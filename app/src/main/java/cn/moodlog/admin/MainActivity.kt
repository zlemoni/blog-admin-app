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
        // ghfast 实测 ~1.5 MB/s，放第一位
        "https://ghfast.top/https://github.com/zlemoni/blog-admin-app/releases/download/payload-v2/payload-v2.tar.gz",
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
    // 载荷版本：换 Node/依赖时必须改这里，旧环境会被自动清掉重装
    private val payloadVer = "p2-node2214"
    private val markerFile: File get() = File(filesDir, ".payload-ver")

    // 以「解压完成标记」为准，避免靠单个文件是否存在来猜
    private fun ready(): Boolean =
        markerFile.exists() &&
            markerFile.readText().trim() == payloadVer &&
            File(payloadDir(), "rootfs/usr/local/node/bin/node").canExecute()

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
        /** 清除本地环境（用户手动调用） */
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

                stage("正在构建…")
                val t0 = System.currentTimeMillis()
                val (_, code) = runProot(dir, libDir, proot, listOf(
                    "/usr/local/node/bin/node",
                    "node_modules/astro/bin/astro.mjs", "build"))
                val secs = (System.currentTimeMillis() - t0) / 1000
                stage(if (code == 0) "✓ 构建成功（${secs}s）" else "✗ 构建失败（exit $code）")
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
                    url.contains("ghfast") -> "ghfast"
                    url.contains("gh-proxy") -> "gh-proxy"
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
