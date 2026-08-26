package com.sahidcode404.camx.core.camera.cache

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal object CacheBounds {
    const val HOT_PAYLOAD_BYTES = 32 * 1024
    const val TOPOLOGY_PAYLOAD_BYTES = 1024 * 1024
    const val ENVELOPE_BYTES = 20
    const val HOT_FILE_BYTES = HOT_PAYLOAD_BYTES + ENVELOPE_BYTES
    const val TOPOLOGY_FILE_BYTES = TOPOLOGY_PAYLOAD_BYTES + ENVELOPE_BYTES

    const val ENVIRONMENT_BYTES = 1024
    const val IDENTIFIER_BYTES = 512
    const val SIGNATURE_BYTES = 1024

    const val ROUTES = 128
    const val CANONICAL_LENSES = 64
    const val PROFILES_PER_LENS = 32
    const val TOTAL_PROFILES = 128
    const val EVIDENCE = 256
    const val ROUTE_SOURCES = 4
    const val PREVIEW_STREAMS = 128
    const val FPS_RANGES = 64
    const val RAW_SIZES = 64
    const val FOCAL_LENGTHS = 32
    const val APERTURES = 32
}

internal object CacheEnvelope {
    const val HOT_MAGIC = 0x434D5848 // CMXH
    const val TOPOLOGY_MAGIC = 0x434D5854 // CMXT
    const val FORMAT_VERSION = 1

    sealed interface Decoded {
        data class Payload(val bytes: ByteArray) : Decoded
        data object Unsupported : Decoded
        data class Corrupt(val reason: String) : Decoded
    }

    fun encode(magic: Int, schema: Int, maximumPayloadBytes: Int, payload: ByteArray): ByteArray {
        require(schema > 0) { "Cache schema must be positive" }
        require(payload.size <= maximumPayloadBytes) { "Cache payload exceeds its bounded format" }
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val output = ByteArrayOutputStream(CacheBounds.ENVELOPE_BYTES + payload.size)
        DataOutputStream(output).use { data ->
            data.writeInt(magic)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(schema)
            data.writeInt(payload.size)
            data.writeInt(crc)
            data.write(payload)
        }
        return output.toByteArray()
    }

    fun decode(
        bytes: ByteArray,
        expectedMagic: Int,
        expectedSchema: Int,
        maximumPayloadBytes: Int,
    ): Decoded {
        if (bytes.size < CacheBounds.ENVELOPE_BYTES) return Decoded.Corrupt("Cache envelope is truncated")
        val header = ByteBuffer.wrap(bytes, 0, CacheBounds.ENVELOPE_BYTES)
        val magic = header.int
        val format = header.int
        val schema = header.int
        val payloadLength = header.int
        val expectedCrc = header.int
        if (magic != expectedMagic) return Decoded.Corrupt("Cache magic mismatch")
        if (payloadLength < 0 || payloadLength > maximumPayloadBytes) {
            return Decoded.Corrupt("Cache payload length is outside bounds")
        }
        val total = CacheBounds.ENVELOPE_BYTES.toLong() + payloadLength.toLong()
        if (total != bytes.size.toLong()) {
            return Decoded.Corrupt(if (total > bytes.size) "Cache payload is truncated" else "Cache has trailing data")
        }
        val payload = bytes.copyOfRange(CacheBounds.ENVELOPE_BYTES, bytes.size)
        val actualCrc = CRC32().apply { update(payload) }.value.toInt()
        if (actualCrc != expectedCrc) return Decoded.Corrupt("Cache checksum mismatch")
        if (format != FORMAT_VERSION || schema != expectedSchema) return Decoded.Unsupported
        return Decoded.Payload(payload)
    }
}

internal class CacheBinaryWriter {
    private val output = ByteArrayOutputStream()
    private val data = DataOutputStream(output)

    fun writeByte(value: Int) = data.writeByte(value)
    fun writeInt(value: Int) = data.writeInt(value)
    fun writeLong(value: Long) = data.writeLong(value)
    fun writeFloat(value: Float) = data.writeInt(value.toBits())

    fun writeBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    fun writeString(value: String, maximumBytes: Int, label: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.isNotEmpty()) { "$label cannot be empty" }
        require(encoded.size <= maximumBytes) { "$label exceeds cache bound" }
        writeInt(encoded.size)
        data.write(encoded)
    }

    fun <T> writeNullable(value: T?, block: (T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) block(value)
    }

    fun toByteArray(): ByteArray {
        data.flush()
        return output.toByteArray()
    }
}

internal class CacheBinaryReader(private val bytes: ByteArray) {
    private var position = 0

    val remaining: Int get() = bytes.size - position

    fun readByte(label: String): Int {
        requireRemaining(1, label)
        return bytes[position++].toInt() and 0xff
    }

    fun readInt(label: String): Int {
        requireRemaining(4, label)
        val value = ByteBuffer.wrap(bytes, position, 4).int
        position += 4
        return value
    }

    fun readLong(label: String): Long {
        requireRemaining(8, label)
        val value = ByteBuffer.wrap(bytes, position, 8).long
        position += 8
        return value
    }

    fun readFloat(label: String): Float = Float.fromBits(readInt(label))

    fun readBoolean(label: String): Boolean = when (val value = readByte(label)) {
        0 -> false
        1 -> true
        else -> throw CacheFormatException("$label has invalid boolean value $value")
    }

    fun readString(maximumBytes: Int, label: String): String {
        val length = readInt("$label length")
        if (length <= 0 || length > maximumBytes || length > remaining) {
            throw CacheFormatException("$label length is outside bounds")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = try {
            decoder.decode(ByteBuffer.wrap(bytes, position, length)).toString()
        } catch (_: Exception) {
            throw CacheFormatException("$label is not valid UTF-8")
        }
        position += length
        if (value.isBlank()) throw CacheFormatException("$label cannot be blank")
        return value
    }

    fun readCount(maximum: Int, label: String): Int {
        val count = readInt(label)
        if (count < 0 || count > maximum) throw CacheFormatException("$label is outside bounds")
        return count
    }

    fun <T> readNullable(label: String, block: () -> T): T? = if (readBoolean("$label present")) block() else null

    fun <E : Enum<E>> readEnum(values: Array<E>, label: String): E {
        val ordinal = readInt(label)
        if (ordinal !in values.indices) throw CacheFormatException("$label has invalid enum value $ordinal")
        return values[ordinal]
    }

    fun requireExhausted() {
        if (remaining != 0) throw CacheFormatException("Cache payload has trailing data")
    }

    private fun requireRemaining(count: Int, label: String) {
        if (count < 0 || count > remaining) throw CacheFormatException("$label is truncated")
    }
}

internal class CacheFormatException(message: String) : Exception(message)
