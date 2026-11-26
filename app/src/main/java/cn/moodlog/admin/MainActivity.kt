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

    // 首次启动时从这里拉运行环境（约 173 MB），之后完全离线
    private val payloadUrl = "https://c.moodlog.cn/_dl/payload-86fz.tar.gz"

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
                        stage("下载运行环境…")
                        download(payloadUrl, tgz)
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
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { input ->
                out.outputStream().buffered(1 shl 16).use { os ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        got += n
                        if (total > 0 && got % (8L * 1024 * 1024) < 65536) {
                            stage("下载中 ${got * 100 / total}%")
                        }
                    }
                }
            }
            log("下载完成: ${got / 1048576} MB")
        }
    }
}
