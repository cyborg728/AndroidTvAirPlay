package com.airplay.receiver.crypto

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 AEAD cipher used by AirPlay 2 pairing protocol.
 * Uses javax.crypto (Android 9+) with BouncyCastle JCE provider fallback.
 */
object ChaCha20Poly1305 {

    private const val TAG = "ChaCha20Poly1305"

    // Initialize BC provider once
    init {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add BouncyCastle provider", e)
        }
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plaintext: ByteArray): ByteArray {
        val cipher = getCipher()
        val keySpec = SecretKeySpec(key, "ChaCha20")
        val ivSpec = IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, ciphertext: ByteArray): ByteArray {
        val cipher = getCipher()
        val keySpec = SecretKeySpec(key, "ChaCha20")
        val ivSpec = IvParameterSpec(nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    private fun getCipher(): Cipher {
        return try {
            Cipher.getInstance("ChaCha20-Poly1305")
        } catch (e: Exception) {
            // Fallback to BouncyCastle provider
            Log.d(TAG, "Using BouncyCastle provider for ChaCha20-Poly1305")
            Cipher.getInstance("ChaCha20-Poly1305", "BC")
        }
    }
}
