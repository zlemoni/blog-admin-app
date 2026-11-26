package cn.moodlog.admin

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 极简 tar 解包（ustar），支持普通文件与目录，忽略链接/权限位。
 */
class TarExtractor(
    private val input: InputStream,
    private val progress: (String, Long, Long, Long) -> Unit
) {
    fun extractTo(dest: File) {
        val header = ByteArray(512)
        var total = 0L
        while (true) {
            val read = readFully(header)
            if (read < 512) break
            if (header.all { it.toInt() == 0 }) break

            val name = String(header, 0, 100).trim('\u0000', ' ')
            val prefix = String(header, 345, 155).trim('\u0000', ' ')
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (fullName.isBlank()) break

            val sizeField = String(header, 124, 12).trim('\u0000', ' ')
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
            val type = header[156].toInt().toChar()

            val target = File(dest, fullName)
            // 防止路径穿越
            if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                skip(size)
                continue
            }

            if (type == '5' || fullName.endsWith("/")) {
                target.mkdirs()
            } else if (type == '0' || type == '\u0000') {
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> copyN(input, out, size) }
                total += size
                progress(fullName, size, total, -1)
            } else {
                skip(size)
            }

            val pad = (512 - (size % 512)).toLong() % 512
            skip(pad)
        }
    }

    private fun readFully(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun copyN(src: InputStream, dst: OutputStream, n: Long) {
        val buf = ByteArray(64 * 1024)
        var left = n
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val got = src.read(buf, 0, want)
            if (got < 0) break
            dst.write(buf, 0, got)
            left -= got
        }
    }

    private fun skip(n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val got = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (got < 0) break
            left -= got
        }
    }
}
