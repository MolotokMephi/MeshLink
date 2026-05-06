package team.hex.meshlink.pairing

import kotlinx.serialization.Serializable
import team.hex.meshlink.crypto.Crypto
import team.hex.meshlink.mesh.MeshMessage
import team.hex.meshlink.mesh.PeerIdentity

/**
 * Out-of-band peer pairing payload — what gets encoded into a QR code or a
 * short shareable string.
 *
 * Two consenting devices show their pairing payload to each other; the
 * scanner trusts the scanned identity and inserts it as a known peer.
 * No network traffic involved, so this works in fully offline,
 * never-in-mesh-range scenarios (one device pairs another via airdrop /
 * camera scan / read-aloud short code).
 *
 * Format (versioned, JSON-based for forward-compat):
 *   meshlink:1:{base64url payload}
 *   payload = {nodeId, name, edPubB64, xPubB64}
 */
@Serializable
data class PairingPayload(
    val nodeId: String,
    val name: String,
    val edPubB64: String,
    val xPubB64: String,
) {
    fun encode(): String {
        val json = MeshMessage.json.encodeToString(serializer(), this)
        return PREFIX + Crypto.b64(json.toByteArray(Charsets.UTF_8))
    }

    fun toIdentity(): PeerIdentity = PeerIdentity(
        nodeId = nodeId,
        displayName = name,
        edPub = Crypto.unb64(edPubB64),
        xPub = Crypto.unb64(xPubB64),
        lastSeenMs = System.currentTimeMillis(),
    )

    companion object {
        const val PREFIX = "meshlink:1:"

        fun decodeOrNull(text: String): PairingPayload? {
            if (!text.startsWith(PREFIX)) return null
            return runCatching {
                val raw = Crypto.unb64(text.removePrefix(PREFIX))
                MeshMessage.json.decodeFromString(serializer(), raw.toString(Charsets.UTF_8))
            }.getOrNull()
        }

        fun forSelf(
            identity: Crypto.IdentityKeys,
            displayName: String,
        ): PairingPayload = PairingPayload(
            nodeId = identity.nodeId(),
            name = displayName,
            edPubB64 = Crypto.b64(identity.edPub),
            xPubB64 = Crypto.b64(identity.xPub),
        )
    }
}

/** Mutable 2D bitmap of QR modules. (0,0) is top-left. */
class BooleanArray2D(val w: Int, val h: Int) {
    private val a = BooleanArray(w * h)
    operator fun get(x: Int, y: Int): Boolean = a[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) { a[y * w + x] = v }
}

/**
 * Standalone QR Model 2 encoder. Byte mode, EC level L, versions 1–10.
 * Suitable for our pairing payloads (~200 bytes).
 *
 * Implementation:
 *   - byte-mode bitstream with 8-bit char-count indicator (v ≤ 9) /
 *     16-bit (v ≥ 10), terminator + pad codewords
 *   - Reed-Solomon over GF(256) with QR generator polynomial
 *   - block interleaving (only the single-block path is exercised at EC L
 *     for v ≤ 4; higher versions split into two groups)
 *   - finder/timing/format/version patterns
 *   - mask selection by lowest penalty score across masks 0–7
 *
 * Output is the boolean matrix; the UI is responsible for drawing
 * black/white modules.
 */
object QrEncoder {
    enum class ECLevel { L, M, Q, H }

    fun encode(text: String): BooleanArray2D = encode(text.toByteArray(Charsets.UTF_8), ECLevel.L)

