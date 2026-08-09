package com.photonspark.pocketexit.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DatagramCodecTest {
    @Test
    fun decoderHandlesFragmentedAndCoalescedFrames() {
        val first = DatagramCodec.frame("first".toByteArray())
        val second = DatagramCodec.frame("second".toByteArray())
        val combined = first + second
        val decoder = DatagramCodec.Decoder()

        assertEquals(0, decoder.feed(combined.copyOfRange(0, 3)).size)
        val decoded = decoder.feed(combined.copyOfRange(3, combined.size))

        assertEquals(2, decoded.size)
        assertArrayEquals("first".toByteArray(), decoded[0])
        assertArrayEquals("second".toByteArray(), decoded[1])
        assertFalse(decoder.hasPartialFrame())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedDatagram() {
        DatagramCodec.frame(ByteArray(DatagramCodec.MAX_DATAGRAM_SIZE + 1))
    }
}
