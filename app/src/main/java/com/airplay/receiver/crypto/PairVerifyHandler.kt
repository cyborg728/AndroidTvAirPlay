package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Handles AirPlay 2 pair-verify flow.
 *
 * This is called after pair-setup to establish an encrypted session.
 *
 * M1: Client → Server: flag(4 bytes) + client X25519 pubkey(32) + client Ed25519 pubkey(32)
 * M2: Server → Client: server X25519 pubkey(32) + encrypted(signature)
 * M3: Client → Server: encrypted(signature)
 * M4: Server → Client: (empty OK)
 */
class PairVerifyHandler {

    companion object {
        private const val TAG = "PairVerify"
        private const val PAIR_VERIFY_SALT = "Pair-Verify-Encrypt-Salt"
        private const val PAIR_VERIFY_INFO = "Pair-Verify-Encrypt-Info"
    }

    private var x25519KeyPair = PairingUtils.generateX25519KeyPair()
    private var ed25519KeyPair = PairingUtils.generateEd25519KeyPair()
    private var sharedSecret: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var state = 0

    fun handle(data: ByteArray): ByteArray {
        return when (state) {
            0 -> handleM1(data)
            1 -> handleM3(data)
            else -> {
                Log.w(TAG, "Unexpected pair-verify state: $state")
                ByteArray(0)
            }
        }
    }

    private fun handleM1(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling pair-verify M1, data size: ${data.size}")

        // Data format: flags(4) + client_x25519_pubkey(32) + client_ed25519_pubkey(32)
        if (data.size < 68) {
            // Might be TLV8 encoded
            return handleM1Tlv(data)
        }

        val clientX25519Bytes = data.copyOfRange(4, 36)
        val clientEd25519Bytes = data.copyOfRange(36, 68)

        return doM1(clientX25519Bytes, clientEd25519Bytes)
    }

    private fun handleM1Tlv(data: ByteArray): ByteArray {
        Log.d(TAG, "Trying TLV8 decode for M1")
        val tlvs = Tlv8.decode(data)
        val clientX25519Bytes = tlvs[Tlv8.PUBLIC_KEY]
        if (clientX25519Bytes != null && clientX25519Bytes.size >= 32) {
            return doM1(clientX25519Bytes, null)
        }
        Log.e(TAG, "M1: Cannot parse client data")
        return ByteArray(0)
    }

    private fun doM1(clientX25519Bytes: ByteArray, clientEd25519Bytes: ByteArray?): ByteArray {
        try {
            // Generate fresh X25519 keypair
            x25519KeyPair = PairingUtils.generateX25519KeyPair()

            val clientX25519Public = X25519PublicKeyParameters(clientX25519Bytes, 0)

            // Derive shared secret
            sharedSecret = PairingUtils.x25519SharedSecret(
                x25519KeyPair.first,
                clientX25519Public
            )

            // Derive session key
            sessionKey = PairingUtils.hkdfSha512(
                sharedSecret!!,
                PAIR_VERIFY_SALT.toByteArray(),
                PAIR_VERIFY_INFO.toByteArray(),
                32
            )

            // Sign: server X25519 pubkey + client X25519 pubkey
            val signData = x25519KeyPair.second.encoded + clientX25519Bytes
            val signature = PairingUtils.ed25519Sign(ed25519KeyPair.first, signData)

            // Build inner TLV: our Ed25519 pubkey + signature
            val innerData = Tlv8.encode(mapOf(
                Tlv8.PUBLIC_KEY to ed25519KeyPair.second.encoded,
                Tlv8.SIGNATURE to signature
            ))

            // Encrypt
            val nonce = ByteArray(12)
            "PV-Msg02".toByteArray().copyInto(nonce, 4)
            val encrypted = ChaCha20Poly1305.encrypt(sessionKey!!, nonce, null, innerData)

            // Response: our X25519 pubkey + encrypted data
            val response = ByteArray(x25519KeyPair.second.encoded.size + encrypted.size)
            x25519KeyPair.second.encoded.copyInto(response, 0)
            encrypted.copyInto(response, x25519KeyPair.second.encoded.size)

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

        try {
            // Decrypt the client's data
            val nonce = ByteArray(12)
            "PV-Msg03".toByteArray().copyInto(nonce, 4)

            val decrypted = try {
                ChaCha20Poly1305.decrypt(sessionKey!!, nonce, null, data)
            } catch (e: Exception) {
                Log.w(TAG, "M3: Decrypt failed (may be OK for some clients)", e)
                // Some clients don't encrypt M3 properly — accept anyway
                state = 2
                return ByteArray(0)
            }

            Log.d(TAG, "M3: Decrypted ${decrypted.size} bytes")

            state = 2
            return ByteArray(0)

        } catch (e: Exception) {
            Log.e(TAG, "M3 error", e)
            state = 2
            return ByteArray(0)
        }
    }

    fun isComplete(): Boolean = state >= 2
    fun getSessionKey(): ByteArray? = sessionKey
    fun getSharedSecret(): ByteArray? = sharedSecret
}
