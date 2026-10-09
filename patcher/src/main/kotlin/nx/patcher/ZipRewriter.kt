package nx.patcher

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Copies a ZIP file while replacing, adding and removing entries. Untouched entries are copied
 * byte for byte without decompressing, so memory use stays small even for huge APKs.
 * Alignment is left to the APK signer, which realigns entries anyway.
 */
object ZipRewriter {
    private class CdEntry(
        val name: String,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val externalAttributes: Long,
        val localOffset: Long,
    )

    fun rewrite(input: File, output: File, replace: Map<String, ByteArray>, remove: (String) -> Boolean) {
        RandomAccessFile(input, "r").use { raf ->
            val entries = readCentralDirectory(raf)
            CountingStream(BufferedOutputStream(FileOutputStream(output), 1 shl 16)).use { out ->
                val written = ArrayList<Pair<CdEntry, Long>>()
                val channel = raf.channel
                val outChannel = Channels.newChannel(out)
                for (entry in entries) {
                    if (entry.name in replace || remove(entry.name)) continue
                    val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(header, entry.localOffset)
                    if (header.getInt(0) != 0x04034b50) throw PatchException("Bad ZIP entry ${entry.name}")
                    val dataStart = entry.localOffset + 30 + header.getShort(26).toUShort().toInt() +
                        header.getShort(28).toUShort().toInt()
                    val offset = out.count
                    writeLocalHeader(out, entry)
                    var position = dataStart
                    var remaining = entry.compressedSize
                    while (remaining > 0) {
                        val n = channel.transferTo(position, remaining, outChannel)
                        if (n <= 0) throw PatchException("Unexpected end of ZIP data in ${entry.name}")
                        position += n
                        remaining -= n
                    }
                    written += entry to offset
                }
                for ((name, bytes) in replace) {
                    val crc = CRC32().apply { update(bytes) }.value
                    val compressed = ByteArrayOutputStream(bytes.size / 2).also { buffer ->
                        DeflaterOutputStream(buffer, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { it.write(bytes) }
                    }.toByteArray()
                    val entry = CdEntry(
                        name, versionMadeBy = 20, versionNeeded = 20, flags = 0x800, method = 8,
                        time = 0, date = (1 shl 5) or 1, crc = crc, compressedSize = compressed.size.toLong(),
                        size = bytes.size.toLong(), externalAttributes = 0, localOffset = 0,
                    )
                    val offset = out.count
                    writeLocalHeader(out, entry)
                    out.write(compressed)
                    written += entry to offset
                }
                val cdStart = out.count
                for ((entry, offset) in written) writeCentralHeader(out, entry, offset)
                val cdSize = out.count - cdStart
                val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x06054b50).putShort(0).putShort(0)
                    .putShort(written.size.toShort()).putShort(written.size.toShort())
                    .putInt(cdSize.toInt()).putInt(cdStart.toInt()).putShort(0)
                out.write(eocd.array())
            }
        }
    }

    private fun readCentralDirectory(raf: RandomAccessFile): List<CdEntry> {
        val tailSize = minOf(raf.length(), 65_557L).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(raf.length() - tailSize)
        raf.readFully(tail)
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tailSize - 22 downTo 0).firstOrNull { buf.getInt(it) == 0x06054b50 }
            ?: throw PatchException("Not a ZIP/APK file")
        val count = buf.getShort(eocd + 10).toUShort().toInt()
        val cdSize = buf.getInt(eocd + 12).toUInt().toLong()
        val cdOffset = buf.getInt(eocd + 16).toUInt().toLong()
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        return List(count) {
            if (b.getInt(p) != 0x02014b50) throw PatchException("Corrupt ZIP central directory")
            val nameLength = b.getShort(p + 28).toUShort().toInt()
            val extraLength = b.getShort(p + 30).toUShort().toInt()
            val commentLength = b.getShort(p + 32).toUShort().toInt()
            CdEntry(
                name = String(cd, p + 46, nameLength, Charsets.UTF_8),
                versionMadeBy = b.getShort(p + 4).toUShort().toInt(),
                versionNeeded = b.getShort(p + 6).toUShort().toInt(),
                flags = b.getShort(p + 8).toUShort().toInt(),
                method = b.getShort(p + 10).toUShort().toInt(),
                time = b.getShort(p + 12).toUShort().toInt(),
                date = b.getShort(p + 14).toUShort().toInt(),
                crc = b.getInt(p + 16).toUInt().toLong(),
                compressedSize = b.getInt(p + 20).toUInt().toLong(),
                size = b.getInt(p + 24).toUInt().toLong(),
                externalAttributes = b.getInt(p + 38).toUInt().toLong(),
                localOffset = b.getInt(p + 42).toUInt().toLong(),
            ).also { p += 46 + nameLength + extraLength + commentLength }
        }
    }

    // Sizes are always known, so the data descriptor flag (bit 3) is dropped.
    private fun writeLocalHeader(out: OutputStream, e: CdEntry) {
        val name = e.name.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(30 + name.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x04034b50).putShort(e.versionNeeded.toShort()).putShort((e.flags and 0x08.inv()).toShort())
            .putShort(e.method.toShort()).putShort(e.time.toShort()).putShort(e.date.toShort())
            .putInt(e.crc.toInt()).putInt(e.compressedSize.toInt()).putInt(e.size.toInt())
            .putShort(name.size.toShort()).putShort(0).put(name)
        out.write(b.array())
    }

    private fun writeCentralHeader(out: OutputStream, e: CdEntry, offset: Long) {
        val name = e.name.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(46 + name.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x02014b50).putShort(e.versionMadeBy.toShort()).putShort(e.versionNeeded.toShort())
            .putShort((e.flags and 0x08.inv()).toShort()).putShort(e.method.toShort())
            .putShort(e.time.toShort()).putShort(e.date.toShort())
            .putInt(e.crc.toInt()).putInt(e.compressedSize.toInt()).putInt(e.size.toInt())
            .putShort(name.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0)
            .putInt(e.externalAttributes.toInt()).putInt(offset.toInt()).put(name)
        out.write(b.array())
    }

    /** Tracks the output position; channel transfers also write through it. */
    private class CountingStream(private val target: BufferedOutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
        override fun close() = target.close()
    }
}
