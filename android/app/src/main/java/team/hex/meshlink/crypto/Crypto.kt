package team.hex.meshlink.crypto

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors core/crypto.py: X25519 ECDH for session keys, AES-256-GCM for
 * message encryption, Ed25519 for message signing. Identity = Ed25519
 * public key fingerprint (sha256, first 8 bytes hex).
 */
object Crypto {
    private val rng = SecureRandom()
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12

    data class IdentityKeys(
        val edPriv: ByteArray,    // 32
        val edPub: ByteArray,     // 32
        val xPriv: ByteArray,     // 32
        val xPub: ByteArray,      // 32
    ) {
        fun nodeId(): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(edPub)
            return digest.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
        }
    }

    fun generateIdentity(): IdentityKeys {
        val edGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(rng))
        }
        val edPair = edGen.generateKeyPair()
        val edPriv = (edPair.private as Ed25519PrivateKeyParameters).encoded
        val edPub = (edPair.public as Ed25519PublicKeyParameters).encoded

        val xGen = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(rng))
        }
        val xPair = xGen.generateKeyPair()
        val xPriv = (xPair.private as X25519PrivateKeyParameters).encoded
        val xPub = (xPair.public as X25519PublicKeyParameters).encoded
        return IdentityKeys(edPriv, edPub, xPriv, xPub)
    }

    /** Derive a 32-byte session key via X25519 ECDH + SHA-256. */
    fun deriveSessionKey(myXPriv: ByteArray, peerXPub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(myXPriv, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerXPub, 0), shared, 0)
        return MessageDigest.getInstance("SHA-256").digest(shared)
    }

    /** Returns nonce(12) || ciphertext||tag. */
    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    fun aesGcmDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == 32)
        require(blob.size > GCM_NONCE_BYTES)
        val nonce = blob.copyOfRange(0, GCM_NONCE_BYTES)
        val ct = blob.copyOfRange(GCM_NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    fun sign(edPriv: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(edPriv, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(edPub: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(edPub, 0))
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (_: Throwable) {
            false
        }
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
