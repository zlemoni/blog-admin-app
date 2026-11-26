package cn.moodlog.admin

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 极简 tar 解包器（ustar + GNU 长文件名 + pax 头）。
 * 只处理普通文件与目录；保留权限位（可执行），忽略链接与设备。
 */
class TarExtractor(
    private val input: InputStream,
    private val progress: (String, Long, Long, Long) -> Unit
) {
    private var pendingName: String? = null

    fun extractTo(dest: File) {
        val header = ByteArray(512)
        var total = 0L

        while (true) {
            if (readFully(header) < 512) break
            if (header.all { it.toInt() == 0 }) break

            val name = String(header, 0, 100).trim('\u0000', ' ')
            val prefix = String(header, 345, 155).trim('\u0000', ' ')
            val type = header[156].toInt().toChar()
            val size = parseSize(header)
            val mode = parseMode(header)

            when (type) {
                // GNU 长文件名：内容就是完整路径，下一条 header 的 name 是截断版
                'L' -> {
                    pendingName = readString(size).trimEnd('\u0000', '\n')
                    skipPad(size)
                    continue
                }
                // pax 扩展头：按 "len key=value\n" 解析
                'x' -> {
                    val body = readString(size)
                    body.split('\n').forEach { line ->
                        val eq = line.indexOf('=')
                        if (eq > 0 && line.substring(0, eq).substringAfter(' ').trim() == "path") {
                            pendingName = line.substring(eq + 1).trim()
                        }
                    }
                    skipPad(size)
                    continue
                }
                // 长链接名，忽略内容
                'K' -> {
                    skip(size); skipPad(size); continue
                }
                'g' -> { // 全局 pax 头
                    skip(size); skipPad(size); continue
                }
            }

            val fullName = pendingName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            pendingName = null
            if (fullName.isBlank()) { skip(size); skipPad(size); continue }

            val target = File(dest, fullName)
            if (!target.canonicalPath.startsWith(dest.canonicalPath + File.separator) &&
                target.canonicalPath != dest.canonicalPath
            ) {
                skip(size); skipPad(size); continue
            }

            if (type == '5' || fullName.endsWith("/")) {
                if (target.exists() && !target.isDirectory) target.delete()
                target.mkdirs()
            } else if (type == '0' || type == '\u0000' || type == '7') {
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                // 若同名目录已存在，先删掉，避免 EISDIR
                if (target.exists() && target.isDirectory) target.deleteRecursively()
                target.outputStream().use { out -> copyN(input, out, size) }
                // 恢复权限位：尤其 node 等可执行文件必须有 x 位
                applyMode(target, mode)
                total += size
                progress(fullName, size, total, -1)
            } else {
                skip(size)
            }
            skipPad(size)
        }
    }

    private fun parseMode(header: ByteArray): Int {
        val field = String(header, 100, 8).trim('\u0000', ' ')
        if (field.isEmpty()) return 0b110100000  // 默认 0640
        return try { field.toInt(8) } catch (_: NumberFormatException) { 0b110100000 }
    }

    private fun applyMode(target: File, mode: Int) {
        try {
            target.setExecutable((mode and 0b001000000) != 0, true)   // owner exec
            target.setExecutable((mode and 0b001000000) != 0, false)  // others exec
            target.setReadable(true, false)
            target.setWritable((mode and 0b010000000) != 0, true)     // owner write
        } catch (_: Throwable) {
            // 权限设置失败不阻断解压
        }
    }

    private fun parseSize(header: ByteArray): Long {
        val field = String(header, 124, 12).trim('\u0000', ' ')
        if (field.isEmpty()) return 0
        return try {
            field.toLong(8)
        } catch (_: NumberFormatException) {
            0
        }
    }

    private fun readString(size: Long): String {
        if (size <= 0 || size > 1024 * 1024) return ""
        val buf = ByteArray(size.toInt())
        readFully(buf)
        return String(buf, Charsets.UTF_8)
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

    private fun skipPad(size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) skip(pad)
    }
}
