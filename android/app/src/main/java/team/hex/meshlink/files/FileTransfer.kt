package team.hex.meshlink.files

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.MeshRouter
import team.hex.meshlink.mesh.MsgType
import team.hex.meshlink.storage.FileRow
import team.hex.meshlink.storage.MeshDb
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Chunked file transfer over the mesh.
 *
 * Wire protocol (all payloads JSON, then optionally base64'd & wrapped in a
 * MeshMessage by the router):
 *
 *   FILE_OFFER     {transferId, name, size, sha256, chunkSize, chunkCount}
 *   FILE_ACCEPT    {transferId, fromOffset}
 *   FILE_REJECT    {transferId}
 *   FILE_CHUNK     {transferId, idx, dataB64}
 *   FILE_COMPLETE  {transferId}
 *
 * The router carries each payload inside a signed envelope. Chunk
 * payloads are AES-GCM encrypted with the same X25519 ECDH session key
 * we use for text — the file transfer module just hands plaintext to the
 * service, which encrypts before sending.
 *
 * Resume: receivers persist `bytes_done` after every chunk; if the
 * transfer is interrupted, the receiver re-issues FILE_ACCEPT with
 * `fromOffset = bytes_done` and the sender skips already-acknowledged
 * chunks.
 */
class FileTransfer(
    private val context: Context,
    private val db: MeshDb,
    private val router: MeshRouter,
    private val sendEncrypted: suspend (peerId: String, type: Int, payload: ByteArray) -> Unit,
) {
    /** Begin offering a file from a content URI. Returns transferId. */
    suspend fun offer(peerId: String, uri: Uri, displayName: String): String {
        val resolver = context.contentResolver
        val tmp = File.createTempFile("ml_", ".bin", context.cacheDir)
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf); if (r <= 0) break
                    sha.update(buf, 0, r); out.write(buf, 0, r); size += r
                }
            }
        }
        val digest = sha.digest()
        val transferId = UUID.randomUUID().toString()

        db.fileDao().upsert(FileRow(
            transferId = transferId,
            peerId = peerId,
            outgoing = true,
            name = displayName,
            size = size,
            sha256 = digest,
            localPath = tmp.absolutePath,
            bytesDone = 0,
            state = "pending",
        ))

        val offer = FileOfferPayload(
            transferId = transferId,
            name = displayName,
            size = size,
            sha256B64 = Crypto.b64(digest),
            chunkSize = CHUNK_SIZE,
            chunkCount = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt(),
        )
        sendEncrypted(peerId, MsgType.FILE_OFFER, offer.toBytes())
        return transferId
    }

    /** Receiver accepts an offer and we start streaming chunks back. */
    suspend fun accept(transferId: String, fromOffset: Long = 0) {
        val row = db.fileDao().byId(transferId) ?: return
        sendEncrypted(row.peerId, MsgType.FILE_ACCEPT,
            FileAcceptPayload(transferId, fromOffset).toBytes())
    }

    /** Sender side: stream chunks after receiving FILE_ACCEPT. */
    suspend fun streamChunks(transferId: String, fromOffset: Long) {
        val row = db.fileDao().byId(transferId) ?: return
        if (!row.outgoing) return
        db.fileDao().progress(transferId, fromOffset, "active")
        val file = File(row.localPath)
        file.inputStream().use { input ->
            input.skip(fromOffset)
            var idx = (fromOffset / CHUNK_SIZE).toInt()
            var sent = fromOffset
            val buf = ByteArray(CHUNK_SIZE)
            while (true) {
                val r = input.read(buf); if (r <= 0) break
                val chunkBytes = if (r == buf.size) buf else buf.copyOf(r)
                val payload = FileChunkPayload(
                    transferId = transferId, idx = idx, dataB64 = Crypto.b64(chunkBytes)
                ).toBytes()
                sendEncrypted(row.peerId, MsgType.FILE_CHUNK, payload)
                sent += r; idx += 1
                db.fileDao().progress(transferId, sent, "active")
            }
        }
        sendEncrypted(row.peerId, MsgType.FILE_COMPLETE,
            FileCompletePayload(transferId).toBytes())
        db.fileDao().progress(transferId, row.size, "done")
    }

    /** Receiver-side dispatch from MeshService. */
    suspend fun onIncomingPlaintext(envelope: MeshMessage, plaintext: ByteArray) {
        when (envelope.type) {
            MsgType.FILE_OFFER -> {
                val offer = FileOfferPayload.fromBytes(plaintext) ?: return
                val downloads = File(context.filesDir, "downloads").apply { mkdirs() }
                val safeName = offer.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val outFile = uniqueChild(downloads, safeName.ifBlank { "shared.bin" })
                // Pre-allocate so chunks can be written at their idx*chunkSize
                // offset out-of-order without corrupting the file.
                runCatching {
                    RandomAccessFile(outFile, "rw").use { it.setLength(offer.size) }
                }
                db.fileDao().upsert(FileRow(
                    transferId = offer.transferId,
                    peerId = envelope.senderId,
                    outgoing = false,
                    name = offer.name,
                    size = offer.size,
                    sha256 = Crypto.unb64(offer.sha256B64),
                    localPath = outFile.absolutePath,
                    bytesDone = 0,
                    state = "pending",
                ))
                // Auto-accept; UI can later expose explicit consent flow.
                accept(offer.transferId, fromOffset = 0)
            }
            MsgType.FILE_ACCEPT -> {
                val a = FileAcceptPayload.fromBytes(plaintext) ?: return
                streamChunks(a.transferId, a.fromOffset)
            }
            MsgType.FILE_REJECT -> {
                val r = FileRejectPayload.fromBytes(plaintext) ?: return
                db.fileDao().progress(r.transferId, 0, "failed")
            }
            MsgType.FILE_CHUNK -> {
                val c = FileChunkPayload.fromBytes(plaintext) ?: return
                val row = db.fileDao().byId(c.transferId) ?: return
                if (row.outgoing) return
                val data = Crypto.unb64(c.dataB64)
                // Write at the chunk's deterministic offset so out-of-order
                // delivery (different relay paths, different MTU paths)
                // doesn't corrupt the file.
                val offset = c.idx.toLong() * CHUNK_SIZE
                runCatching {
                    RandomAccessFile(File(row.localPath), "rw").use { raf ->
                        raf.seek(offset)
                        raf.write(data)
                    }
                }
                val newDone = (row.bytesDone + data.size).coerceAtMost(row.size)
                db.fileDao().progress(c.transferId, newDone, "active")
            }
            MsgType.FILE_COMPLETE -> {
                val c = FileCompletePayload.fromBytes(plaintext) ?: return
                val row = db.fileDao().byId(c.transferId) ?: return
                val sha = MessageDigest.getInstance("SHA-256")
                File(row.localPath).inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf); if (r <= 0) break
                        sha.update(buf, 0, r)
                    }
                }
                val ok = sha.digest().contentEquals(row.sha256)
                db.fileDao().progress(c.transferId, row.size, if (ok) "done" else "failed")
            }
        }
    }

    private fun uniqueChild(dir: File, baseName: String): File {
        var candidate = File(dir, baseName)
        if (!candidate.exists()) return candidate
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    companion object {
        const val CHUNK_SIZE = 32 * 1024
    }
}

