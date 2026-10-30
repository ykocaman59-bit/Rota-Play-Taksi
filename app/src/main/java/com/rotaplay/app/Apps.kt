package com.rotaplay.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val pkg: String, val icon: ImageBitmap, val category: String?)

private val CATEGORIES = listOf("Navigasyon", "Müzik", "Podcast & Kitap", "Mesajlaşma")

/** CarPlay / Android Auto'da desteklenen uygulama kategorileri */
private val KNOWN: Map<String, String> = buildMap {
    listOf(
        "com.google.android.apps.maps", "com.waze", "ru.yandex.yandexnavi",
        "ru.yandex.yandexmaps", "com.here.app.maps", "com.sygic.aura"
    ).forEach { put(it, "Navigasyon") }
    listOf(
        "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music",
        "deezer.android.app", "com.amazon.mp3", "com.soundcloud.android", "com.aspiro.tidal",
        "com.pandora.android", "ru.yandex.music", "com.turkcell.gncplay", "org.videolan.vlc",
        "com.maxmpz.audioplayer"
    ).forEach { put(it, "Müzik") }
    listOf(
        "au.com.shiftyjelly.pocketcasts", "com.audible.application", "tunein.player",
        "fm.castbox.audiobook.radio.podcast", "com.google.android.apps.podcasts"
    ).forEach { put(it, "Podcast & Kitap") }
    listOf(
        "com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.facebook.orca"
    ).forEach { put(it, "Mesajlaşma") }
}

private fun iconOf(d: Drawable, size: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    d.setBounds(0, 0, size, size)
    d.draw(canvas)
    return bmp.asImageBitmap()
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull { ri ->
            runCatching {
                val pkg = ri.activityInfo.packageName
                AppEntry(ri.loadLabel(pm).toString(), pkg, iconOf(ri.loadIcon(pm), 96), KNOWN[pkg])
            }.getOrNull()
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun landscapeWanted(ctx: Context) =
    ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).getBoolean("land", false)

/** Uygulamayı açar; yatay mod açıksa yatay zorlar ve üstte RotaPlay ev düğmesini gösterir. */
fun launchApp(ctx: Context, pkg: String) {
    runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        if (landscapeWanted(ctx) && Settings.System.canWrite(ctx)) setSystemLandscape(ctx, true)
        if (Settings.canDrawOverlays(ctx)) {
            ctx.startService(Intent(ctx, DockOverlayService::class.java))
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

/** Tüm sistemi (diğer uygulamalar dahil) yatay yapar / eski haline döndürür. */
fun setSystemLandscape(ctx: Context, on: Boolean) {
    if (!Settings.System.canWrite(ctx)) {
        open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        return
    }
    ctx.getSharedPreferences("rota", Context.MODE_PRIVATE).edit().putBoolean("land", on).apply()
    runCatching {
        val cr = ctx.contentResolver
        if (on) {
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_90)
        } else {
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
        }
    }
}

data class WebApp(val title: String, val url: String, val icon: ImageVector, val color: Color)

private val WEBS = listOf(
    WebApp("YouTube Music", "https://music.youtube.com", Icons.Rounded.MusicNote, Color(0xFFE11D48)),
    WebApp("Spotify", "https://open.spotify.com", Icons.Rounded.MusicNote, Color(0xFF1DB954)),
    WebApp("YouTube", "https://www.youtube.com", Icons.Rounded.PlayArrow, Color(0xFFDC2626)),
    WebApp("Waze Harita", "https://www.waze.com/live-map", Icons.Rounded.Navigation, Color(0xFF33CCFF))
)

@Composable
fun AppsPage(onWeb: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<AppEntry>?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { runCatching { loadApps(ctx) }.getOrDefault(emptyList()) }
    }

    val list = all
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Yükleniyor…", color = Color.White.copy(alpha = 0.7f))
        }
        return
    }

    val carApps = list.filter { it.category != null }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!showAll) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                Text("CarPlay uygulamaları", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_sys") {
                Text("Araç modu", fontSize = 16.sp, color = Cyan, modifier = Modifier.padding(top = 4.dp))
            }
            item(key = "s_land") {
                WebTile(WebApp("Her şey yatay", "", Icons.Rounded.ScreenRotation, Color(0xFF6366F1))) {
                    setSystemLandscape(ctx, true)
                }
            }
            item(key = "s_free") {
                WebTile(WebApp("Yatay kilidi kaldır", "", Icons.Rounded.ScreenRotation, Color(0xFF64748B))) {
                    setSystemLandscape(ctx, false)
                }
            }
            item(key = "s_dock") {
                WebTile(WebApp("Ev düğmesi", "", Icons.Rounded.Home, Color(0xFF14B8A6))) {
                    if (Settings.canDrawOverlays(ctx)) {
                        ctx.startService(Intent(ctx, DockOverlayService::class.java))
                    } else {
                        open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }
            item(key = "s_home") {
                WebTile(WebApp("Ana ekranım yap", "", Icons.Rounded.Home, Color(0xFF0EA5E9))) {
                    open(ctx, Intent(Settings.ACTION_HOME_SETTINGS))
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                Text(
                    "İpucu: RotaPlay'i bölünmüş ekranda aç, uygulamalar yanında açılır.",
                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_web") {
                Text("Uygulama içinde aç", fontSize = 16.sp, color = Cyan, modifier = Modifier.padding(top = 4.dp))
            }
            items(WEBS, key = { "w_" + it.title }) { w -> WebTile(w) { onWeb(w.title, w.url) } }
            item(span = { GridItemSpan(maxLineSpan) }, key = "h_real") {
                Text("Yüklü uygulamalar", fontSize = 16.sp, color = Cyan, modifier = Modifier.padding(top = 8.dp))
            }
            if (carApps.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        "Telefonunda CarPlay uyumlu uygulama bulunamadı (Spotify, Maps, Waze, WhatsApp vb.).",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            CATEGORIES.forEach { cat ->
                val inCat = carApps.filter { it.category == cat }
                if (inCat.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h_$cat") {
                        Text(cat, fontSize = 16.sp, color = Cyan, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(inCat, key = { it.pkg }) { AppCell(it) { launchApp(ctx, it.pkg) } }
                }
            }
        } else {
            items(list, key = { it.pkg }) { AppCell(it) { launchApp(ctx, it.pkg) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "toggle") {
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardColor)
                    .clickable { showAll = !showAll },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (showAll) "CarPlay uygulamalarına dön" else "Tüm uygulamalar",
                    color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AppCell(app: AppEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(app.icon, app.label, Modifier.size(64.dp))
        Spacer(Modifier.height(6.dp))
        Text(app.label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun WebTile(app: WebApp, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(app.color),
            contentAlignment = Alignment.Center
        ) {
            Icon(app.icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(app.title, fontSize = 13.sp, maxLines = 1)
    }
}
