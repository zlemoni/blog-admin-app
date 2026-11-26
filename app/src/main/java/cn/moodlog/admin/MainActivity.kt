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
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // 运行环境（首次启动下载），优先精简版，失败回退完整版
    private val payloadUrls = listOf(
        "https://c.moodlog.cn/_dl/payload-slim.tar.gz",
        "https://c.moodlog.cn/_dl/payload-86fz.tar.gz"
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
                        var lastErr: Throwable? = null
                        for (u in payloadUrls) {
                            try {
                                stage("下载运行环境…")
                                log("源: $u")
                                download(u, tgz)
                                lastErr = null
                                break
                            } catch (e: Throwable) {
                                lastErr = e
                                log("该源失败: ${e.message}")
                            }
                        }
                        if (lastErr != null) throw lastErr
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

        @JavascriptInterface
        fun build() {
            io.execute {
                if (!ready()) { log("环境未就绪，请先点「安装环境」"); return@execute }
                val dir = payloadDir()
                val proot = File(applicationInfo.nativeLibraryDir, "libproot.so")
                if (!proot.exists()) { log("缺少 proot 二进制"); return@execute }

                val cmd = listOf(
                    proot.absolutePath,
                    "-r", File(dir, "rootfs").absolutePath,
                    "-b", "/proc", "-b", "/dev", "-b", "/sys",
                    "-b", File(dir, "project").absolutePath + ":/work",
                    "-w", "/work",
                    "-e", "HOME=/root",
                    "-e", "PATH=/usr/local/node/bin:/usr/bin:/bin",
                    "-e", "NODE_OPTIONS=--max-old-space-size=2048",
                    "/usr/local/node/bin/node",
                    "node_modules/astro/astro.js", "build"
                )

                stage("正在构建…")
                val t0 = System.currentTimeMillis()
                try {
                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    pb.environment()["PROOT_NO_SECCOMP"] = "1"
                    val proc = pb.start()
                    proc.inputStream.bufferedReader().use { r ->
                        var line = r.readLine()
                        while (line != null) { log(line); line = r.readLine() }
                    }
                    val code = proc.waitFor()
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    stage(if (code == 0) "构建成功（${secs}s）" else "构建失败（exit $code）")
                    js("window.__buildDone && window.__buildDone($code)")
                } catch (e: Throwable) {
                    log("执行异常: ${e.message}")
                    stage("执行异常")
                }
            }
        }

        private fun download(url: String, out: File) {
            val total = remoteSize(url)
            if (total <= 0) throw IllegalStateException("无法获取文件大小")
            log("文件大小: ${total / 1048576} MB，4 线程并发下载")

            out.delete()
            java.io.RandomAccessFile(out, "rw").use { raf -> raf.setLength(total) }

            val workers = 4
            val chunk = 8L * 1024 * 1024
            val parts = ((total + chunk - 1) / chunk).toInt()
            val done = java.util.concurrent.atomic.AtomicLong(0)
            val failed = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
            val latch = java.util.concurrent.CountDownLatch(parts)

            for (i in 0 until parts) {
                pool.execute {
                    try {
                        val start = i * chunk
                        val end = minOf(start + chunk - 1, total - 1)
                        var attempt = 0
                        while (true) {
                            try {
                                fetchRange(url, start, end, out)
                                done.addAndGet(end - start + 1)
                                val pct = done.get() * 100 / total
                                stage("下载中 $pct%")
                                break
                            } catch (e: Throwable) {
                                attempt++
                                if (attempt >= 8) throw e
                                log("分片重试 $attempt @${start / 1048576}MB: ${e.message}")
                                Thread.sleep(500L * attempt)
                            }
                        }
                    } catch (e: Throwable) {
                        failed.compareAndSet(null, e)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()

            failed.get()?.let { throw IllegalStateException("下载失败: ${it.message}", it) }
            log("下载完成: ${out.length() / 1048576} MB")
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