@Serializable
data class FileOfferPayload(
    val transferId: String,
    val name: String,
    val size: Long,
    val sha256B64: String,
    val chunkSize: Int,
    val chunkCount: Int,
) { fun toBytes(): ByteArray = json(this) ; companion object { fun fromBytes(b: ByteArray) = parse<FileOfferPayload>(b) } }

@Serializable
data class FileAcceptPayload(val transferId: String, val fromOffset: Long) {
    fun toBytes(): ByteArray = json(this); companion object { fun fromBytes(b: ByteArray) = parse<FileAcceptPayload>(b) }
}

@Serializable
data class FileRejectPayload(val transferId: String) {
    fun toBytes(): ByteArray = json(this); companion object { fun fromBytes(b: ByteArray) = parse<FileRejectPayload>(b) }
}

@Serializable
data class FileChunkPayload(val transferId: String, val idx: Int, val dataB64: String) {
    fun toBytes(): ByteArray = json(this); companion object { fun fromBytes(b: ByteArray) = parse<FileChunkPayload>(b) }
}

@Serializable
data class FileCompletePayload(val transferId: String) {
    fun toBytes(): ByteArray = json(this); companion object { fun fromBytes(b: ByteArray) = parse<FileCompletePayload>(b) }
}

private inline fun <reified T> json(value: T): ByteArray =
    MeshMessage.json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray(Charsets.UTF_8)

private inline fun <reified T> parse(b: ByteArray): T? = try {
    MeshMessage.json.decodeFromString(kotlinx.serialization.serializer<T>(), b.toString(Charsets.UTF_8))
} catch (_: Throwable) { null }
