package com.rotaplay.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

internal const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/** Harita uygulama içinde (yatay) açılır; ayrı bir uygulamaya geçilmez. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapPage(dest: String) {
    val ctx = LocalContext.current
    val url = if (dest.isBlank()) "https://www.google.com/maps"
    else "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}&travelmode=driving"

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Navigation, null, tint = Cyan, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (dest.isBlank()) "Harita" else dest,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardColor)
                    .clickable {
                        val uri = if (dest.isBlank()) Uri.parse("geo:0,0")
                        else Uri.parse("google.navigation:q=${Uri.encode(dest)}")
                        open(ctx, Intent(Intent.ACTION_VIEW, uri))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("Sesli navigasyon (Maps)", color = Cyan, fontSize = 14.sp)
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#0B1220"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setGeolocationEnabled(true)
                    settings.userAgentString = DESKTOP_UA
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        // Harici uygulamaya atlamayı engelle (intent://, market:// vb.)
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
