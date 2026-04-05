package com.airplay.receiver.crypto

import java.io.ByteArrayOutputStream

/**
 * TLV8 (Type-Length-Value with 8-bit type) encoder/decoder
 * used by Apple's pairing protocol.
 *
 * Each TLV item: [type(1 byte)][length(1 byte)][value(0-255 bytes)]
 * Values longer than 255 bytes are split into consecutive TLVs with the same type.
 */
object Tlv8 {

    fun encode(items: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var offset = 0
            do {
                val chunkLen = minOf(255, value.size - offset)
                out.write(type)
                out.write(chunkLen)
                if (chunkLen > 0) {
                    out.write(value, offset, chunkLen)
                }
                offset += chunkLen
            } while (offset < value.size)

            // Write zero-length TLV if value is empty
            if (value.isEmpty()) {
                // Already written above with chunkLen=0
            }
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2

            if (i + length > data.size) break

            val stream = result.getOrPut(type) { ByteArrayOutputStream() }
            if (length > 0) {
                stream.write(data, i, length)
            }
            i += length
        }
        return result.mapValues { it.value.toByteArray() }
    }

    // TLV types used in pairing
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
}