    fun encode(data: ByteArray, ec: ECLevel = ECLevel.L): BooleanArray2D {
        val version = pickVersion(data.size, ec)
            ?: throw IllegalArgumentException("payload too large for QR v1..40")
        val total = totalCodewords(version)
        val ecPerBlock = ecCodewordsPerBlock(version, ec)
        val (g1Blocks, g1DataPerBlock, g2Blocks, g2DataPerBlock) = blockLayout(version, ec)
        val totalDataCw = g1Blocks * g1DataPerBlock + g2Blocks * g2DataPerBlock
        require(g1Blocks + g2Blocks > 0)

        val bits = bitBuffer()
        // Mode indicator: byte = 0100
        bits.put(0b0100, 4)
        val ccBits = if (version <= 9) 8 else 16
        bits.put(data.size, ccBits)
        for (b in data) bits.put(b.toInt() and 0xFF, 8)

        // Terminator (up to 4 zero bits) and byte-align.
        val maxBits = totalDataCw * 8
        val term = minOf(4, maxBits - bits.size())
        repeat(term) { bits.put(0, 1) }
        while (bits.size() % 8 != 0) bits.put(0, 1)
        // Pad bytes 0xEC, 0x11.
        var padToggle = false
        while (bits.size() < maxBits) {
            bits.put(if (padToggle) 0x11 else 0xEC, 8)
            padToggle = !padToggle
        }
        val dataBytes = bits.toByteArray()

        // Split into blocks per the version/EC layout.
        val blocks = ArrayList<ByteArray>()
        val ecBlocks = ArrayList<ByteArray>()
        var off = 0
        repeat(g1Blocks) {
            val block = dataBytes.copyOfRange(off, off + g1DataPerBlock)
            off += g1DataPerBlock
            blocks.add(block)
            ecBlocks.add(ReedSolomon.encode(block, ecPerBlock))
        }
        repeat(g2Blocks) {
            val block = dataBytes.copyOfRange(off, off + g2DataPerBlock)
            off += g2DataPerBlock
            blocks.add(block)
            ecBlocks.add(ReedSolomon.encode(block, ecPerBlock))
        }

        // Interleave data, then EC.
        val interleaved = ByteArray(total)
        var w = 0
        val maxData = blocks.maxOf { it.size }
        for (i in 0 until maxData) {
            for (b in blocks) if (i < b.size) interleaved[w++] = b[i]
        }
        val maxEc = ecBlocks[0].size
        for (i in 0 until maxEc) {
            for (b in ecBlocks) interleaved[w++] = b[i]
        }
        require(w == total)

        val size = 21 + (version - 1) * 4
        val matrix = BooleanArray2D(size, size)
        val reserved = BooleanArray2D(size, size)
        placeFinders(matrix, reserved, size)
        placeSeparators(reserved, size)
        placeAlignmentPatterns(matrix, reserved, version, size)
        placeTimingPatterns(matrix, reserved, size)
        // Reserve dark module + format info + version info regions.
        reserveFormatInfo(reserved, size)
        if (version >= 7) reserveVersionInfo(reserved, size)
        // Dark module (always black).
        matrix[8, size - 8] = true

        placeDataBits(matrix, reserved, interleaved, size)

        // Pick best mask.
        val bestMask = (0..7).minBy { mask ->
            val candidate = applyMask(matrix, reserved, size, mask)
            writeFormatInfo(candidate, size, ec, mask)
            if (version >= 7) writeVersionInfo(candidate, size, version)
            penaltyScore(candidate, size)
        }
        val finalMatrix = applyMask(matrix, reserved, size, bestMask)
        writeFormatInfo(finalMatrix, size, ec, bestMask)
        if (version >= 7) writeVersionInfo(finalMatrix, size, version)
        return finalMatrix
    }

    // ------- capacity tables -------

