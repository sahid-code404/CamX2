package com.sahidcode404.camx.core.rawvideo.codec

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawVideoCodecFuzzTest {
    @Test
    fun deterministicCorpusRoundTripsAcrossPackedAndRiceCodecs() {
        val random = Random(0x2b404)
        repeat(200) { caseIndex ->
            val size = 2 + random.nextInt(1, 1024) * 2
            val payload = ByteArray(size)
            when (caseIndex % 4) {
                0 -> payload.fill((caseIndex and 0xff).toByte())
                1 -> payload.indices.forEach { payload[it] = (it and 0xff).toByte() }
                2 -> payload.indices.forEach { payload[it] = ((it / 8) and 0xff).toByte() }
                else -> random.nextBytes(payload)
            }
            val frame = m2bFrame(payload)
            listOf(PackedNoneCodec, RiceDeltaByteCodec).forEach { codec ->
                val decoded = codec.decode(codec.encode(frame), frame.representation)
                assertArrayEquals("case=$caseIndex codec=${codec.descriptor.family}", payload, decoded.take())
            }
        }
    }

    @Test
    fun mutatedRicePayloadNeverReturnsWrongRaster() {
        val random = Random(0x51ce)
        repeat(200) { caseIndex ->
            val size = 16 + random.nextInt(1, 512) * 2
            val payload = ByteArray(size).also(random::nextBytes)
            val frame = m2bFrame(payload)
            val packet = RiceDeltaByteCodec.encode(frame).take()
            val mutated = packet.copyPayload()
            val index = random.nextInt(mutated.size)
            val bit = 1 shl random.nextInt(8)
            mutated[index] = (mutated[index].toInt() xor bit).toByte()
            val invalid = EncodedFrameLease.create(packet.header, mutated)

            assertThrows("case=$caseIndex", IllegalArgumentException::class.java) {
                RiceDeltaByteCodec.decode(invalid, frame.representation)
            }
        }
    }
}
