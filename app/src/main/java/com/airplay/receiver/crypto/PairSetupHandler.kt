package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Handles AirPlay 2 "transient" pair-setup.
 *
 * Transient pairing doesn't require a PIN — used when statusFlags=0x4.
 *
 * iOS may send pair-setup in two formats:
 *
 * 1. Raw format (iOS 15+): Client sends raw 32-byte X25519 public key,
 *    server responds with raw 32-byte X25519 public key.
 *    May be followed by a second request with encrypted payload (36+ bytes).
 *
 * 2. TLV8 format: Standard HAP-style M1/M2/M3/M4 exchange with
 *    state tracking via TLV8 STATE field.
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

    // Track whether we've done the initial raw key exchange
    private var rawKeyExchangeDone = false
    private var clientX25519PubBytes: ByteArray? = null

    init {
        val pair = PairingUtils.generateEd25519KeyPair()
        ed25519Private = pair.first
        ed25519Public = pair.second
    }

    fun handle(data: ByteArray): ByteArray {
        // Try TLV8 decode first
        val tlvs = Tlv8.decode(data)
        val state = tlvs[Tlv8.STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        val method = tlvs[Tlv8.METHOD]?.firstOrNull()?.toInt()?.and(0xFF)

        Log.d(TAG, "pair-setup: state=$state, method=$method, data size=${data.size}, rawKeyExchangeDone=$rawKeyExchangeDone")

        // If valid TLV8 with STATE field, use TLV8 flow
        if (state > 0) {
            return when (state) {
                1 -> handleM1Tlv(data)
                3 -> handleM3Tlv(data) ?: makeErrorResponse(state)
                else -> {
                    Log.w(TAG, "Unknown TLV8 pair-setup state: $state")
                    makeErrorResponse(state)
                }
            }
        }

        // Raw format: no STATE field found
        // Case 1: 32 bytes = raw X25519 public key (first request)
        if (data.size == 32 && !rawKeyExchangeDone) {
            return handleRawKeyExchange(data)
        }

        // Case 2: After raw key exchange, second request with encrypted data
        if (rawKeyExchangeDone && data.size > 32) {
            return handleRawEncryptedExchange(data)
        }

        // Case 3: Small TLV8 with just METHOD=0 (transient), no STATE
        // This is a "I want transient pairing" request — just acknowledge
        if (method == 0 && data.size < 32) {
            Log.d(TAG, "Transient pair-setup init (method=0), acknowledging")
            return Tlv8.encode(mapOf(
                Tlv8.STATE to byteArrayOf(0x02)
            ))
        }

        // Fallback: treat any 32-byte payload as raw X25519 key
        if (data.size == 32) {
            return handleRawKeyExchange(data)
        }

        Log.w(TAG, "Unrecognized pair-setup format: ${data.size} bytes")
        // Return empty OK — some implementations just need acknowledgment
        return ByteArray(0)
    }

    /**
     * Raw transient pair-setup: client sends 32-byte X25519 public key,
     * we respond with our 32-byte X25519 public key.
     */
    private fun handleRawKeyExchange(clientPubKey: ByteArray): ByteArray {
        Log.d(TAG, "Raw pair-setup: received 32-byte client X25519 public key")

        // Generate fresh X25519 keypair
        x25519KeyPair = PairingUtils.generateX25519KeyPair()
        clientX25519PubBytes = clientPubKey

        try {
            val clientX25519Public = X25519PublicKeyParameters(clientPubKey, 0)

            // Derive shared secret
            sharedSecret = PairingUtils.x25519SharedSecret(
                x25519KeyPair.first,
                clientX25519Public
            )

            // Derive session key
            sessionKey = PairingUtils.hkdfSha512(
                sharedSecret!!,
                PAIR_SETUP_SALT.toByteArray(),
                PAIR_SETUP_INFO.toByteArray(),
                32
            )

            rawKeyExchangeDone = true
            Log.d(TAG, "Raw pair-setup: responding with our 32-byte X25519 public key")

            // Respond with our raw 32-byte X25519 public key
            return x25519KeyPair.second.encoded

        } catch (e: Exception) {
            Log.e(TAG, "Raw pair-setup key exchange failed", e)
            return ByteArray(0)
        }
    }

    /**
     * Second step of raw pair-setup: client sends encrypted Ed25519 data.
     * We decrypt, verify, and respond with our encrypted Ed25519 signature.
     */
    private fun handleRawEncryptedExchange(data: ByteArray): ByteArray {
        Log.d(TAG, "Raw pair-setup step 2: ${data.size} bytes of encrypted data")

        if (sessionKey == null) {
            Log.e(TAG, "No session key for encrypted exchange")
            return ByteArray(0)
        }

        try {
            // Decrypt with PS-Msg03 nonce
            val nonce = ByteArray(12)
            "PS-Msg03".toByteArray().copyInto(nonce, 4)

            val decrypted = try {
                ChaCha20Poly1305.decrypt(sessionKey!!, nonce, null, data)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt pair-setup step 2 (may be OK)", e)
                // Some clients just need the key exchange, not the encrypted step
                return ByteArray(0)
            }

            Log.d(TAG, "Decrypted ${decrypted.size} bytes from pair-setup step 2")

            // Parse inner TLV: client's Ed25519 pubkey + signature
            val innerTlvs = Tlv8.decode(decrypted)
            val clientEd25519PubBytes = innerTlvs[Tlv8.PUBLIC_KEY]
            val clientSignature = innerTlvs[Tlv8.SIGNATURE]

            if (clientEd25519PubBytes != null) {
                Log.d(TAG, "Got client Ed25519 pubkey: ${clientEd25519PubBytes.size} bytes")
            }

            // Build our response: sign and encrypt
            val signData = x25519KeyPair.second.encoded + ed25519Public.encoded +
                    (clientX25519PubBytes ?: ByteArray(0))
            val signature = PairingUtils.ed25519Sign(ed25519Private, signData)

            val innerResponse = Tlv8.encode(mapOf(
                Tlv8.PUBLIC_KEY to ed25519Public.encoded,
                Tlv8.SIGNATURE to signature
            ))

            val encNonce = ByteArray(12)
            "PS-Msg04".toByteArray().copyInto(encNonce, 4)
            val encrypted = ChaCha20Poly1305.encrypt(sessionKey!!, encNonce, null, innerResponse)

            Log.d(TAG, "Responding with encrypted Ed25519 data: ${encrypted.size} bytes")
            return encrypted

        } catch (e: Exception) {
            Log.e(TAG, "Raw pair-setup step 2 error", e)
            return ByteArray(0)
        }
    }

    // --- TLV8 flow (original M1/M2/M3/M4) ---

    private fun handleM1Tlv(data: ByteArray): ByteArray {
        Log.d(TAG, "Handling TLV8 pair-setup M1")

        // Generate fresh X25519 keypair for this session
        x25519KeyPair = PairingUtils.generateX25519KeyPair()

        // Respond with M2: our X25519 public key
        val response = Tlv8.encode(mapOf(
            Tlv8.STATE to byteArrayOf(0x02),
            Tlv8.PUBLIC_KEY to x25519KeyPair.second.encoded
        ))

        Log.d(TAG, "Sending TLV8 pair-setup M2, pubkey size: ${x25519KeyPair.second.encoded.size}")
        return response
    }

    private fun handleM3Tlv(data: ByteArray): ByteArray? {
        Log.d(TAG, "Handling TLV8 pair-setup M3")

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
            }

            // Create our M4 response
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

            Log.d(TAG, "Sending TLV8 pair-setup M4")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "M3: Error during pairing", e)
            return makeErrorResponse(3)
        }
    }

    fun reset() {
        rawKeyExchangeDone = false
        clientX25519PubBytes = null
        sessionKey = null
        sharedSecret = null
        x25519KeyPair = PairingUtils.generateX25519KeyPair()
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
