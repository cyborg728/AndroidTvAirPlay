package com.airplay.receiver.server

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Minimal binary plist decoder for AirPlay 2 request parsing.
 * iOS sends binary plist bodies for /play, SETUP, /command, etc.
 *
 * Supports: dict, string, integer, real, boolean, data, array, uid
 */
object BPlistDecoder {

    private const val TAG = "BPlistDecoder"

    fun decode(data: ByteArray): Any? {
        if (data.size < 40) return null // minimum: 8 header + 32 trailer
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        if (!magic.startsWith("bplist")) {
            Log.w(TAG, "Not a binary plist: $magic")
            return null
        }

        try {
            // Read trailer (last 32 bytes)
            val trailerOffset = data.size - 32
            val trailer = ByteBuffer.wrap(data, trailerOffset, 32).order(ByteOrder.BIG_ENDIAN)

            // trailer[0..5] = unused
            // trailer[6] = offset size
            // trailer[7] = object ref size
            // trailer[8..15] = number of objects
            // trailer[16..23] = top object index
            // trailer[24..31] = offset table offset
            val offsetSize = data[trailerOffset + 6].toInt() and 0xFF
            val objectRefSize = data[trailerOffset + 7].toInt() and 0xFF
            val numObjects = ByteBuffer.wrap(data, trailerOffset + 8, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            val topObjectIndex = ByteBuffer.wrap(data, trailerOffset + 16, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
            val offsetTableOffset = ByteBuffer.wrap(data, trailerOffset + 24, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()

            if (numObjects <= 0 || offsetSize <= 0 || objectRefSize <= 0) return null

            // Read offset table
            val offsets = IntArray(numObjects)
            for (i in 0 until numObjects) {
                offsets[i] = readSizedInt(data, offsetTableOffset + i * offsetSize, offsetSize)
            }

            // Parse objects
            val objects = arrayOfNulls<Any>(numObjects)
            for (i in 0 until numObjects) {
                objects[i] = parseObject(data, offsets[i], offsets, objectRefSize, objects, numObjects)
            }

            // Resolve references for containers (second pass)
            for (i in 0 until numObjects) {
                val obj = objects[i]
                if (obj is UnresolvedDict) {
                    val map = linkedMapOf<String, Any?>()
                    for (j in obj.keyRefs.indices) {
                        val keyObj = objects.getOrNull(obj.keyRefs[j])
                        val valObj = objects.getOrNull(obj.valRefs[j])
                        val key = keyObj?.toString() ?: "key_${obj.keyRefs[j]}"
                        map[key] = valObj
                    }
                    objects[i] = map
                } else if (obj is UnresolvedArray) {
                    val list = mutableListOf<Any?>()
                    for (ref in obj.refs) {
                        list.add(objects.getOrNull(ref))
                    }
                    objects[i] = list
                }
            }

            return objects.getOrNull(topObjectIndex)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode binary plist", e)
            return null
        }
    }

    /**
     * Convenience: decode and return as Map<String, Any?>, or null.
     */
    fun decodeAsMap(data: ByteArray): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return decode(data) as? Map<String, Any?>
    }

    private data class UnresolvedDict(val keyRefs: IntArray, val valRefs: IntArray)
    private data class UnresolvedArray(val refs: IntArray)

    private fun parseObject(
        data: ByteArray, offset: Int,
        offsets: IntArray, objectRefSize: Int,
        objects: Array<Any?>, numObjects: Int
    ): Any? {
        val marker = data[offset].toInt() and 0xFF
        val objectType = (marker shr 4) and 0x0F
        var objectInfo = marker and 0x0F

        return when (objectType) {
            0x0 -> { // singleton: null, bool, fill
                when (objectInfo) {
                    0x0 -> null        // null
                    0x8 -> false       // false
                    0x9 -> true        // true
                    0xF -> null        // fill byte
                    else -> null
                }
            }
            0x1 -> { // integer
                val byteCount = 1 shl objectInfo
                readSignedInt(data, offset + 1, byteCount)
            }
            0x2 -> { // real
                val byteCount = 1 shl objectInfo
                when (byteCount) {
                    4 -> ByteBuffer.wrap(data, offset + 1, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
                    8 -> ByteBuffer.wrap(data, offset + 1, 8).order(ByteOrder.BIG_ENDIAN).double
                    else -> 0.0
                }
            }
            0x3 -> { // date (CoreFoundation absolute time)
                ByteBuffer.wrap(data, offset + 1, 8).order(ByteOrder.BIG_ENDIAN).double
            }
            0x4 -> { // data (binary)
                val (size, dataStart) = getSizeAndStart(data, offset, objectInfo)
                data.copyOfRange(dataStart, dataStart + size)
            }
            0x5 -> { // ASCII string
                val (size, dataStart) = getSizeAndStart(data, offset, objectInfo)
                String(data, dataStart, size, Charsets.US_ASCII)
            }
            0x6 -> { // Unicode string (UTF-16BE)
                val (size, dataStart) = getSizeAndStart(data, offset, objectInfo)
                String(data, dataStart, size * 2, Charsets.UTF_16BE)
            }
            0x8 -> { // UID
                val size = objectInfo + 1
                readSizedInt(data, offset + 1, size).toLong()
            }
            0xA -> { // array
                val (size, dataStart) = getSizeAndStart(data, offset, objectInfo)
                val refs = IntArray(size)
                for (i in 0 until size) {
                    refs[i] = readSizedInt(data, dataStart + i * objectRefSize, objectRefSize)
                }
                UnresolvedArray(refs)
            }
            0xD -> { // dict
                val (size, dataStart) = getSizeAndStart(data, offset, objectInfo)
                val keyRefs = IntArray(size)
                val valRefs = IntArray(size)
                for (i in 0 until size) {
                    keyRefs[i] = readSizedInt(data, dataStart + i * objectRefSize, objectRefSize)
                }
                for (i in 0 until size) {
                    valRefs[i] = readSizedInt(data, dataStart + size * objectRefSize + i * objectRefSize, objectRefSize)
                }
                UnresolvedDict(keyRefs, valRefs)
            }
            else -> {
                Log.w(TAG, "Unknown plist object type: $objectType at offset $offset")
                null
            }
        }
    }

    private fun getSizeAndStart(data: ByteArray, offset: Int, objectInfo: Int): Pair<Int, Int> {
        return if (objectInfo < 0x0F) {
            Pair(objectInfo, offset + 1)
        } else {
            // Size is in the next object (an integer)
            val sizeMarker = data[offset + 1].toInt() and 0xFF
            val sizeByteCount = 1 shl (sizeMarker and 0x0F)
            val size = readSizedInt(data, offset + 2, sizeByteCount)
            Pair(size, offset + 2 + sizeByteCount)
        }
    }

    private fun readSizedInt(data: ByteArray, offset: Int, size: Int): Int {
        var result = 0
        for (i in 0 until size) {
            result = (result shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun readSignedInt(data: ByteArray, offset: Int, size: Int): Long {
        return when (size) {
            1 -> (data[offset].toInt() and 0xFF).toLong()
            2 -> ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toLong()
            4 -> ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong()
            8 -> ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).long
            else -> {
                var result = 0L
                for (i in 0 until size) {
                    result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
                }
                result
            }
        }
    }
}
