package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Handles AirPlay 2 "transient" pair-setup.
 *
 * Transient pairing doesn't require a PIN — used when the device
 * is on the same network. The flow:
 *
 * M1: Client → Server: method=0 (pair-setup), state=1
 * M2: Server → Client: state=2, server X25519 public key
 * M3: Client → Server: state=3, client X25519 pubkey + encrypted Ed25519 pubkey + signature
 * M4: Server → Client: state=4, encrypted Ed25519 signature
 */
class PairSetupHandler {

    companion object {
        private const val TAG = "PairSetup"
        private const val PAIR_SETUP_SALT = "Pair-Setup-Encrypt-Salt"
        private const val PAIR_SETUP_INFO = "Pair-Setup-Encrypt-Info"
    }

    // Long-lived Ed25519 key pair for this server
    private val ed25519Private: Ed25519PrivateKeyParameters
    private val ed25519Public: Ed25519PublicKeyParameters

    // Ephemeral X25519 key pair for this session
    private var x25519KeyPair = PairingUtils.generateX25519KeyPair()
    private var sessionKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null

    init {
        val pair = PairingUtils.generateEd25519KeyPair()
        ed25519Private = pair.first
        ed25519Public = pair.second
    }

    fun handleM1(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling pair-setup M1")

        // Generate fresh X25519 keypair for this session
        x25519KeyPair = PairingUtils.generateX25519KeyPair()

        // Respond with M2: our X25519 public key
        val response = Tlv8.encode(mapOf(
            Tlv8.STATE to byteArrayOf(0x02),
            Tlv8.PUBLIC_KEY to x25519KeyPair.second.encoded
        ))

        Log.d(TAG, "Sending pair-setup M2, pubkey size: ${x25519KeyPair.second.encoded.size}")
        return response
    }

    fun handleM3(data: ByteArray): ByteArray? {
        Log.d(TAG, "Handling pair-setup M3")

        val tlvs = Tlv8.decode(data)
        val clientPublicKeyBytes = tlvs[Tlv8.PUBLIC_KEY] ?: run {
            Log.e(TAG, "M3: missing client public key")
            return makeErrorResponse(3)
        }
        val encryptedData = tlvs[Tlv8.ENCRYPTED_DATA] ?: run {
            Log.e(TAG, "M3: missing encrypted data")
            return makeErrorResponse(3)
        }

        try {
            val clientX25519Public = X25519PublicKeyParameters(clientPublicKeyBytes, 0)

            // Derive shared secret
            sharedSecret = PairingUtils.x25519SharedSecret(
                x25519KeyPair.first,
                clientX25519Public
            )

            // Derive session key using HKDF
            sessionKey = PairingUtils.hkdfSha512(
                sharedSecret!!,
                PAIR_SETUP_SALT.toByteArray(),
                PAIR_SETUP_INFO.toByteArray(),
                32
            )

            // Decrypt the client's encrypted data
            val nonce = ByteArray(12)
            "PS-Msg03".toByteArray().copyInto(nonce, 4)

            val decrypted = try {
                ChaCha20Poly1305.decrypt(sessionKey!!, nonce, null, encryptedData)
            } catch (e: Exception) {
                Log.e(TAG, "M3: Failed to decrypt client data", e)
                return makeErrorResponse(3)
            }

            // Parse decrypted TLV: contains client's Ed25519 public key + signature
            val innerTlvs = Tlv8.decode(decrypted)
            val clientEd25519PubBytes = innerTlvs[Tlv8.PUBLIC_KEY]
            val clientSignature = innerTlvs[Tlv8.SIGNATURE]

            if (clientEd25519PubBytes != null && clientSignature != null) {
                Log.d(TAG, "M3: Got client Ed25519 pubkey (${clientEd25519PubBytes.size} bytes) and signature (${clientSignature.size} bytes)")

                // Verify client signature
                val clientEd25519Public = Ed25519PublicKeyParameters(clientEd25519PubBytes, 0)
                val verifyData = clientPublicKeyBytes + clientEd25519PubBytes + x25519KeyPair.second.encoded
                val verified = PairingUtils.ed25519Verify(clientEd25519Public, verifyData, clientSignature)
                Log.d(TAG, "M3: Client signature verified: $verified")
            }

            // Create our M4 response
            // Sign: our X25519 pubkey + our Ed25519 pubkey + client's X25519 pubkey
            val signData = x25519KeyPair.second.encoded + ed25519Public.encoded + clientPublicKeyBytes
            val signature = PairingUtils.ed25519Sign(ed25519Private, signData)

            val innerResponse = Tlv8.encode(mapOf(
                Tlv8.PUBLIC_KEY to ed25519Public.encoded,
                Tlv8.SIGNATURE to signature
            ))

            // Encrypt with session key
            val encNonce = ByteArray(12)
            "PS-Msg04".toByteArray().copyInto(encNonce, 4)
            val encrypted = ChaCha20Poly1305.encrypt(sessionKey!!, encNonce, null, innerResponse)

            val response = Tlv8.encode(mapOf(
                Tlv8.STATE to byteArrayOf(0x04),
                Tlv8.ENCRYPTED_DATA to encrypted
            ))

            Log.d(TAG, "Sending pair-setup M4")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "M3: Error during pairing", e)
            return makeErrorResponse(3)
        }
    }

    fun handle(data: ByteArray): ByteArray {
        val tlvs = Tlv8.decode(data)
        val state = tlvs[Tlv8.STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0

        Log.d(TAG, "pair-setup state: $state, data size: ${data.size}")

        return when (state) {
            1 -> handleM1(data)
            3 -> handleM3(data) ?: makeErrorResponse(state)
            else -> {
                Log.w(TAG, "Unknown pair-setup state: $state")
                makeErrorResponse(state)
            }
        }
    }

    fun getSessionKey(): ByteArray? = sessionKey
    fun getSharedSecret(): ByteArray? = sharedSecret

    private fun makeErrorResponse(state: Int): ByteArray {
        return Tlv8.encode(mapOf(
            Tlv8.STATE to byteArrayOf((state + 1).toByte()),
            Tlv8.ERROR to byteArrayOf(0x02)
        ))
    }
}