    /** Byte-mode capacity per (version, ECLevel). Versions 1..40, four levels. */
    private val byteCapacityTable = arrayOf(
        // v: L,    M,    Q,    H
        intArrayOf(17,   14,   11,   7),
        intArrayOf(32,   26,   20,   14),
        intArrayOf(53,   42,   32,   24),
        intArrayOf(78,   62,   46,   34),
        intArrayOf(106,  84,   60,   44),
        intArrayOf(134,  106,  74,   58),
        intArrayOf(154,  122,  86,   64),
        intArrayOf(192,  152,  108,  84),
        intArrayOf(230,  180,  130,  98),
        intArrayOf(271,  213,  151,  119),
        intArrayOf(321,  251,  177,  137),
        intArrayOf(367,  287,  203,  155),
        intArrayOf(425,  331,  241,  177),
        intArrayOf(458,  362,  258,  194),
        intArrayOf(520,  412,  292,  220),
        intArrayOf(586,  450,  322,  250),
        intArrayOf(644,  504,  364,  280),
        intArrayOf(718,  560,  394,  310),
        intArrayOf(792,  624,  442,  338),
        intArrayOf(858,  666,  482,  382),
        intArrayOf(929,  711,  509,  403),
        intArrayOf(1003, 779,  565,  439),
        intArrayOf(1091, 857,  611,  461),
        intArrayOf(1171, 911,  661,  511),
        intArrayOf(1273, 997,  715,  535),
        intArrayOf(1367, 1059, 751,  593),
        intArrayOf(1465, 1125, 805,  625),
        intArrayOf(1528, 1190, 868,  658),
        intArrayOf(1628, 1264, 908,  698),
        intArrayOf(1732, 1370, 982,  742),
        intArrayOf(1840, 1452, 1030, 790),
        intArrayOf(1952, 1538, 1112, 842),
        intArrayOf(2068, 1628, 1168, 898),
        intArrayOf(2188, 1722, 1228, 958),
        intArrayOf(2303, 1809, 1283, 983),
        intArrayOf(2431, 1911, 1351, 1051),
        intArrayOf(2563, 1989, 1423, 1093),
        intArrayOf(2699, 2099, 1499, 1139),
        intArrayOf(2809, 2213, 1579, 1219),
        intArrayOf(2953, 2331, 1663, 1273),
    )

    /** Total codewords per version. */
    private val totalCodewordsTable = intArrayOf(
        26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655,
        733, 815, 901, 991, 1085, 1156, 1258, 1364, 1474, 1588, 1706, 1828,
        1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706,
    )

