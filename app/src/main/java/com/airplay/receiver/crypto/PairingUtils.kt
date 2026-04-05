package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jcajce.provider.digest.SHA512
import java.security.SecureRandom

/**
 * Crypto utilities for AirPlay 2 pairing protocol.
 */
object PairingUtils {

    private const val TAG = "PairingUtils"

    fun generateX25519KeyPair(): Pair<X25519PrivateKeyParameters, X25519PublicKeyParameters> {
        val random = SecureRandom()
        val privateKey = X25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey()
        return Pair(privateKey, publicKey)
    }

    fun generateEd25519KeyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val random = SecureRandom()
        val privateKey = Ed25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey()
        return Pair(privateKey, publicKey)
    }

    fun x25519SharedSecret(
        ourPrivate: X25519PrivateKeyParameters,
        theirPublic: X25519PublicKeyParameters
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(ourPrivate)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(theirPublic, secret, 0)
        return secret
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA512.Digest())
        hkdf.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(outputLength)
        hkdf.generateBytes(output, 0, outputLength)
        return output
    }

    fun ed25519Sign(
        privateKey: Ed25519PrivateKeyParameters,
        message: ByteArray
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(
        publicKey: Ed25519PublicKeyParameters,
        message: ByteArray,
        signature: ByteArray
    ): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 verify failed", e)
            false
        }
    }
}
