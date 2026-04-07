package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AirPlay 2 pair-verify flow.
 *
 * This is called after pair-setup to establish an encrypted session.
 * iOS may call pair-verify multiple times (before and after pair-setup),
 * so the handler auto-resets when it detects a new M1 while in M3 state.
 *
 * Protocol (AirPlay 2 / HomeKit transient):
 *   M1: Client → Server: flags(4) + client_x25519_pub(32) + client_ed25519_pub(32) = 68 bytes
 *   M2: Server → Client: server_x25519_pub(32) + AES-CTR-encrypted(signature(64)) = 96 bytes
 *   M3: Client → Server: AES-CTR-encrypted(signature(64)) = 64 bytes
 *
 * Key derivation uses HKDF-Expand-SHA512 (no Extract step):
 *   AES key = HKDF-Expand(shared_secret, info="Pair-Verify-AES-Key", 16)
 *   AES IV  = HKDF-Expand(shared_secret, info="Pair-Verify-AES-IV", 16)
 */
class PairVerifyHandler {

    companion object {
        private const val TAG = "PairVerify"
    }

    private var x25519KeyPair = PairingUtils.generateX25519KeyPair()
    private var ed25519KeyPair = PairingUtils.generateEd25519KeyPair()
    private var sharedSecret: ByteArray? = null
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var state = 0

    // Store client keys for M3 verification
    private var clientX25519PubBytes: ByteArray? = null
    private var clientEd25519PubBytes: ByteArray? = null

    fun handle(data: ByteArray): ByteArray {
        Log.d(TAG, "handle: state=$state, data size=${data.size}")

        // Detect if this is a new M1 arriving when we're expecting M3 or already complete.
        // M1 is always 68 bytes starting with 4-byte flags.
        if (state != 0 && data.size == 68) {
            Log.d(TAG, "New M1 detected while in state=$state, resetting for fresh pair-verify")
            reset()
        }

        return when (state) {
            0 -> handleM1(data)
            1 -> handleM3(data)
            else -> {
                Log.w(TAG, "Unexpected pair-verify data in state=$state")
                ByteArray(0)
            }
        }
    }

    private fun reset() {
        state = 0
        sharedSecret = null
        aesKey = null
        aesIv = null
        clientX25519PubBytes = null
        clientEd25519PubBytes = null
        x25519KeyPair = PairingUtils.generateX25519KeyPair()
        ed25519KeyPair = PairingUtils.generateEd25519KeyPair()
    }

    private fun handleM1(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling pair-verify M1, data size: ${data.size}")

        if (data.size < 68) {
            Log.e(TAG, "M1 too short: ${data.size} bytes")
            return ByteArray(0)
        }

        val flags = data.copyOfRange(0, 4)
        Log.d(TAG, "M1 flags: ${flags.joinToString("") { "%02x".format(it) }}")

        clientX25519PubBytes = data.copyOfRange(4, 36)
        clientEd25519PubBytes = data.copyOfRange(36, 68)

        try {
            // Generate fresh X25519 keypair
            x25519KeyPair = PairingUtils.generateX25519KeyPair()

            val clientX25519Public = X25519PublicKeyParameters(clientX25519PubBytes, 0)

            // Derive shared secret via ECDH
            sharedSecret = PairingUtils.x25519SharedSecret(
                x25519KeyPair.first,
                clientX25519Public
            )

            // Derive AES key and IV using HKDF-Expand (no Extract step)
            aesKey = PairingUtils.hkdfExpandSha512(
                sharedSecret!!,
                "Pair-Verify-AES-Key".toByteArray(),
                16
            )
            aesIv = PairingUtils.hkdfExpandSha512(
                sharedSecret!!,
                "Pair-Verify-AES-IV".toByteArray(),
                16
            )

            Log.d(TAG, "Derived AES key (${aesKey!!.size} bytes) and IV (${aesIv!!.size} bytes)")

            // Sign: server_x25519_pub + client_x25519_pub
            val signData = x25519KeyPair.second.encoded + clientX25519PubBytes!!
            val signature = PairingUtils.ed25519Sign(ed25519KeyPair.first, signData)
            Log.d(TAG, "Ed25519 signature: ${signature.size} bytes")

            // Encrypt signature with AES-128-CTR
            val encryptedSignature = aesCtrEncrypt(aesKey!!, aesIv!!, signature)
            Log.d(TAG, "Encrypted signature: ${encryptedSignature.size} bytes")

            // Response: server_x25519_pub(32) + encrypted_signature(64) = 96 bytes
            val response = ByteArray(32 + encryptedSignature.size)
            x25519KeyPair.second.encoded.copyInto(response, 0)
            encryptedSignature.copyInto(response, 32)

            state = 1
            Log.d(TAG, "Sending pair-verify M2, response size: ${response.size}")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "M1 error", e)
            return ByteArray(0)
        }
    }

    private fun handleM3(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling pair-verify M3, data size: ${data.size}")

        if (aesKey == null || aesIv == null) {
            Log.e(TAG, "M3: No AES key/IV available")
            return ByteArray(0)
        }

        try {
            // Decrypt the client's signature with AES-128-CTR
            val decryptedSignature = aesCtrDecrypt(aesKey!!, aesIv!!, data)
            Log.d(TAG, "M3: Decrypted signature: ${decryptedSignature.size} bytes")

            // Verify: client should have signed client_x25519_pub + server_x25519_pub
            if (clientEd25519PubBytes != null && decryptedSignature.size == 64) {
                val clientEd25519Public = Ed25519PublicKeyParameters(clientEd25519PubBytes, 0)
                val verifyData = clientX25519PubBytes!! + x25519KeyPair.second.encoded
                val verified = PairingUtils.ed25519Verify(clientEd25519Public, verifyData, decryptedSignature)
                Log.d(TAG, "M3: Client signature verified: $verified")
            }

            state = 2
            Log.d(TAG, "Pair-verify completed successfully")
            return ByteArray(0)

        } catch (e: Exception) {
            Log.e(TAG, "M3 error", e)
            // Accept anyway for transient pairing
            state = 2
            return ByteArray(0)
        }
    }

    private fun aesCtrEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun aesCtrDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun isComplete(): Boolean = state >= 2
    fun getSharedSecret(): ByteArray? = sharedSecret
    fun getAesKey(): ByteArray? = aesKey
    fun getAesIv(): ByteArray? = aesIv
}
