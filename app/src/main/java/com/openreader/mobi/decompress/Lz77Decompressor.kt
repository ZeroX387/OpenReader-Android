package com.openreader.mobi.decompress

import androidx.core.util.Pools.SynchronizedPool

class Lz77Decompressor(private val textRecordSize: Int) : Decompressor {

    val pool = SynchronizedPool<ByteArray>(2)

    override fun decompress(data: ByteArray): ByteArray {
        require(textRecordSize > 0) { "Invalid MOBI text record size" }
        val out = pool.acquire() ?: ByteArray(textRecordSize)
        var i = 0
        var o = 0

        try {
            while (i < data.size) {
                var c = data[i++].toInt() and 0x00FF
                if (c in 0x01..0x08) {
                    val copyCount = minOf(c, data.size - i)
                    check(copyCount <= out.size - o) { "Invalid compressed MOBI text record: output is too large" }
                    var j = 0
                    while (j < copyCount) {
                        out[o++] = data[i + j]
                        j++
                    }
                    i += c
                } else if (c <= 0x7f) {
                    check(o < out.size) { "Invalid compressed MOBI text record: output is too large" }
                    out[o++] = c.toByte()
                } else if (c >= 0xC0) {
                    check(out.size - o >= 2) { "Invalid compressed MOBI text record: output is too large" }
                    out[o++] = ' '.code.toByte()
                    out[o++] = (c xor 0x80).toByte()
                } else if (i < data.size) {
                    c = c shl 8 or (data[i++].toInt() and 0xFF)
                    val length = (c and 0x0007) + 3
                    val location = (c shr 3) and 0x7FF

                    if (location in 1..o) {
                        check(length <= out.size - o) { "Invalid compressed MOBI text record: output is too large" }
                        repeat(length) {
                            val idx = o - location
                            out[o++] = out[idx]
                        }
                    }
                }
            }

            return out.copyOf(o)
        } finally {
            pool.release(out)
        }
    }
}
