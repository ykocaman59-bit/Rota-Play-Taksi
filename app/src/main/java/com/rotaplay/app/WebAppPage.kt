package com.rotaplay.app

import android.annotation.SuppressLint
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** Web tabanlı uygulamayı RotaPlay'in içinde, yatay ekranda açar. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppPage(title: String, url: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#0B1220"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setGeolocationEnabled(true)
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = DESKTOP_UA
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            val s = request.url.scheme
                            return s != "http" && s != "https"
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String, callback: GeolocationPermissions.Callback
                        ) {
                            callback.invoke(origin, true, false)
                        }
                    }
                    web = this
                }
            },
            update = { w ->
                if (w.tag != url) {
                    w.tag = url
                    w.loadUrl(url)
                }
            },
            onRelease = { it.destroy() }
        )
    }
}
