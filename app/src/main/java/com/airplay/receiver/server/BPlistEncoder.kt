package com.airplay.receiver.server

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary plist encoder for AirPlay 2 responses.
 * iOS expects binary plist format for /info and other endpoints.
 *
 * Supports: dict, string, integer, real, boolean, data, array
 */
object BPlistEncoder {

    private const val TAG = "BPlistEncoder"

    fun encode(obj: Any?): ByteArray {
        val objects = mutableListOf<Any?>()
        val offsets = mutableListOf<Int>()

        // Flatten all objects
        flattenObject(obj, objects)

        val body = ByteArrayOutputStream()

        // Write header
        body.write("bplist00".toByteArray())

        // Write each object and record offsets
        for (o in objects) {
            offsets.add(body.size())
            writeObject(o, objects, body)
        }

        val offsetTableOffset = body.size()
        val numObjects = objects.size

        // Determine offset size
        val offsetSize = when {
            offsetTableOffset <= 0xFF -> 1
            offsetTableOffset <= 0xFFFF -> 2
            else -> 4
        }

        // Write offset table
        for (offset in offsets) {
            when (offsetSize) {
                1 -> body.write(offset and 0xFF)
                2 -> {
                    body.write((offset shr 8) and 0xFF)
                    body.write(offset and 0xFF)
                }
                4 -> {
                    body.write((offset shr 24) and 0xFF)
                    body.write((offset shr 16) and 0xFF)
                    body.write((offset shr 8) and 0xFF)
                    body.write(offset and 0xFF)
                }
            }
        }

        // Determine objectRef size
        val objectRefSize = when {
            numObjects <= 0xFF -> 1
            numObjects <= 0xFFFF -> 2
            else -> 4
        }

        // Write trailer (32 bytes)
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = objectRefSize.toByte()
        // Number of objects (8 bytes big-endian)
        val numObjBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(numObjects.toLong()).array()
        System.arraycopy(numObjBytes, 0, trailer, 8, 8)
        // Top object (8 bytes) = 0
        // Offset table offset (8 bytes big-endian)
        val offsetBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offsetTableOffset.toLong()).array()
        System.arraycopy(offsetBytes, 0, trailer, 24, 8)

        body.write(trailer)

        return body.toByteArray()
    }

    private fun flattenObject(obj: Any?, objects: MutableList<Any?>): Int {
        val index = objects.size
        objects.add(obj)

        when (obj) {
            is Map<*, *> -> {
                for ((key, value) in obj) {
                    flattenObject(key, objects)
                    flattenObject(value, objects)
                }
            }
            is List<*> -> {
                for (item in obj) {
                    flattenObject(item, objects)
                }
            }
        }

        return index
    }

    private fun writeObject(obj: Any?, objects: List<Any?>, out: ByteArrayOutputStream) {
        when (obj) {
            null -> {
                out.write(0x00) // null
            }
            is Boolean -> {
                out.write(if (obj) 0x09 else 0x08)
            }
            is Int -> writeInt(obj.toLong(), out)
            is Long -> writeInt(obj, out)
            is Double -> {
                out.write(0x23) // real, 8 bytes
                val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(obj).array()
                out.write(buf)
            }
            is Float -> {
                out.write(0x22) // real, 4 bytes
                val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(obj).array()
                out.write(buf)
            }
            is String -> {
                val bytes = obj.toByteArray(Charsets.UTF_8)
                writeTypeAndSize(0x50, bytes.size, out) // ASCII string
                out.write(bytes)
            }
            is ByteArray -> {
                writeTypeAndSize(0x40, obj.size, out) // data
                out.write(obj)
            }
            is Map<*, *> -> {
                val entries = obj.entries.toList()
                writeTypeAndSize(0xD0, entries.size, out) // dict

                val objectRefSize = if (objects.size <= 0xFF) 1 else 2

                // Write key references
                for ((key, _) in entries) {
                    val keyIdx = objects.indexOf(key)
                    writeRef(keyIdx, objectRefSize, out)
                }
                // Write value references
                for ((_, value) in entries) {
                    val valIdx = objects.indexOf(value)
                    writeRef(valIdx, objectRefSize, out)
                }
            }
            is List<*> -> {
                writeTypeAndSize(0xA0, obj.size, out) // array

                val objectRefSize = if (objects.size <= 0xFF) 1 else 2

                for (item in obj) {
                    val idx = objects.indexOf(item)
                    writeRef(idx, objectRefSize, out)
                }
            }
        }
    }

    private fun writeInt(value: Long, out: ByteArrayOutputStream) {
        when {
            value in 0..0xFF -> {
                out.write(0x10) // 1-byte int
                out.write((value and 0xFF).toInt())
            }
            value in 0..0xFFFF -> {
                out.write(0x11) // 2-byte int
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            value in 0..0xFFFFFFFFL -> {
                out.write(0x12) // 4-byte int
                val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
                out.write(buf)
            }
            else -> {
                out.write(0x13) // 8-byte int
                val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
                out.write(buf)
            }
        }
    }

    private fun writeTypeAndSize(typeNibble: Int, size: Int, out: ByteArrayOutputStream) {
        if (size < 15) {
            out.write(typeNibble or size)
        } else {
            out.write(typeNibble or 0x0F)
            // Write size as int
            writeInt(size.toLong(), out)
        }
    }

    private fun writeRef(index: Int, refSize: Int, out: ByteArrayOutputStream) {
        when (refSize) {
            1 -> out.write(index and 0xFF)
            2 -> {
                out.write((index shr 8) and 0xFF)
                out.write(index and 0xFF)
            }
        }
    }
}