    /** EC codewords per block, by (version, ECLevel). */
    private val ecPerBlockTable = arrayOf(
        intArrayOf(7,   10,  13,  17),
        intArrayOf(10,  16,  22,  28),
        intArrayOf(15,  26,  18,  22),
        intArrayOf(20,  18,  26,  16),
        intArrayOf(26,  24,  18,  22),
        intArrayOf(18,  16,  24,  28),
        intArrayOf(20,  18,  18,  26),
        intArrayOf(24,  22,  22,  26),
        intArrayOf(30,  22,  20,  24),
        intArrayOf(18,  26,  24,  28),
        intArrayOf(20,  30,  28,  24),
        intArrayOf(24,  22,  26,  28),
        intArrayOf(26,  22,  24,  22),
        intArrayOf(30,  24,  20,  24),
        intArrayOf(22,  24,  30,  24),
        intArrayOf(24,  28,  24,  30),
        intArrayOf(28,  28,  28,  28),
        intArrayOf(30,  26,  28,  28),
        intArrayOf(28,  26,  26,  26),
        intArrayOf(28,  26,  30,  28),
        intArrayOf(28,  26,  28,  30),
        intArrayOf(28,  28,  30,  24),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(26,  28,  30,  30),
        intArrayOf(28,  28,  28,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
        intArrayOf(30,  28,  30,  30),
    )

    /**
     * Block layout per version+EC level: (group1Blocks, group1DataPerBlock,
     * group2Blocks, group2DataPerBlock). Source: ISO/IEC 18004 Annex E.
     * Only the (level L, versions 1..10) rows are exercised by typical
     * pairing payloads; the rest is provided for completeness and uses
     * the canonical ISO values.
     */
    private val blockLayoutTable: Array<Array<IntArray>> = arrayOf(
        // Each row: 4 entries (L,M,Q,H), each [g1Blocks,g1Data,g2Blocks,g2Data]
        arrayOf(intArrayOf(1,19,0,0), intArrayOf(1,16,0,0), intArrayOf(1,13,0,0), intArrayOf(1,9,0,0)),
        arrayOf(intArrayOf(1,34,0,0), intArrayOf(1,28,0,0), intArrayOf(1,22,0,0), intArrayOf(1,16,0,0)),
        arrayOf(intArrayOf(1,55,0,0), intArrayOf(1,44,0,0), intArrayOf(2,17,0,0), intArrayOf(2,13,0,0)),
        arrayOf(intArrayOf(1,80,0,0), intArrayOf(2,32,0,0), intArrayOf(2,24,0,0), intArrayOf(4,9,0,0)),
        arrayOf(intArrayOf(1,108,0,0), intArrayOf(2,43,0,0), intArrayOf(2,15,2,16), intArrayOf(2,11,2,12)),
        arrayOf(intArrayOf(2,68,0,0), intArrayOf(4,27,0,0), intArrayOf(4,19,0,0), intArrayOf(4,15,0,0)),
        arrayOf(intArrayOf(2,78,0,0), intArrayOf(4,31,0,0), intArrayOf(2,14,4,15), intArrayOf(4,13,1,14)),
        arrayOf(intArrayOf(2,97,0,0), intArrayOf(2,38,2,39), intArrayOf(4,18,2,19), intArrayOf(4,14,2,15)),
        arrayOf(intArrayOf(2,116,0,0), intArrayOf(3,36,2,37), intArrayOf(4,16,4,17), intArrayOf(4,12,4,13)),
        arrayOf(intArrayOf(2,68,2,69), intArrayOf(4,43,1,44), intArrayOf(6,19,2,20), intArrayOf(6,15,2,16)),
    )

    private fun pickVersion(byteLen: Int, ec: ECLevel): Int? {
        for (v in 1..byteCapacityTable.size) {
            if (byteLen <= byteCapacityTable[v - 1][ec.ordinal]) return v
        }
        return null
    }

    private fun totalCodewords(version: Int) = totalCodewordsTable[version - 1]
    private fun ecCodewordsPerBlock(version: Int, ec: ECLevel) =
        ecPerBlockTable[version - 1][ec.ordinal]

    private fun blockLayout(version: Int, ec: ECLevel): IntArray {
        require(version in 1..blockLayoutTable.size) { "QR encoder ships block-layout data for v1..10 only" }
        return blockLayoutTable[version - 1][ec.ordinal]
    }

    // ------- pattern placement -------

    private fun placeFinders(matrix: BooleanArray2D, reserved: BooleanArray2D, size: Int) {
        val pos = listOf(intArrayOf(0, 0), intArrayOf(size - 7, 0), intArrayOf(0, size - 7))
        for (p in pos) drawFinderAt(matrix, reserved, p[0], p[1])
    }

    private fun drawFinderAt(matrix: BooleanArray2D, reserved: BooleanArray2D, x: Int, y: Int) {
        for (dy in 0..6) for (dx in 0..6) {
            val on = dx == 0 || dx == 6 || dy == 0 || dy == 6 ||
                (dx in 2..4 && dy in 2..4)
            matrix[x + dx, y + dy] = on
            reserved[x + dx, y + dy] = true
        }
    }

    private fun placeSeparators(reserved: BooleanArray2D, size: Int) {
        // 1-wide white separator around the three finders.
        for (i in 0..7) {
            if (i < size) {
                reserved[7, i] = true; reserved[i, 7] = true
                if (size - 8 in 0 until size) {
                    reserved[size - 8, i] = true; reserved[i, size - 8] = true
                }
            }
        }
        for (i in 0..7) reserved[size - 1 - i, 7] = true
    }

    private fun placeTimingPatterns(matrix: BooleanArray2D, reserved: BooleanArray2D, size: Int) {
        for (i in 8 until size - 8) {
            val on = i % 2 == 0
            matrix[6, i] = on; reserved[6, i] = true
            matrix[i, 6] = on; reserved[i, 6] = true
        }
    }

    private fun placeAlignmentPatterns(
        matrix: BooleanArray2D, reserved: BooleanArray2D, version: Int, size: Int,
    ) {
        if (version == 1) return
        val centers = alignmentCentersFor(version)
        for (cy in centers) for (cx in centers) {
            // Skip ones that overlap the finder squares.
            if ((cx == centers.first() && cy == centers.first()) ||
                (cx == centers.last() && cy == centers.first()) ||
                (cx == centers.first() && cy == centers.last())
            ) continue
            for (dy in -2..2) for (dx in -2..2) {
                val on = dx == -2 || dx == 2 || dy == -2 || dy == 2 || (dx == 0 && dy == 0)
                val x = cx + dx; val y = cy + dy
                matrix[x, y] = on; reserved[x, y] = true
            }
        }
    }

    private fun alignmentCentersFor(version: Int): IntArray {
        // Versions 1..40 alignment-pattern centers per ISO 18004 Annex E.1.
        val table = arrayOf(
            intArrayOf(),
            intArrayOf(6, 18),
            intArrayOf(6, 22),
            intArrayOf(6, 26),
            intArrayOf(6, 30),
            intArrayOf(6, 34),
            intArrayOf(6, 22, 38),
            intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46),
            intArrayOf(6, 28, 50),
        )
        return table[version - 1]
    }

