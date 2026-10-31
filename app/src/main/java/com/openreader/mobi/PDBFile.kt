package com.openreader.mobi

import android.os.ParcelFileDescriptor
import com.openreader.mobi.utils.readString
import com.openreader.mobi.utils.readUInt16
import com.openreader.mobi.utils.readUInt32
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class PDBFile(private val pfd: ParcelFileDescriptor) {
    private val fc: FileChannel = FileInputStream(pfd.fileDescriptor).channel
    private val offsets: IntArray
    val name: String
    val type: String
    val creator: String
    val recordCount: Int

    init {
        val fileSize = fc.size()
        require(fileSize >= 78L) { "Invalid PDB file: header is truncated" }

        val header = ByteBuffer.allocate(78)
        readFully(header, 0L)
        name = header.readString(0, 32)
        type = header.readString(60, 4)
        creator = header.readString(64, 4)
        recordCount = header.readUInt16(76)

        require(recordCount > 0) { "Invalid PDB file: no records" }
        val tableEnd = 78L + recordCount.toLong() * 8L
        require(tableEnd <= fileSize) { "Invalid PDB file: record table is truncated" }

        val table = ByteBuffer.allocate(recordCount * 8)
        readFully(table, 78L)
        offsets = IntArray(recordCount) { table.readUInt32(it * 8) }

        var previous = tableEnd
        for (offset in offsets) {
            val current = offset.toLong()
            require(current >= tableEnd && current >= previous && current <= fileSize) {
                "Invalid PDB file: record offsets are out of bounds"
            }
            previous = current
        }
    }

    fun getRecordData(index: Int): ByteBuffer {
        if (index < 0 || index >= recordCount) {
            throw IndexOutOfBoundsException("Record index out of bounds")
        }
        val start = offsets[index].toLong()
        val end = offsets.getOrElse(index + 1) { fc.size().toInt() }.toLong()
        require(end >= start && end <= fc.size()) { "Invalid PDB file: record length is out of bounds" }
        val length = end - start
        require(length <= Int.MAX_VALUE) { "Invalid PDB file: record is too large" }
        val buffer = ByteBuffer.allocate(length.toInt())
        readFully(buffer, start)
        return buffer
    }

    private fun readFully(buffer: ByteBuffer, position: Long) {
        var current = position
        while (buffer.hasRemaining()) {
            val count = fc.read(buffer, current)
            if (count <= 0) throw IllegalArgumentException("Invalid PDB file: unexpected end of file")
            current += count
        }
        buffer.flip()
    }

    fun close() {
        fc.close()
        pfd.close()
    }
}
