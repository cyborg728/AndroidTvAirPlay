package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Handles AirPlay 2 pair-verify flow.
 *
 * This is called after pair-setup to establish an encrypted session.
 * iOS may call pair-verify multiple times (before and after pair-setup),
 * so the handler auto-resets when it detects a new M1 while in M3 state.
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
        Log.d(TAG, "handle: state=$state, data size=${data.size}")

        // Detect if this is a new M1 arriving when we're expecting M3.
        // M1 is always 68 bytes (4 flag + 32 X25519 + 32 Ed25519) and starts
        // with a 4-byte flag like 0x01000000. M3 is encrypted data (variable size,
        // typically NOT 68 bytes, and doesn't start with 0x01000000).
        if (state == 1 && isM1Format(data)) {
            Log.d(TAG, "New M1 detected while in state=1, resetting for fresh pair-verify")
            reset()
        }

        return when (state) {
            0 -> handleM1(data)
            1 -> handleM3(data)
            else -> {
                // Already completed — if a new M1 comes, start fresh
                if (isM1Format(data)) {
                    Log.d(TAG, "New M1 after completion, resetting")
                    reset()
                    handleM1(data)
                } else {
                    Log.w(TAG, "Unexpected pair-verify data in state=$state")
                    ByteArray(0)
                }
            }
        }
    }

    /** Check if data looks like an M1 message: 68 bytes, first byte is a flag (typically 1) */
    private fun isM1Format(data: ByteArray): Boolean {
        if (data.size == 68) {
            // First 4 bytes are flags, typically 0x01 0x00 0x00 0x00
            return true
        }
        if (data.size >= 36) {
            // Could also be TLV8 encoded M1 — check for TLV8 state
            val tlvs = Tlv8.decode(data)
            val tlvState = tlvs[Tlv8.STATE]?.firstOrNull()?.toInt()?.and(0xFF)
            if (tlvState == 1) return true
        }
        return false
    }

    private fun reset() {
        state = 0
        sharedSecret = null
        sessionKey = null
        x25519KeyPair = PairingUtils.generateX25519KeyPair()
        ed25519KeyPair = PairingUtils.generateEd25519KeyPair()
    }

    private fun handleM1(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling pair-verify M1, data size: ${data.size}")

        // Data format: flags(4) + client_x25519_pubkey(32) + client_ed25519_pubkey(32)
        if (data.size >= 68) {
            val flags = data.copyOfRange(0, 4)
            Log.d(TAG, "M1 flags: ${flags.joinToString("") { "%02x".format(it) }}")

            val clientX25519Bytes = data.copyOfRange(4, 36)
            val clientEd25519Bytes = data.copyOfRange(36, 68)

            return doM1(clientX25519Bytes, clientEd25519Bytes)
        }

        // Might be TLV8 encoded
        return handleM1Tlv(data)
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

            if (clientEd25519Bytes != null) {
                Log.d(TAG, "M1: client Ed25519 pubkey: ${clientEd25519Bytes.size} bytes")
            }

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
                Log.w(TAG, "M3: Decrypt failed", e)
                // Don't silently accept — this means keys don't match
                return ByteArray(0)
            }

            Log.d(TAG, "M3: Decrypted ${decrypted.size} bytes")

            // Parse the decrypted TLV to verify signature
            val tlvs = Tlv8.decode(decrypted)
            val clientEd25519PubBytes = tlvs[Tlv8.PUBLIC_KEY]
            val clientSignature = tlvs[Tlv8.SIGNATURE]

            if (clientEd25519PubBytes != null && clientSignature != null) {
                Log.d(TAG, "M3: Got client Ed25519 pubkey (${clientEd25519PubBytes.size} bytes) and signature (${clientSignature.size} bytes)")
                // Could verify signature here if needed
            }

            state = 2
            Log.d(TAG, "Pair-verify completed successfully")
            return ByteArray(0)

        } catch (e: Exception) {
            Log.e(TAG, "M3 error", e)
            return ByteArray(0)
        }
    }

    fun isComplete(): Boolean = state >= 2
    fun getSessionKey(): ByteArray? = sessionKey
    fun getSharedSecret(): ByteArray? = sharedSecret
}