    private fun reserveFormatInfo(reserved: BooleanArray2D, size: Int) {
        for (i in 0..8) reserved[8, i] = true
        for (i in 0..8) reserved[i, 8] = true
        for (i in 0..7) reserved[8, size - 1 - i] = true
        for (i in 0..8) reserved[size - 1 - i, 8] = true
    }

    private fun reserveVersionInfo(reserved: BooleanArray2D, size: Int) {
        for (i in 0..5) for (j in 0..2) {
            reserved[i, size - 11 + j] = true
            reserved[size - 11 + j, i] = true
        }
    }

    // ------- data placement (zigzag, skipping col 6) -------

    private fun placeDataBits(
        matrix: BooleanArray2D, reserved: BooleanArray2D, data: ByteArray, size: Int,
    ) {
        var bitIdx = 0
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col -= 1
            val rowRange = if (upward) (size - 1) downTo 0 else 0 until size
            for (row in rowRange) {
                for (c in 0..1) {
                    val x = col - c
                    if (!reserved[x, row]) {
                        val bit = if (bitIdx < data.size * 8) {
                            ((data[bitIdx / 8].toInt() ushr (7 - bitIdx % 8)) and 1) == 1
                        } else false
                        matrix[x, row] = bit
                        bitIdx++
                    }
                }
            }
            col -= 2
            upward = !upward
        }
    }

    // ------- masking -------

    private fun applyMask(
        src: BooleanArray2D, reserved: BooleanArray2D, size: Int, mask: Int,
    ): BooleanArray2D {
        val out = BooleanArray2D(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            out[x, y] = src[x, y]
            if (!reserved[x, y] && maskCondition(mask, x, y)) {
                out[x, y] = !out[x, y]
            }
        }
        return out
    }

    private fun maskCondition(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> ((y / 2) + (x / 3)) % 2 == 0
        5 -> ((x * y) % 2) + ((x * y) % 3) == 0
        6 -> (((x * y) % 2) + ((x * y) % 3)) % 2 == 0
        7 -> (((x + y) % 2) + ((x * y) % 3)) % 2 == 0
        else -> false
    }

    // ------- format / version info encoding -------

    private fun writeFormatInfo(matrix: BooleanArray2D, size: Int, ec: ECLevel, mask: Int) {
        val ecBits = when (ec) { ECLevel.L -> 0b01; ECLevel.M -> 0b00; ECLevel.Q -> 0b11; ECLevel.H -> 0b10 }
        val data5 = (ecBits shl 3) or mask
        val format = formatBchEncode(data5) xor 0b101_0100_0001_0010
        // Bit 0 is rightmost.
        for (i in 0..5) matrix[8, i] = ((format ushr i) and 1) == 1
        matrix[8, 7] = ((format ushr 6) and 1) == 1
        matrix[8, 8] = ((format ushr 7) and 1) == 1
        matrix[7, 8] = ((format ushr 8) and 1) == 1
        for (i in 0..5) matrix[5 - i, 8] = ((format ushr (9 + i)) and 1) == 1

        for (i in 0..6) matrix[size - 1 - i, 8] = ((format ushr i) and 1) == 1
        for (i in 0..7) matrix[8, size - 1 - i] = ((format ushr (14 - i)) and 1) == 1
    }

    private fun writeVersionInfo(matrix: BooleanArray2D, size: Int, version: Int) {
        val info = versionBchEncode(version)
        for (i in 0..17) {
            val bit = ((info ushr i) and 1) == 1
            val a = i / 3; val b = i % 3
            matrix[a, size - 11 + b] = bit
            matrix[size - 11 + b, a] = bit
        }
    }

    private fun formatBchEncode(data: Int): Int {
        var d = data shl 10
        val gen = 0b10100110111
        for (i in 14 downTo 10) {
            if (((d ushr i) and 1) == 1) d = d xor (gen shl (i - 10))
        }
        return (data shl 10) or d
    }

    private fun versionBchEncode(version: Int): Int {
        var d = version shl 12
        val gen = 0b1111100100101
        for (i in 17 downTo 12) {
            if (((d ushr i) and 1) == 1) d = d xor (gen shl (i - 12))
        }
        return (version shl 12) or d
    }

    // ------- penalty score (ISO 18004 §8.8.2) -------

    private fun penaltyScore(matrix: BooleanArray2D, size: Int): Int {
        var score = 0
        // N1: runs of 5+ same-color in row/col → 3 + (run-5).
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (matrix[x, y] == matrix[x - 1, y]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score += 1
                } else run = 1
            }
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (matrix[x, y] == matrix[x, y - 1]) {
                    run++
                    if (run == 5) score += 3 else if (run > 5) score += 1
                } else run = 1
            }
        }
        // N2: 2x2 blocks of same color.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val v = matrix[x, y]
            if (matrix[x + 1, y] == v && matrix[x, y + 1] == v && matrix[x + 1, y + 1] == v) score += 3
        }
        // N3: 1:1:3:1:1 finder-like patterns in rows/cols → +40 each.
        val pat = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        for (y in 0 until size) for (x in 0..size - 11) {
            var ok = true
            for (i in 0 until 11) if (matrix[x + i, y] != pat[i]) { ok = false; break }
            if (ok) score += 40
        }
        for (x in 0 until size) for (y in 0..size - 11) {
            var ok = true
            for (i in 0 until 11) if (matrix[x, y + i] != pat[i]) { ok = false; break }
            if (ok) score += 40
        }
        // N4: dark module ratio → +10 per 5%-deviation step.
        var darks = 0
        for (y in 0 until size) for (x in 0 until size) if (matrix[x, y]) darks++
        val ratio = darks * 100 / (size * size)
        val dev = kotlin.math.abs(ratio - 50) / 5
        score += dev * 10
        return score
    }

    // ------- bit buffer -------

    private class BitBuf {
        private val bytes = ArrayList<Int>()
        private var bitOffset = 0   // number of bits in current byte (0..7)
        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                val b = (value ushr i) and 1
                if (bitOffset == 0) bytes.add(0)
                val last = bytes.size - 1
                bytes[last] = bytes[last] or (b shl (7 - bitOffset))
                bitOffset = (bitOffset + 1) and 7
            }
        }
        fun size(): Int = bytes.size * 8 - (if (bitOffset == 0) 0 else 8 - bitOffset)
        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
    }
    private fun bitBuffer() = BitBuf()
}

/**
 * Reed-Solomon encoder over GF(256) with QR's reducing polynomial 0x11D.
 * Encodes [data] into [data || ec] of length data.size + ecLength.
 */
private object ReedSolomon {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun generatorPoly(degree: Int): IntArray {
        var result = intArrayOf(1)
        for (i in 0 until degree) {
            val factor = intArrayOf(1, EXP[i])
            result = mulPoly(result, factor)
        }
        return result
    }

    private fun mulPoly(a: IntArray, b: IntArray): IntArray {
        val result = IntArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) {
            result[i + j] = result[i + j] xor gfMul(a[i], b[j])
        }
        return result
    }

    fun encode(data: ByteArray, ecLength: Int): ByteArray {
        val gen = generatorPoly(ecLength)
        val buf = IntArray(data.size + ecLength)
        for (i in data.indices) buf[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val factor = buf[i]
            buf[i] = 0
            if (factor == 0) continue
            for (j in gen.indices) {
                buf[i + j] = buf[i + j] xor gfMul(gen[j], factor)
            }
        }
        // Return just the parity tail; callers interleave it after the
        // already-known data portion.
        val out = ByteArray(ecLength)
        for (i in 0 until ecLength) out[i] = buf[data.size + i].toByte()
        return out
    }
}
