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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** 分片清单：分片数、总大小、每片大小 */
data class Manifest(val parts: Int, val size: Long, val sizes: List<Long>)

/** Vercel 上传用的文件描述 */
data class VFile(val rel: String, val sha: String, val size: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // 运行环境分片（git 仓库里，走 jsDelivr / gh-proxy 国内可达）
    // 分片名由 manifest 决定，避免包大小变化时分片数写死
    private fun partNames(count: Int) =
        (0 until count).map { "part-" + ('a' + it / 26).toChar() + ('a' + it % 26).toChar() }
    // 完整包（单文件）源，优先使用；失败再回退分片
    private val fullUrls = listOf(
        // v4.gh-proxy.org 实测 ~2.2 MB/s，放第一位
        "https://v4.gh-proxy.org/https://github.com/zlemoni/blog-admin-app/releases/download/payload-v2/payload-v2.tar.gz",
        "https://gh-proxy.com/https://github.com/zlemoni/blog-admin-app/releases/download/payload-v2/payload-v2.tar.gz",
        "https://github.com/zlemoni/blog-admin-app/releases/download/payload-v2/payload-v2.tar.gz"
    )

    private val mirrors = listOf(
        // 首选：渺软公益 CDN（jsDelivr 国内加速）
        "https://jsd.onmicrosoft.cn/gh/zlemoni/blog-admin-app@main/payload2/",
        // 备用：jsDelivr 官方
        "https://cdn.jsdelivr.net/gh/zlemoni/blog-admin-app@main/payload2/",
        // 兜底：gh-proxy 反代
        "https://gh-proxy.com/https://raw.githubusercontent.com/zlemoni/blog-admin-app/main/payload2/"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Native")
            // 没有 WebChromeClient 时 alert/confirm 会被静默吞掉
            webChromeClient = android.webkit.WebChromeClient()
            loadUrl("file:///android_asset/index.html")
        }
        imagePicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) io.execute { processPickedImage(uri) }
        }
        setContentView(web)
        // 首次解压耗时较长，避免息屏导致进程被回收
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    // 内置 tar 解压后的总字节数（用于进度百分比）
    private val PAYLOAD_RAW_SIZE = 483_758_080L

    // Vercel 接口基址：api.vercel.com 在国内被阻断，主站同名路径可达
    private val vercelApi = "https://vercel.com/api"

    // 选图：Activity Result API，回调在 Activity 层，Bridge 里只负责触发
    private var pendingQuality = 80
    private lateinit var imagePicker: androidx.activity.result.ActivityResultLauncher<String>

    // 载荷版本：换 Node/依赖时必须改这里，旧环境会被自动清掉重装
    private val payloadVer = "p2-node2214"
    private val markerFile: File get() = File(filesDir, ".payload-ver")

    // 以「解压完成标记」为准，避免靠单个文件是否存在来猜
    private fun ready(): Boolean =
        markerFile.exists() &&
            markerFile.readText().trim() == payloadVer &&
            File(payloadDir(), "rootfs/usr/local/node/bin/node").canExecute()


    /** 把选中的图片压成 WebP 存到 public/images */
    private fun processPickedImage(uri: android.net.Uri) {
        try {
            stage("处理图片…")
            val resolver = contentResolver
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }

            var scale = 1
            val maxEdge = maxOf(opts.outWidth, opts.outHeight)
            while (maxEdge / scale > 1600) scale *= 2

            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
            var bmp = resolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: throw IllegalStateException("无法读取图片")

            try {
                resolver.openInputStream(uri)?.use { input ->
                    val exif = androidx.exifinterface.media.ExifInterface(input)
                    val deg = when (exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                    if (deg != 0f) {
                        val m = android.graphics.Matrix().apply { postRotate(deg) }
                        bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                }
            } catch (_: Throwable) {}

            val dir = File(payloadDir(), "project/public/images").apply { mkdirs() }
            val name = "img-" + System.currentTimeMillis() + ".webp"
            val outFile = File(dir, name)
            outFile.outputStream().use { os ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, pendingQuality, os)
            }
            val kb = outFile.length() / 1024
            log("已保存 /images/$name（${kb}KB，质量 $pendingQuality）")
            stage("✓ 图片已保存（${kb}KB）")
            js("window.__imageReady && window.__imageReady('/images/$name')")
        } catch (e: Throwable) {
            log("图片处理失败: ${e.message}")
            stage("✗ 图片处理失败: ${e.message}")
        }
    }

    /** 构建前修补配置：sharp → passthrough；关闭远程字体（大陆拉不到，会卡 2 分钟） */
    private fun patchImageService(dir: File) {
        try {
            val cfg = File(dir, "project/astro.config.ts")
            if (!cfg.exists()) return
            var text = cfg.readText()
            var changed = false

            // Astro 6 里 passthrough 已改名为 noop
            if (text.contains("astro/assets/services/sharp")) {
                text = text.replace("astro/assets/services/sharp", "astro/assets/services/noop")
                log("图片服务已切换为 noop（手机无 sharp）")
                changed = true
            }
            // 兼容上一版误写的 passthrough
            if (text.contains("astro/assets/services/passthrough")) {
                text = text.replace("astro/assets/services/passthrough", "astro/assets/services/noop")
                log("修正图片服务名 passthrough → noop")
                changed = true
            }

            if (text.contains("fontProviders.fontshare")) {
                // 整个 fonts 数组置空，避免构建时联网取字体
                val re = Regex("fonts:\\s*\\[[\\s\\S]*?\\n  \\],")
                val replaced = re.replaceFirst(text, "fonts: [],")
                if (replaced != text) {
                    text = replaced
                    log("已关闭远程字体（fontshare 无法访问）")
                    changed = true
                }
            }

            // 清空 fonts 后，页面里的 <Font /> 会报 FontFamilyNotFound，一并移除
            if (text.contains("fonts: [],")) {
                val srcDir = File(dir, "project/src")
                if (srcDir.exists()) {
                    var removed = 0
                    srcDir.walkTopDown().filter { it.isFile && it.name.endsWith(".astro") }.forEach { f ->
                        val t0 = f.readText()
                        val t1 = t0.replace(Regex("<Font\\b[\\s\\S]*?/>"), "")
                                     .replace(Regex("<Font\\b[\\s\\S]*?</Font>"), "")
                                     // 字体变量已无定义，换成系统字体栈
                                     .replace("var(--font-satoshi)",
                                              "-apple-system, \"SF Pro Text\", \"PingFang SC\", Roboto, \"Noto Sans SC\", sans-serif")
                        if (t1 != t0) { f.writeText(t1); removed++ }
                    }
                    if (removed > 0) log("已移除 $removed 个 <Font> 组件（改用系统字体）")
                }
            }

            if (changed) cfg.writeText(text)
        } catch (e: Throwable) {
            log("配置补丁失败: ${e.message}")
        }
    }

    @Volatile private var previewServer: java.net.ServerSocket? = null
    @Volatile private var previewThread: Thread? = null
    private val previewPort = 4321

    /** 启动本地预览：把 project/dist 用 HTTP 服务出来 */
    private fun startPreview(dir: File) {
        stopPreview()
        val dist = File(dir, "project/dist")
        if (!dist.exists()) { stage("还没有产物，请先构建"); return }

        previewThread = Thread {
            try {
                val server = java.net.ServerSocket(previewPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
                previewServer = server
                log("预览服务已启动: http://127.0.0.1:$previewPort")
                stage("预览已启动")
                while (!server.isClosed) {
                    val sock = try { server.accept() } catch (e: Throwable) { break }
                    Thread {
                        try {
                            sock.use { s ->
                                val input = s.getInputStream().bufferedReader()
                                val requestLine = input.readLine() ?: return@use
                                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                                while (true) { val h = input.readLine(); if (h.isNullOrEmpty()) break }

                                var rel = java.net.URLDecoder.decode(path.substringBefore('?'), "UTF-8")
                                if (rel == "/") rel = "/index.html"
                                var file = File(dist, rel)
                                if (file.isDirectory) file = File(file, "index.html")
                                if (!file.exists() && !File(file.path + ".html").exists()) {
                                    file = File(dist, "404.html")
                                } else if (!file.exists()) {
                                    file = File(file.path + ".html")
                                }

                                val body = if (file.exists()) file.readBytes() else "Not Found".toByteArray()
                                val mime = when (file.extension.lowercase()) {
                                    "html" -> "text/html; charset=utf-8"
                                    "css" -> "text/css; charset=utf-8"
                                    "js", "mjs" -> "application/javascript; charset=utf-8"
                                    "json" -> "application/json; charset=utf-8"
                                    "svg" -> "image/svg+xml"
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "webp" -> "image/webp"
                                    "woff2" -> "font/woff2"
                                    "xml" -> "application/xml; charset=utf-8"
                                    "txt" -> "text/plain; charset=utf-8"
                                    else -> "application/octet-stream"
                                }
                                val out = s.getOutputStream()
                                val head = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                                out.write(head.toByteArray())
                                out.write(body)
                                out.flush()
                            }
                        } catch (_: Throwable) {}
                    }.start()
                }
            } catch (e: Throwable) {
                log("预览服务异常: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPreview() {
        try { previewServer?.close() } catch (_: Throwable) {}
        previewServer = null
        previewThread = null
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    /** 统计 dist：页面数、文件数、体积 */
    private fun distSummary(dist: File): String {
        if (!dist.exists()) return "未生成 dist"
        var files = 0; var pages = 0; var bytes = 0L
        dist.walkTopDown().forEach { f ->
            if (f.isFile) {
                files++; bytes += f.length()
                if (f.name.endsWith(".html")) pages++
            }
        }
        return "$pages 页 / $files 文件 / ${bytes / 1048576} MB"
    }

    /** rootfs 里没有 hosts / resolv.conf 时，Node 解析 localhost 会报 EAI_AGAIN */
    private fun ensureEtc() {
        try {
            val etc = File(payloadDir(), "rootfs/etc")
            if (!etc.exists()) etc.mkdirs()
            val hosts = File(etc, "hosts")
            hosts.writeText(
                "127.0.0.1 localhost localhost.localdomain\n" +
                "::1 localhost ip6-localhost ip6-loopback\n" +
                "127.0.0.1 android\n"
            )
            val resolv = File(etc, "resolv.conf")
            resolv.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")
            // 证书目录也补齐，避免后续 https 请求失败
            val ssl = File(payloadDir(), "rootfs/etc/ssl/certs")
            if (!ssl.exists()) ssl.mkdirs()
        } catch (e: Throwable) {
            log("初始化 /etc 失败: ${e.message}")
        }
    }

    /** 清空本地环境：rootfs、project、压缩包、标记 */
    private fun wipeEnv() {
        markerFile.delete()
        File(filesDir, "payload.tar.gz").delete()
        File(filesDir, "tmp").deleteRecursively()
        payloadDir().deleteRecursively()
    }

    inner class Bridge {
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        /** 检查最新版本，返回 JSON 给前端 */
        @JavascriptInterface
        fun checkUpdate() {
            io.execute {
                try {
                    // 先读 CDN 上的 version.json（不受 GitHub API 限流影响）
                    val info = fetchVersionInfo()
                    val latest = info.first
                    val apkUrl = info.second
                    val cur = BuildConfig.VERSION_NAME
                    val has = cmpVersion(latest, cur) > 0
                    val result = JSONObject().apply {
                        put("current", cur)
                        put("latest", latest)
                        put("hasUpdate", has)
                        put("direct", apkUrl)
                        put("mirror", if (apkUrl.startsWith("http")) "https://gh-proxy.com/" + apkUrl else "")
                    }
                    js("window.__update && window.__update('" + esc(result.toString()) + "')")
                } catch (e: Throwable) {
                    js("window.__updateErr && window.__updateErr('" + esc(e.message ?: "检查失败") + "')")
                }
            }
        }

        /** 返回 (最新版本, APK 直链)；先 CDN，后 GitHub API */
        private fun fetchVersionInfo(): Pair<String, String> {
            var lastErr: Throwable? = null
            for (base in mirrors) {
                try {
                    val conn = URL(base + "version.json").openConnection() as HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout = 20000
                    conn.instanceFollowRedirects = true
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    return o.getString("version") to o.getString("apk")
                } catch (e: Throwable) {
                    lastErr = e
                }
            }
            // 兜底：GitHub API
            try {
                val conn = URL("https://api.github.com/repos/zlemoni/blog-admin-app/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "blog-admin-app")
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                var apk = ""
                val assets = o.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
                }
                return o.getString("tag_name").removePrefix("v") to apk
            } catch (e: Throwable) {
                throw IllegalStateException("获取版本失败: ${lastErr?.message ?: e.message}")
            }
        }

        /** 语义化版本比较：0.4.9-p26 > 0.4.8-p25 */
        private fun cmpVersion(a: String, b: String): Int {
            fun nums(v: String) = Regex("\\d+").findAll(v.removePrefix("v"))
                .map { it.value.toIntOrNull() ?: 0 }.toList()
            val x = nums(a); val y = nums(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val l = x.getOrElse(i) { 0 }
                val r = y.getOrElse(i) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }

        /** 用系统浏览器打开链接 */
        @JavascriptInterface
        fun openLink(url: String) {
            ui.post {
                try {
                    startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Throwable) {
                    log("打开链接失败: ${e.message}")
                }
            }
        }

        /** 应用内下载 APK 并拉起安装器 */
        @JavascriptInterface
        fun downloadUpdate(url: String) {
            io.execute {
                try {
                    val dir = java.io.File(getExternalFilesDir(null) ?: filesDir, "update").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val apk = java.io.File(dir, "update.apk")
                    stage("下载更新…")
                    var got = 0L
                    var total = 0L

                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 25000
                    conn.readTimeout = 90000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "blog-admin-app")
                    total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        apk.outputStream().buffered(1 shl 16).use { os ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                got += n
                                if (total > 0) stage("下载更新 ${got * 100 / total}%")
                            }
                        }
                    }
                    stage("下载完成，准备安装")
                    installApk(apk)
                } catch (e: Throwable) {
                    log("更新下载失败: ${e.message}")
                    stage("更新下载失败")
                }
            }
        }

        private fun installApk(apk: java.io.File) {
            ui.post {
                try {
                    log("APK 大小: ${apk.length() / 1048576} MB")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        if (!packageManager.canRequestPackageInstalls()) {
                            log("未授予「安装未知应用」权限，请到系统设置允许后重试")
                            stage("请先允许本应用安装未知应用")
                            val i = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"))
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                            return@post
                        }
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity, "cn.moodlog.admin.fileprovider", apk)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    log("URI: $uri")
                    startActivity(intent)
                    log("已调用系统安装器")
                    stage("请在系统安装界面确认")
                } catch (e: Throwable) {
                    log("拉起安装器失败: ${e.message}")
                    stage("拉起安装器失败")
                }
            }
        }

        private fun esc(s: String): String =
            s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")



        @JavascriptInterface
        fun status(): String = if (ready()) "ready" else "missing"

        @JavascriptInterface
        fun install() {
            io.execute {
                try {
                    if (ready()) { stage("环境已就绪"); return@execute }

                    val dir = payloadDir()
                    val tgz = File(filesDir, "payload.tar.gz")
                    val installedVer = if (markerFile.exists()) markerFile.readText().trim() else ""
                    if (installedVer != payloadVer) {
                        log("环境版本 ${installedVer.ifEmpty { "未知" }} → $payloadVer，重新安装")
                        tgz.delete()   // 旧包作废，强制重新下载
                    }

                    if (!tgz.exists() || tgz.length() < 10_000_000L) {
                        try {
                            downloadFull(tgz)
                        } catch (e: Throwable) {
                            log("单文件下载失败(${e.message})，改用分片")
                            downloadParts(tgz)
                        }
                    }

                    // 旧环境可能缺符号链接/权限位，清空后干净重解
                    markerFile.delete()
                    File(dir, "rootfs").deleteRecursively()
                    File(dir, "project").deleteRecursively()
                    dir.mkdirs()

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

                    val node = File(dir, "rootfs/usr/local/node/bin/node")
                    val loader = File(dir, "rootfs/lib/ld-linux-aarch64.so.1")
                    when {
                        !node.exists() -> stage("解压完成但缺少 node")
                        !node.canExecute() -> stage("解压完成但 node 不可执行")
                        // File.exists() 会跟随符号链接，/lib 缺失或链接断了都会在这里暴露
                        !loader.exists() -> stage("解压完成但缺少动态链接器 /lib/ld-linux-aarch64.so.1")
                        else -> {
                            markerFile.writeText(payloadVer)
                            stage("环境就绪")
                        }
                    }
                } catch (e: Throwable) {
                    log("安装失败: ${e.message}")
                    stage("安装失败")
                }
            }
        }

        /** 逐步自检：定位环境、proot、依赖、node 每一环是否就绪 */
        /** 首次启动：内置包解压，完成后通知前端 */
        @JavascriptInterface
        fun ensureEnv() {
            io.execute {
                try {
                    if (ready()) {
                        js("window.__envReady && window.__envReady()")
                        return@execute
                    }
                    val dir = payloadDir()
                    val tgz = File(filesDir, "payload.tar.gz")

                    // 优先用 APK 内置包，零网络。
                    // 注意：AAPT2 会把 .gz 资源解压并改名（payload.tar.gz → payload.tar），
                    // 所以这里按实际存在的名字取，并区分是否还是 gzip。
                    fun assetExists(n: String): Boolean = try {
                        assets.open(n).use { true }
                    } catch (e: Throwable) { false }

                    val assetName = when {
                        assetExists("payload.bin") -> "payload.bin"
                        assetExists("payload.tar.gz") -> "payload.tar.gz"
                        assetExists("payload.tar") -> "payload.tar"
                        else -> null
                    }
                    val bundled = assetName != null
                    if (bundled) log("使用内置包: $assetName")

                    if (!bundled && (!tgz.exists() || tgz.length() < 10_000_000L)) {
                        stage("正在下载运行环境…")
                        try { downloadFull(tgz) } catch (e: Throwable) { downloadParts(tgz) }
                    } else if (bundled) {
                        stage("正在安装运行环境…")
                    }

                    markerFile.delete()
                    File(dir, "rootfs").deleteRecursively()
                    File(dir, "project").deleteRecursively()
                    dir.mkdirs()

                    if (bundled) {
                        assets.open(assetName!!, android.content.res.AssetManager.ACCESS_STREAMING).use { raw ->
                            // payload.tar 是裸 tar（AAPT 已解压过），其余仍是 gzip
                            val stream = if (assetName!!.endsWith(".tar")) raw.buffered(1 shl 16)
                                         else GZIPInputStream(raw.buffered(1 shl 16), 1 shl 16)
                            var lastTick = 0L
                            var files = 0
                            TarExtractor(stream) { _, _, done, _ ->
                                files++
                                if (done - lastTick > 4L * 1024 * 1024 || files % 500 == 0) {
                                    lastTick = done
                                    val pct = (done * 100 / PAYLOAD_RAW_SIZE).coerceIn(0L, 100L)
                                    stage("正在解压 $pct%（${done / 1048576} MB / ${PAYLOAD_RAW_SIZE / 1048576} MB，${files} 个文件）")
                                    js("window.__progress && window.__progress($pct)")
                                }
                            }.extractTo(dir)
                        }
                    } else {
                        GZIPInputStream(tgz.inputStream().buffered(1 shl 16), 1 shl 16).use { gz ->
                            var lastTick = 0L
                            TarExtractor(gz) { _, _, done, _ ->
                                if (done - lastTick > 16L * 1024 * 1024) {
                                    lastTick = done
                                    stage("正在解压 ${done / 1048576} MB")
                                }
                            }.extractTo(dir)
                        }
                        tgz.delete()
                    }

                    val node = File(dir, "rootfs/usr/local/node/bin/node")
                    val loader = File(dir, "rootfs/lib/ld-linux-aarch64.so.1")
                    when {
                        !node.exists() -> stage("安装失败：缺少 node")
                        !node.canExecute() -> stage("安装失败：node 不可执行")
                        !loader.exists() -> stage("安装失败：缺少动态链接器")
                        else -> {
                            markerFile.writeText(payloadVer)
                            stage("环境就绪")
                            js("window.__envReady && window.__envReady()")
                        }
                    }
                } catch (e: Throwable) {
                    log("环境安装失败: ${e.message}")
                    stage("安装失败: ${e.message}")
                    js("window.__envError && window.__envError('" + esc(e.message ?: "未知错误") + "')")
                }
            }
        }

        /** 清除本地环境（用户手动调用） */
        // ---------- 配置 ----------
        private fun prefs() = getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)

        /** 统一的连接入口：配置了代理就走代理（Vercel API 在国内常被阻断） */
        private fun open(url: String): HttpURLConnection {
            val host = prefs().getString("proxyHost", "") ?: ""
            val port = prefs().getString("proxyPort", "")?.toIntOrNull() ?: 0
            return if (host.isNotEmpty() && port > 0) {
                val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress(host, port))
                URL(url).openConnection(proxy) as HttpURLConnection
            } else {
                URL(url).openConnection() as HttpURLConnection
            }
        }

        @JavascriptInterface
        fun saveConfig(key: String, value: String) {
            prefs().edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun loadConfig(): String {
            val p = prefs()
            val o = JSONObject()
            o.put("vercelToken", p.getString("vercelToken", "") ?: "")
            o.put("vercelProject", p.getString("vercelProject", "") ?: "")
            o.put("walineUrl", p.getString("walineUrl", "https://c.moodlog.cn/waline") ?: "")
            o.put("ghToken", p.getString("ghToken", "") ?: "")
            o.put("ghRepo", p.getString("ghRepo", "") ?: "")
            o.put("ghBranch", p.getString("ghBranch", "main") ?: "main")
            o.put("proxyHost", p.getString("proxyHost", "") ?: "")
            o.put("proxyPort", p.getString("proxyPort", "") ?: "")
            return o.toString()
        }

        // ---------- Waline 评论 ----------

        private fun walineBase(): String =
            (prefs().getString("walineUrl", "https://c.moodlog.cn/waline") ?: "").trimEnd('/')

        private fun walineToken(): String = prefs().getString("walineToken", "") ?: ""

        /** 管理员登录，拿 JWT */
        @JavascriptInterface
        fun walineLogin(email: String, password: String) {
            io.execute {
                try {
                    stage("Waline 登录…")
                    val base = walineBase()
                    log("→ POST $base/api/token")
                    val body = JSONObject().apply {
                        put("email", email.trim())
                        put("password", password)
                    }.toString()
                    val resp = requestJson("$base/api/token", "POST", null, body)
                    val o = JSONObject(resp)
                    val token = o.optString("token")
                    if (token.isEmpty()) throw IllegalStateException("未返回 token")
                    prefs().edit().putString("walineToken", token)
                        .putString("walineEmail", email.trim()).apply()
                    log("登录成功")
                    stage("✓ Waline 已登录")
                    js("window.__walineLogin && window.__walineLogin('" + esc(o.optJSONObject("user")?.optString("display_name") ?: email) + "')")
                } catch (e: Throwable) {
                    log("登录失败: ${e.message}")
                    stage("✗ 登录失败: ${e.message}")
                }
            }
        }

        /** 拉评论列表：status = all|waiting|approved|spam */
        @JavascriptInterface
        fun walineComments(status: String, page: Int) {
            io.execute {
                try {
                    val base = walineBase()
                    val token = walineToken()
                    if (token.isEmpty()) { stage("请先登录 Waline"); return@execute }
                    stage("加载评论…")
                    val url = "$base/api/comment?type=list&status=$status&page=$page&pageSize=20&lang=zh-CN"
                    log("→ GET $url")
                    val resp = requestJson(url, "GET", token, null)
                    val o = JSONObject(resp)
                    val data = o.optJSONArray("data") ?: org.json.JSONArray()
                    val out = org.json.JSONArray()
                    for (i in 0 until data.length()) {
                        val c = data.getJSONObject(i)
                        out.put(JSONObject().apply {
                            put("id", c.optInt("objectId"))
                            put("nick", c.optString("nick"))
                            put("mail", c.optString("mail"))
                            put("comment", c.optString("comment"))
                            put("url", c.optString("url"))
                            put("status", c.optString("status"))
                            put("time", c.optLong("time"))
                            put("pid", c.optInt("pid"))
                        })
                    }
                    log("共 ${out.length()} 条")
                    js("window.__comments && window.__comments('" + esc(out.toString()) + "')")
                    stage("共 ${out.length()} 条评论")
                } catch (e: Throwable) {
                    log("加载评论失败: ${e.message}")
                    stage("✗ 加载失败: ${e.message}")
                }
            }
        }

        /** 审核：status = approved|spam|waiting */
        @JavascriptInterface
        fun walineSetStatus(id: Int, status: String) {
            io.execute {
                try {
                    val base = walineBase()
                    log("→ PUT $base/api/comment/$id → $status")
                    requestJson("$base/api/comment/$id", "PUT", walineToken(),
                        JSONObject().put("status", status).toString())
                    stage("✓ 已设为 $status")
                } catch (e: Throwable) {
                    log("操作失败: ${e.message}")
                    stage("✗ 操作失败: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun walineDelete(id: Int) {
            io.execute {
                try {
                    val base = walineBase()
                    log("→ DELETE $base/api/comment/$id")
                    requestJson("$base/api/comment/$id", "DELETE", walineToken(), null)
                    stage("✓ 已删除")
                } catch (e: Throwable) {
                    log("删除失败: ${e.message}")
                    stage("✗ 删除失败: ${e.message}")
                }
            }
        }

        /** 管理员回复 */
        @JavascriptInterface
        fun walineReply(id: Int, text: String) {
            io.execute {
                try {
                    val base = walineBase()
                    val email = prefs().getString("walineEmail", "") ?: ""
                    val body = JSONObject().apply {
                        put("comment", text)
                        put("nick", "博主")
                        put("mail", email)
                        put("url", "")
                        put("ua", "MoodlogApp")
                        put("pid", 0)
                        put("rid", id)
                    }.toString()
                    log("→ POST $base/api/comment (回复 #$id)")
                    requestJson("$base/api/comment", "POST", walineToken(), body)
                    stage("✓ 已回复")
                } catch (e: Throwable) {
                    log("回复失败: ${e.message}")
                    stage("✗ 回复失败: ${e.message}")
                }
            }
        }

        /** 通用 JSON 请求（Waline 用） */
        private fun requestJson(url: String, method: String, token: String?, body: String?): String {
            val t0 = System.currentTimeMillis()
            val conn = open(url)
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MoodlogApp")
            if (!token.isNullOrEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            log("← $code ${System.currentTimeMillis() - t0}ms ${text.take(120)}")
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(160)}")
            return text
        }

        @JavascriptInterface
        fun pickImage(quality: Int) {
            pendingQuality = quality.coerceIn(30, 100)
            ui.post {
                try { imagePicker.launch("image/*") }
                catch (e: Throwable) { log("打开相册失败: ${e.message}") }
            }
        }

        // ---------- 文章 ----------

        private fun blogDir(): File = File(payloadDir(), "project/src/content/blog")

        @JavascriptInterface
        fun listPosts(): String {
            val out = org.json.JSONArray()
            try {
                val dir = blogDir()
                if (!dir.exists()) return "[]"
                dir.walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".mdx")) }
                    .sortedByDescending { it.lastModified() }
                    .forEach { f ->
                        val text = try { f.readText().take(1200) } catch (e: Throwable) { "" }
                        val title = Regex("(?m)^title:\\s*[\"']?(.+?)[\"']?\\s*$")
                            .find(text)?.groupValues?.get(1)?.trim()
                            ?: f.nameWithoutExtension
                        val date = Regex("(?m)^(?:pubDate|date|publishDate):\\s*(.+?)\\s*$")
                            .find(text)?.groupValues?.get(1)?.trim() ?: ""
                        out.put(JSONObject().apply {
                            put("path", f.relativeTo(themeRoot()).invariantSeparatorsPath)
                            put("title", title)
                            put("date", date)
                            put("mtime", f.lastModified())
                        })
                    }
            } catch (e: Throwable) {
                log("列出文章失败: ${e.message}")
            }
            return out.toString()
        }

        @JavascriptInterface
        fun createPost(title: String): String {
            return try {
                val dir = blogDir()
                dir.mkdirs()
                val slug = title.trim().lowercase()
                    .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
                    .trim('-')
                    .ifEmpty { "untitled-" + System.currentTimeMillis() }
                val f = File(dir, "$slug.md")
                if (f.exists()) return "已存在同名文章: ${f.name}"
                val now = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                f.writeText(
                    "---\n" +
                    "title: ${title.trim()}\n" +
                    "description: ''\n" +
                    "pubDate: $now\n" +
                    "draft: true\n" +
                    "---\n\n在这里开始写正文。\n"
                )
                log("已创建文章: ${f.name}")
                f.relativeTo(themeRoot()).invariantSeparatorsPath
            } catch (e: Throwable) {
                "创建失败: ${e.message}"
            }
        }

        @JavascriptInterface
        fun deletePost(path: String): String {
            return try {
                val f = safeThemeFile(path) ?: return "路径不允许"
                if (!f.exists()) return "文件不存在"
                f.delete()
                log("已删除: $path")
                "ok"
            } catch (e: Throwable) {
                "删除失败: ${e.message}"
            }
        }



        // ---------- 主题文件读写 ----------

        private fun themeRoot(): File = File(payloadDir(), "project")

        /** 只允许访问 src/ 与 astro.config.ts，防越权 */
        private fun safeThemeFile(rel: String): File? {
            val clean = rel.replace("\\", "/").trimStart('/')
            if (clean.contains("..")) return null
            val allowed = clean == "astro.config.ts" || clean.startsWith("src/")
            if (!allowed) return null
            return File(themeRoot(), clean)
        }

        @JavascriptInterface
        fun listThemeFiles(): String {
            val out = org.json.JSONArray()
            try {
                val root = themeRoot()
                val cfg = File(root, "astro.config.ts")
                if (cfg.exists()) out.put(JSONObject().apply {
                    put("path", "astro.config.ts"); put("size", cfg.length())
                })
                val src = File(root, "src")
                if (src.exists()) {
                    src.walkTopDown()
                        .filter { it.isFile }
                        .filter { f ->
                            val n = f.name.lowercase()
                            n.endsWith(".ts") || n.endsWith(".astro") || n.endsWith(".css") ||
                                n.endsWith(".md") || n.endsWith(".mdx") || n.endsWith(".json") || n.endsWith(".js")
                        }
                        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                        .forEach { f ->
                            out.put(JSONObject().apply {
                                put("path", f.relativeTo(root).invariantSeparatorsPath)
                                put("size", f.length())
                            })
                        }
                }
            } catch (e: Throwable) {
                log("列出主题文件失败: ${e.message}")
            }
            return out.toString()
        }

        @JavascriptInterface
        fun readThemeFile(path: String): String {
            return try {
                val f = safeThemeFile(path) ?: return ""
                if (!f.exists()) "" else f.readText()
            } catch (e: Throwable) {
                log("读取失败: ${e.message}"); ""
            }
        }

        @JavascriptInterface
        fun writeThemeFile(path: String, text: String): String {
            return try {
                val f = safeThemeFile(path) ?: return "路径不允许"
                f.parentFile?.mkdirs()
                f.writeText(text)
                log("已保存: $path")
                "ok"
            } catch (e: Throwable) {
                "保存失败: ${e.message}"
            }
        }

        /** 网络诊断：逐个探测关键域名，输出状态码与耗时 */
        @JavascriptInterface
        fun netDiagnose() {
            io.execute {
                val targets = listOf(
                    "https://vercel.com/" to "Vercel 主站",
                    vercelApi + "/v2/user" to "Vercel API",
                    "https://api.github.com/" to "GitHub API",
                    "https://cdn.jsdelivr.net/" to "jsDelivr"
                )
                for ((url, name) in targets) {
                    val t0 = System.currentTimeMillis()
                    try {
                        val conn = open(url)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.requestMethod = "GET"
                        val code = conn.responseCode
                        log("✓ $name  HTTP $code  ${System.currentTimeMillis() - t0}ms")
                    } catch (e: Throwable) {
                        log("✗ $name  失败 ${System.currentTimeMillis() - t0}ms: ${e.message}")
                    }
                }
                stage("诊断完成，见日志")
            }
        }

        /** 用 token 新建项目（无需去网页） */
        @JavascriptInterface
        fun createProject(name: String) {
            io.execute {
                try {
                    val token = prefs().getString("vercelToken", "") ?: ""
                    if (token.isEmpty()) { stage("请先填写 Vercel Token"); return@execute }
                    val proj = name.trim()
                    if (proj.isEmpty()) { stage("请填写项目名（小写字母/数字/中划线）"); return@execute }

                    stage("创建项目 $proj …")
                    val body = JSONObject().apply {
                        put("name", proj)
                        put("framework", JSONObject.NULL)
                    }.toString()
                    val resp = httpJson(vercelApi + "/v10/projects", "POST", token, body)
                    val o = JSONObject(resp)
                    val created = o.optString("name")
                    log("项目已创建: $created")
                    prefs().edit().putString("vercelProject", created).apply()
                    stage("✓ 项目已创建: $created")
                    js("window.__projectCreated && window.__projectCreated('" + esc(created) + "')")
                } catch (e: Throwable) {
                    val msg = e.message ?: ""
                    if (msg.contains("409") || msg.contains("already exists")) {
                        stage("项目已存在，可直接用于发布")
                        prefs().edit().putString("vercelProject", name.trim()).apply()
                    } else {
                        log("创建项目失败: $msg")
                        stage("✗ 创建项目失败: $msg")
                    }
                }
            }
        }

        /** 通过 GitHub 推送 dist，让 Vercel 的 Git 集成自动构建部署（绕过 api.vercel.com） */
        @JavascriptInterface
        fun pushToGitHub() {
            io.execute {
                try {
                    val ghToken = prefs().getString("ghToken", "") ?: ""
                    val repo = prefs().getString("ghRepo", "") ?: ""
                    val branch = prefs().getString("ghBranch", "main")?.ifEmpty { "main" } ?: "main"
                    if (ghToken.isEmpty() || repo.isEmpty()) { stage("请先填写 GitHub Token 和 仓库"); return@execute }
                    val dist = File(payloadDir(), "project/dist")
                    if (!dist.exists()) { stage("没有产物，请先构建"); return@execute }

                    val files = dist.walkTopDown().filter { it.isFile }.toList()
                    log("待推送 ${files.size} 个文件 → $repo@$branch")

                    // 1) 当前分支 SHA（没有则视为空仓库）
                    var parent: String? = null
                    try {
                        val ref = JSONObject(ghApi("https://api.github.com/repos/$repo/git/ref/heads/$branch", "GET", ghToken, null))
                        parent = ref.getJSONObject("object").getString("sha")
                    } catch (e: Throwable) {
                        log("分支不存在，将创建: $branch")
                    }

                    // 2) 逐文件建 blob
                    val tree = org.json.JSONArray()
                    files.forEachIndexed { i, f ->
                        if (i % 20 == 0) stage("上传 ${i + 1}/${files.size}")
                        val rel = f.relativeTo(dist).invariantSeparatorsPath
                        val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                        val body = JSONObject().apply {
                            put("content", b64)
                            put("encoding", "base64")
                        }.toString()
                        val blob = JSONObject(ghApi("https://api.github.com/repos/$repo/git/blobs", "POST", ghToken, body))
                        tree.put(JSONObject().apply {
                            put("path", rel)
                            put("mode", "100644")
                            put("type", "blob")
                            put("sha", blob.getString("sha"))
                        })
                    }

                    // 3) 建 tree（不带 base_tree，等于整站替换）
                    stage("创建目录树…")
                    val treeBody = JSONObject().put("tree", tree).toString()
                    val treeSha = JSONObject(ghApi("https://api.github.com/repos/$repo/git/trees", "POST", ghToken, treeBody))
                        .getString("sha")

                    // 4) 提交
                    stage("创建提交…")
                    val commitObj = JSONObject().apply {
                        put("message", "deploy: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
                        put("tree", treeSha)
                        if (parent != null) put("parents", org.json.JSONArray().put(parent))
                    }
                    val commitSha = JSONObject(ghApi("https://api.github.com/repos/$repo/git/commits", "POST", ghToken, commitObj.toString()))
                        .getString("sha")

                    // 5) 更新分支
                    stage("更新分支…")
                    if (parent == null) {
                        val refBody = JSONObject().apply {
                            put("ref", "refs/heads/$branch")
                            put("sha", commitSha)
                        }.toString()
                        ghApi("https://api.github.com/repos/$repo/git/refs", "POST", ghToken, refBody)
                    } else {
                        val refBody = JSONObject().apply {
                            put("sha", commitSha)
                            put("force", true)
                        }.toString()
                        ghApi("https://api.github.com/repos/$repo/git/refs/heads/$branch", "PATCH", ghToken, refBody)
                    }

                    stage("✓ 已推送，Vercel 将自动构建")
                    js("window.__deployDone && window.__deployDone('https://github.com/$repo/commits/$branch')")
                } catch (e: Throwable) {
                    log("推送失败: ${e.message}")
                    stage("✗ 推送失败: ${e.message}")
                }
            }
        }

        private fun ghApi(url: String, method: String, token: String, body: String?): String {
            val conn = open(url)
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "blog-admin-app")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(180)}")
            return text
        }

        /** 用 token 拉取项目列表，供用户选择 */
        @JavascriptInterface
        fun listProjects() {
            io.execute {
                try {
                    val token = prefs().getString("vercelToken", "") ?: ""
                    if (token.isEmpty()) { stage("请先填写 Vercel Token"); return@execute }
                    stage("获取项目列表…")
                    log("token 长度: ${token.length}，代理: ${prefs().getString("proxyHost","")}:${prefs().getString("proxyPort","")}")
                    val t0 = System.currentTimeMillis()
                    val json = httpJson(vercelApi + "/v9/projects?limit=100", "GET", token, null)
                    log("列表请求完成 ${System.currentTimeMillis() - t0}ms")
                    val arr = JSONObject(json).optJSONArray("projects")
                    val out = org.json.JSONArray()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.put(JSONObject().apply {
                            put("id", o.optString("id"))
                            put("name", o.optString("name"))
                        })
                    }
                    log("找到 ${out.length()} 个项目")
                    js("window.__projects && window.__projects('" + esc(out.toString()) + "')")
                    stage("共 ${out.length()} 个项目")
                } catch (e: Throwable) {
                    log("获取项目失败: ${e.message}")
                    stage("✗ 获取项目失败: ${e.message}")
                    js("window.__projects && window.__projects('[]')")
                }
            }
        }

        /** 用 Vercel API 直接上传 dist 并发布（无需 Git） */
        @JavascriptInterface
        fun deployVercel() {
            io.execute {
                try {
                    val token = prefs().getString("vercelToken", "") ?: ""
                    if (token.isEmpty()) { stage("请先填写 Vercel Token"); return@execute }
                    val project = prefs().getString("vercelProject", "")?.ifEmpty { "moodlog-blog" } ?: "moodlog-blog"
                    val dist = File(payloadDir(), "project/dist")
                    if (!dist.exists()) { stage("没有产物，请先构建"); return@execute }

                    stage("准备上传…")
                    val files = dist.walkTopDown().filter { it.isFile }.toList()
                    log("待上传 ${files.size} 个文件")

                    val items = files.map { f ->
                        val rel = f.relativeTo(dist).invariantSeparatorsPath
                        VFile(rel, sha1(f), f.length())
                    }

                    // 1) 逐个上传（Vercel 按 sha 去重）
                    items.forEachIndexed { i, it ->
                        stage("上传 ${i + 1}/${items.size}")
                        uploadVercelFile(token, it.sha, File(dist, it.rel))
                    }

                    // 2) 创建部署
                    stage("创建部署…")
                    val payload = JSONObject().apply {
                        put("name", project)
                        put("target", "production")
                        put("files", org.json.JSONArray().apply {
                            items.forEach { it2 ->
                                put(JSONObject().apply {
                                    put("file", it2.rel)
                                    put("sha", it2.sha)
                                    put("size", it2.size)
                                })
                            }
                        })
                    }
                    val resp = httpJson(vercelApi + "/v13/deployments?forceNew=1", "POST",
                        token, payload.toString())
                    val dep = JSONObject(resp)
                    val id = dep.optString("id")
                    val url = dep.optString("url")
                    log("部署已创建: $url")

                    // 3) 轮询状态
                    var state = ""
                    var tries = 0
                    while (tries < 60) {
                        Thread.sleep(3000)
                        tries++
                        val cur = JSONObject(httpJson(vercelApi + "/v13/deployments/$id", "GET", token, null))
                        state = cur.optString("readyState")
                        stage("部署中: $state")
                        if (state == "READY" || state == "ERROR" || state == "CANCELED") break
                    }
                    if (state == "READY") {
                        stage("✓ 已发布: https://$url")
                        js("window.__deployDone && window.__deployDone('" + esc("https://$url") + "')")
                    } else {
                        stage("✗ 部署失败: $state")
                    }
                } catch (e: Throwable) {
                    log("部署异常: ${e.message}")
                    stage("✗ 部署失败: ${e.message}")
                }
            }
        }

        private fun sha1(f: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            f.inputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun uploadVercelFile(token: String, sha: String, f: File) {
            val conn = open(vercelApi + "/v2/files")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("x-vercel-digest", sha)
            conn.setFixedLengthStreamingMode(f.length())
            conn.outputStream.use { os -> f.inputStream().use { it.copyTo(os, 128 * 1024) } }
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                // 已存在（409/413）视为成功
                if (code == 409) return
                throw IllegalStateException("上传失败 HTTP $code $msg")
            }
        }

        private fun httpJson(url: String, method: String, token: String, body: String?): String {
            val t0 = System.currentTimeMillis()
            log("→ $method $url")
            val conn = open(url)
            conn.requestMethod = method
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            log("← $code ${ms}ms ${text.take(140)}")
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(200)}")
            return text
        }

        @JavascriptInterface
        fun preview() {
            io.execute { startPreview(payloadDir()) }
        }

        @JavascriptInterface
        fun previewUrl(): String = "http://127.0.0.1:$previewPort/"

        @JavascriptInterface
        fun clearEnv() {
            io.execute {
                try {
                    stage("正在清除环境…")
                    wipeEnv()
                    stage("环境已清除，请重新点「安装环境」")
                } catch (e: Throwable) {
                    log("清除失败: ${e.message}")
                    stage("清除失败")
                }
            }
        }

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

                // 2.4 proot loader（缺它 proot 无法 exec 任何动态程序）
                val pl = File(libDir, "libproot-loader.so")
                if (!pl.exists()) { stage("✗ 缺少 proot loader"); return@execute }
                log("✓ proot loader 就位")

                // 2.5 动态链接器（node 是 glibc 程序，没有它必然 execve 失败）
                val loader = File(rootfs, "lib/ld-linux-aarch64.so.1")
                val libLink = File(rootfs, "lib")
                log("  /lib ${if (libLink.exists()) "存在" else "缺失"}" +
                    "${if (java.nio.file.Files.isSymbolicLink(libLink.toPath())) "（符号链接→" +
                        java.nio.file.Files.readSymbolicLink(libLink.toPath()) + "）" else ""}")
                if (!loader.exists()) {
                    stage("✗ 缺少动态链接器 /lib/ld-linux-aarch64.so.1，请点「安装环境」重新解压")
                    return@execute
                }
                log("✓ 动态链接器就位")

                // 3. node 版本
                stage("检查 node…")
                val ver = runProot(dir, libDir, proot, listOf("/usr/local/node/bin/node", "--version"))
                if (ver.second != 0) { stage("✗ node 无法运行，日志见下"); return@execute }
                log("✓ node ${ver.first.trim()}")

                // 4. astro 模块
                stage("检查 astro…")
                val entry = File(project, "node_modules/astro/bin/astro.mjs")
                if (!entry.exists()) { stage("✗ 缺少 astro 入口 node_modules/astro/bin/astro.mjs"); return@execute }
                val astro = runProot(dir, libDir, proot, listOf(
                    "/usr/local/node/bin/node",
                    "node_modules/astro/bin/astro.mjs", "--version"))
                if (astro.second != 0) { stage("✗ astro 无法运行，日志见下"); return@execute }
                log("✓ astro ${astro.first.trim().take(40)}")

                stage("✓ 自检通过，可以构建")
            }
        }

        /** 在 proot 里跑一段命令，返回 (输出, 退出码) */
        private fun runProot(dir: File, libDir: File, proot: File, args: List<String>): Pair<String, Int> {
            ensureEtc()

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
            pb.environment()["ASTRO_TELEMETRY_DISABLED"] = "1"
            pb.environment()["CI"] = "1"
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
            // proot 需要可写临时目录来建立 glue rootfs；Termux 编译版硬编码了 Termux 路径，必须显式指定
            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
            pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            // proot 需要自己的 loader 才能执行 guest 里的动态链接程序（如 node）
            val loader = File(libDir, "libproot-loader.so")
            val loader32 = File(libDir, "libproot-loader32.so")
            if (loader.exists()) pb.environment()["PROOT_LOADER"] = loader.absolutePath
            if (loader32.exists()) pb.environment()["PROOT_LOADER_32"] = loader32.absolutePath

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

                patchImageService(dir)
                stage("正在构建…")
                val t0 = System.currentTimeMillis()
                val (_, code) = runProot(dir, libDir, proot, listOf(
                    "/usr/local/node/bin/node",
                    "node_modules/astro/bin/astro.mjs", "build"))
                val secs = (System.currentTimeMillis() - t0) / 1000
                val dist = File(dir, "project/dist")
                val pageCount = if (dist.exists()) dist.walkTopDown().count { it.isFile && it.name.endsWith(".html") } else 0
                if (code == 0 && pageCount > 0) {
                    log("产物: ${distSummary(dist)}")
                    stage("✓ 构建成功（${secs}s）")
                } else if (code == 0) {
                    stage("✗ 构建失败（未见产物，看上面日志）")
                } else {
                    stage("✗ 构建失败（exit $code）")
                }
                js("window.__buildDone && window.__buildDone($code)")
            }
        }

        /** 依次下载 8 个分片并拼接；每个分片带多镜像回退与重试 */
        /** 完整包下载：支持断点续传，失败换源 */
        private fun downloadFull(out: File) {
            out.delete()
            var lastErr: Throwable? = null
            for ((i, url) in fullUrls.withIndex()) {
                val label = when {
                    url.contains("v4.gh-proxy.org") -> "gh-proxy v4"
                    url.contains("gh-proxy.com") -> "gh-proxy"
                    else -> "GitHub 直链"
                }
                for (attempt in 1..3) {
                    try {
                        stage("下载完整包（${label}）")
                        val total = remoteSize(url)
                        if (total > 0) log("包大小: ${total / 1048576} MB")
                        var done = 0L
                        while (true) {
                            val already = out.length()
                            if (total > 0 && already >= total) break
                            val conn = URL(url).openConnection() as HttpURLConnection
                            if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
                            conn.connectTimeout = 15000
                            conn.readTimeout = 30000
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", "blog-admin-app")
                            val code = conn.responseCode
                            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                            val before = already
                            conn.inputStream.use { input ->
                                java.io.FileOutputStream(out, true).use { os ->
                                    val buf = ByteArray(128 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        os.write(buf, 0, n)
                                        done = out.length()
                                        if (total > 0 && done % (8L * 1024 * 1024) < 131072) {
                                            stage("下载完整包 ${done * 100 / total}%")
                                        }
                                    }
                                }
                            }
                            if (out.length() == before) throw IllegalStateException("连接提前结束")
                            if (total <= 0) break
                        }
                        // 校验
                        val size = out.length()
                        if (total > 0 && size != total) throw IllegalStateException("大小不符 $size/$total")
                        val magic = ByteArray(2)
                        out.inputStream().use { it.read(magic) }
                        if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                            throw IllegalStateException("不是有效 gzip 包")
                        }
                        log("完整包下载完成: ${size / 1048576} MB")
                        return
                    } catch (e: Throwable) {
                        lastErr = e
                        log("完整包重试 $attempt（$label）: ${e.message}")
                        try { Thread.sleep(600L * attempt) } catch (_: InterruptedException) {}
                    }
                }
                if (i < fullUrls.size - 1) log("换下一个源")
            }
            throw IllegalStateException("完整包下载失败: ${lastErr?.message}", lastErr)
        }

        private fun downloadParts(out: File) {
            val manifest = fetchManifest()
            val names = partNames(manifest.parts)
            val expectSize = manifest.size
            val sizes = manifest.sizes
            log("分片数: ${names.size}，期望大小: ${expectSize / 1048576} MB")

            out.delete()
            out.outputStream().use { }

            names.forEachIndexed { idx, name ->
                val want = sizes.getOrElse(idx) { -1L }
                var ok = false
                var lastErr: Throwable? = null

                outer@ for (base in mirrors) {
                    val url = base + name
                    for (attempt in 1..2) {
                        val tmp = File(filesDir, "part.tmp")
                        try {
                            stage("下载 ${idx + 1}/${names.size}")
                            val got = fetchTo(tmp, url, 0L)
                            // 逐片校验：大小必须与清单一致，且不能是 HTML 错误页
                            val head = ByteArray(1)
                            tmp.inputStream().use { it.read(head) }
                            if (want > 0 && got != want) throw IllegalStateException("大小不符 $got/$want")
                            if (head[0] == '<'.code.toByte()) throw IllegalStateException("返回了 HTML 页面")
                            // 必须追加写入，否则每片都会清空前面已下载的内容
                            tmp.inputStream().use { input ->
                                java.io.FileOutputStream(out, true).use { os ->
                                    input.copyTo(os, 128 * 1024)
                                }
                            }
                            tmp.delete()
                            ok = true
                            log("分片 $name 完成 ${got / 1048576} MB")
                            break@outer
                        } catch (e: Throwable) {
                            lastErr = e
                            tmp.delete()
                            log("分片 $name 重试 $attempt: ${e.message}")
                            try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {}
                        }
                    }
                    val label = when {
                        base.contains("onmicrosoft") -> "渺软 CDN"
                        base.contains("jsdelivr") -> "jsDelivr CDN"
                        base.contains("gh-proxy") -> "gh-proxy 反代"
                        else -> base
                    }
                    log("切换镜像 → $label")
                }
                if (!ok) throw IllegalStateException("分片 $name 下载失败: ${lastErr?.message}", lastErr)
            }

            val got = out.length()
            if (expectSize > 0 && got != expectSize) throw IllegalStateException("总大小不符：$got / $expectSize")
            // gzip 魔数校验，杜绝坏包进入解压
            val magic = ByteArray(2)
            out.inputStream().use { it.read(magic) }
            if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                out.delete()
                throw IllegalStateException("下载内容不是有效的 gzip 包")
            }
            log("全部下载完成: ${got / 1048576} MB")
        }

        private fun fetchManifest(): Manifest {
            for (base in mirrors) {
                try {
                    val conn = URL(base + "manifest.json").openConnection() as HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout = 20000
                    conn.instanceFollowRedirects = true
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val arr = o.optJSONArray("sizes")
                    val sizes = if (arr != null) (0 until arr.length()).map { arr.getLong(it) } else emptyList()
                    return Manifest(o.getInt("parts"), o.getLong("size"), sizes)
                } catch (e: Throwable) {
                    log("清单获取失败(${e.message})，换下一个源")
                }
            }
            log("清单全部失败，使用默认 10 片")
            return Manifest(10, 0L, emptyList())
        }

        /** 单分片下载（支持断点：按已写入长度续传） */
        private fun fetchTo(out: File, url: String, baseLen: Long): Long {
            // 续传偏移必须相对「当前分片」的起点，而不是整个文件的长度
            val already = (out.length() - baseLen).coerceAtLeast(0L)
            val conn = URL(url).openConnection() as HttpURLConnection
            if (already > 0L) conn.setRequestProperty("Range", "bytes=$already-")
            conn.connectTimeout = 12000
            conn.readTimeout = 30000
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
            conn.connectTimeout = 12000
            conn.readTimeout = 30000
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
