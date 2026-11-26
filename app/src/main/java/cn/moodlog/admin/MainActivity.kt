package cn.moodlog.admin

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

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
        fun status(): String =
            if (ready()) "ready" else "missing"

        /** 解压安装包内置的 payload.tar.gz */
        @JavascriptInterface
        fun install() {
            io.execute {
                try {
                    if (ready()) { stage("环境已就绪"); return@execute }
                    stage("正在解压运行环境…")
                    val dir = payloadDir()
                    dir.mkdirs()
                    assets.open("payload.tar.gz").use { raw ->
                        GZIPInputStream(raw, 1 shl 16).use { gz ->
                            TarExtractor(gz) { name, size, done, total ->
                                if (done % (8L * 1024 * 1024) < 65536) {
                                    val pct = if (total > 0) done * 100 / total else 0
                                    stage("解压中 $pct%（${done / 1048576} MB）")
                                }
                            }.extractTo(dir)
                        }
                    }
                    stage(if (ready()) "环境就绪" else "解压完成但缺少 node")
                } catch (e: Throwable) {
                    log("解压失败: ${e.message}")
                    stage("解压失败")
                }
            }
        }

        /** 用 proot 跑 astro build */
        @JavascriptInterface
        fun build() {
            io.execute {
                if (!ready()) { log("环境未就绪，请先点「安装环境」"); return@execute }
                val dir = payloadDir()
                val rootfs = File(dir, "rootfs")
                val project = File(dir, "project")
                val proot = File(applicationInfo.nativeLibraryDir, "libproot.so")
                if (!proot.exists()) { log("缺少 proot 二进制"); return@execute }

                val cmd = listOf(
                    proot.absolutePath,
                    "-r", rootfs.absolutePath,
                    "-b", "/proc",
                    "-b", "/dev",
                    "-b", "/sys",
                    "-b", "${project.absolutePath}:/work",
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
                    proc.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            log(line)
                            line = reader.readLine()
                        }
                    }
                    val code = proc.waitFor()
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    if (code == 0) stage("构建成功（${secs}s）") else stage("构建失败（exit $code）")
                    js("window.__buildDone && window.__buildDone($code)")
                } catch (e: Throwable) {
                    log("执行异常: ${e.message}")
                    stage("执行异常")
                }
            }
        }
    }
}
