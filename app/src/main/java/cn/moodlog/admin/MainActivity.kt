package cn.moodlog.admin

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

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

    inner class Bridge {
        @JavascriptInterface
        fun ping(): String = "native-ok"

        @JavascriptInterface
        fun version(): String = "0.1.0-p0"
    }
}
