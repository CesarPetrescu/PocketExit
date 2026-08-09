package com.photonspark.pocketexit.proxy

object DatagramCodec {
    const val MAX_DATAGRAM_SIZE = 65_507

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_DATAGRAM_SIZE) { "Datagram is too large" }
        return ByteArray(payload.size + 2).also { frame ->
            frame[0] = (payload.size ushr 8).toByte()
            frame[1] = payload.size.toByte()
            payload.copyInto(frame, 2)
        }
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            if (chunk.isEmpty()) return emptyList()
            val combined = ByteArray(pending.size + chunk.size)
            pending.copyInto(combined)
            chunk.copyInto(combined, pending.size)

            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (combined.size - offset >= 2) {
                val length = ((combined[offset].toInt() and 0xff) shl 8) or
                    (combined[offset + 1].toInt() and 0xff)
                require(length <= MAX_DATAGRAM_SIZE) { "Invalid datagram length" }
                if (combined.size - offset - 2 < length) break
                result += combined.copyOfRange(offset + 2, offset + 2 + length)
                offset += 2 + length
            }
            pending = combined.copyOfRange(offset, combined.size)
            require(pending.size <= MAX_DATAGRAM_SIZE + 1) { "Datagram stream buffer overflow" }
            return result
        }

        fun hasPartialFrame(): Boolean = pending.isNotEmpty()
    }
}
