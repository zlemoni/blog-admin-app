package cn.moodlog.admin

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // 运行环境分片（git 仓库里，走 jsDelivr / gh-proxy 国内可达）
    private val parts = (0 until 8).map { "part-" + ('a' + it / 26).toChar() + ('a' + it % 26).toChar() }
    private val mirrors = listOf(
        "https://cdn.jsdelivr.net/gh/zlemoni/blog-admin-app@main/payload/",
        "https://gh-proxy.com/https://raw.githubusercontent.com/zlemoni/blog-admin-app/main/payload/",
        "https://raw.githubusercontent.com/zlemoni/blog-admin-app/main/payload/"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Native")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun js(code: String) = ui.post { web.evaluateJavascript(code, null) }

    private fun log(line: String) {
        val safe = line.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        js("window.__log && window.__log('$safe')")
    }

    private fun stage(msg: String) = js("window.__stage && window.__stage('$msg')")

    private fun payloadDir(): File = File(filesDir, "payload")
    private fun ready(): Boolean = File(payloadDir(), "rootfs/usr/local/node/bin/node").exists()

    inner class Bridge {
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun status(): String = if (ready()) "ready" else "missing"

        @JavascriptInterface
        fun install() {
            io.execute {
                try {
                    if (ready()) { stage("环境已就绪"); return@execute }
                    val dir = payloadDir()
                    dir.mkdirs()
                    val tgz = File(filesDir, "payload.tar.gz")

                    if (!tgz.exists() || tgz.length() < 10_000_000L) {
                        downloadParts(tgz)
                    }

                    stage("解压中…")
                    var lastTick = 0L
                    GZIPInputStream(tgz.inputStream().buffered(1 shl 16), 1 shl 16).use { gz ->
                        TarExtractor(gz) { _, _, done, _ ->
                            if (done - lastTick > 16L * 1024 * 1024) {
                                lastTick = done
                                stage("解压中 ${done / 1048576} MB")
                            }
                        }.extractTo(dir)
                    }
                    tgz.delete()
                    stage(if (ready()) "环境就绪" else "解压完成但缺少 node")
                } catch (e: Throwable) {
                    log("安装失败: ${e.message}")
                    stage("安装失败")
                }
            }
        }

        /** 逐步自检：定位环境、proot、依赖、node 每一环是否就绪 */
        @JavascriptInterface
        fun diagnose() {
            io.execute {
                val dir = payloadDir()
                val libDir = File(applicationInfo.nativeLibraryDir)
                val proot = File(libDir, "libproot.so")
                val rootfs = File(dir, "rootfs")
                val project = File(dir, "project")

                stage("开始自检…")

                // 1. payload
                if (!rootfs.exists()) { stage("✗ 未解压环境，请先点「安装环境」"); return@execute }
                val node = File(rootfs, "usr/local/node/bin/node")
                if (!node.exists()) { stage("✗ 缺少 node 二进制"); return@execute }
                log("✓ rootfs 与 node 存在")

                // 2. proot 与依赖库
                if (!proot.exists()) { stage("✗ 缺少 proot"); return@execute }
                val deps = listOf("libtalloc.so", "libandroid-shmem.so")
                val missing = deps.filter { !File(libDir, it).exists() }
                if (missing.isNotEmpty()) { stage("✗ 缺少依赖库：${missing.joinToString()}"); return@execute }
                log("✓ proot 与依赖库齐全")

                // 3. node 版本
                stage("检查 node…")
                val ver = runProot(dir, libDir, proot, listOf("/usr/local/node/bin/node", "--version"))
                if (ver.second != 0) { stage("✗ node 无法运行，日志见下"); return@execute }
                log("✓ node ${ver.first.trim()}")

                // 4. astro 模块
                stage("检查 astro…")
                val astro = runProot(dir, libDir, proot, listOf(
                    "/usr/local/node/bin/node", "-e",
                    "require('/work/node_modules/astro/astro.js'); console.log('ok')"))
                if (astro.second != 0) { stage("✗ 无法加载 astro 模块，日志见下"); return@execute }
                log("✓ astro 模块可加载")

                stage("✓ 自检通过，可以构建")
            }
        }

        /** 在 proot 里跑一段命令，返回 (输出, 退出码) */
        private fun runProot(dir: File, libDir: File, proot: File, args: List<String>): Pair<String, Int> {
            val cmd = mutableListOf(
                proot.absolutePath,
                "-r", File(dir, "rootfs").absolutePath,
                "-b", "/proc", "-b", "/dev", "-b", "/sys",
                "-b", File(dir, "project").absolutePath + ":/work",
                "-w", "/work"
            )
            cmd.addAll(args)

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            pb.environment()["PROOT_NO_SECCOMP"] = "1"
            pb.environment()["HOME"] = "/root"
            pb.environment()["PATH"] = "/usr/local/node/bin:/usr/bin:/bin"
            pb.environment()["NODE_OPTIONS"] = "--max-old-space-size=2048"
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
            // proot 需要可写临时目录来建立 glue rootfs；Termux 编译版硬编码了 Termux 路径，必须显式指定
            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
            pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath

            val proc = pb.start()
            val out = StringBuilder()
            proc.inputStream.bufferedReader().use { r ->
                var line = r.readLine()
                while (line != null) { out.appendLine(line); log(line); line = r.readLine() }
            }
            val finished = proc.waitFor(180, TimeUnit.SECONDS)
            val code = if (finished) proc.exitValue() else -999
            if (!finished) proc.destroyForcibly()
            return out.toString() to code
        }

        @JavascriptInterface
        fun build() {
            io.execute {
                if (!ready()) { log("环境未就绪，请先点「安装环境」"); return@execute }
                val dir = payloadDir()
                val libDir = File(applicationInfo.nativeLibraryDir)
                val proot = File(libDir, "libproot.so")

                stage("正在构建…")
                val t0 = System.currentTimeMillis()
                val (_, code) = runProot(dir, libDir, proot, listOf(
                    "/usr/local/node/bin/node",
                    "node_modules/astro/astro.js", "build"))
                val secs = (System.currentTimeMillis() - t0) / 1000
                stage(if (code == 0) "✓ 构建成功（${secs}s）" else "✗ 构建失败（exit $code）")
                js("window.__buildDone && window.__buildDone($code)")
            }
        }

        /** 依次下载 8 个分片并拼接；每个分片带多镜像回退与重试 */
        private fun downloadParts(out: File) {
            out.delete()
            out.outputStream().use { }
            var totalGot = 0L
            parts.forEachIndexed { idx, name ->
                var ok = false
                var lastErr: Throwable? = null
                val baseLen = out.length()
                outer@ for (base in mirrors) {
                    val url = base + name
                    for (attempt in 1..4) {
                        try {
                            stage("下载 ${idx + 1}/${parts.size}（${totalGot / 1048576} MB）")
                            val got = fetchTo(out, url, baseLen)
                            totalGot += got
                            ok = true
                            log("分片 $name 完成 ${got / 1048576} MB")
                            break@outer
                        } catch (e: Throwable) {
                            lastErr = e
                            log("分片 $name 重试 $attempt: ${e.message}")
                            try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {}
                        }
                    }
                    log("切换镜像: ${base.substringBefore("/gh/").take(40)}…")
                }
                if (!ok) throw IllegalStateException("分片 $name 下载失败: ${lastErr?.message}", lastErr)
            }
            log("全部下载完成: ${out.length() / 1048576} MB")
        }

        /** 单分片下载（支持断点：按已写入长度续传） */
        private fun fetchTo(out: File, url: String, baseLen: Long): Long {
            // 续传偏移必须相对「当前分片」的起点，而不是整个文件的长度
            val already = (out.length() - baseLen).coerceAtLeast(0L)
            val conn = URL(url).openConnection() as HttpURLConnection
            if (already > 0L) conn.setRequestProperty("Range", "bytes=$already-")
            conn.connectTimeout = 25000
            conn.readTimeout = 90000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
            var got = 0L
            java.io.FileOutputStream(out, true).use { fos ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        got += n
                    }
                }
            }
            if (got == 0L) throw IllegalStateException("空响应")
            return got
        }

        private fun fetchRange(url: String, start: Long, end: Long, out: File) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.connectTimeout = 25000
            conn.readTimeout = 90000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 206 && code != 200) throw IllegalStateException("HTTP $code")

            java.io.RandomAccessFile(out, "rw").use { raf ->
                raf.seek(start)
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    var pos = start
                    while (pos <= end) {
                        val want = minOf((end - pos + 1).toInt(), buf.size)
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n
                    }
                    if (pos <= end) throw IllegalStateException("分片不完整 ${pos - start}/${end - start + 1}")
                }
            }
        }

        private fun remoteSize(url: String): Long {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            return conn.contentLengthLong
        }
    }
}
