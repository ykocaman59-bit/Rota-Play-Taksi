package com.rotaplay.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal fun open(ctx: Context, intent: Intent): Boolean = try {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { false }

@Composable
fun Dashboard() {
    // 0 ana ekran, 1 uygulamalar, 2 harita, 3 telefon, 4 web uygulama, 5 taksi
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mapDest by rememberSaveable { mutableStateOf("") }
    var webTitle by rememberSaveable { mutableStateOf("") }
    var webUrl by rememberSaveable { mutableStateOf("") }

    // Hangi sekmeye girildiyse hareket kaydına yaz
    LaunchedEffect(tab) {
        val names = listOf("ana_ekran", "uygulamalar", "harita", "telefon", "web_uygulama", "taksi")
        TaxiStore.track("sekme", names.getOrElse(tab) { "?" })
    }

    // Taksi servisi öne getirince (talep geldi / yolculuk bitti) taksi sekmesini aç
    LaunchedEffect(TaxiStore.openTaxi) {
        if (TaxiStore.openTaxi) { tab = 5; TaxiStore.openTaxi = false }
    }

    BackHandler(enabled = tab != 0) { tab = if (tab == 4) 1 else 0 }

    Surface(modifier = Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) {
        Row(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- Sol dock ----
            Column(
                Modifier.width(72.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DockButton(Icons.Rounded.Home, tab == 0) { tab = 0 }
                    DockButton(Icons.Rounded.Apps, tab == 1 || tab == 4) { tab = 1 }
                    DockButton(Icons.Rounded.LocalTaxi, tab == 5) { tab = 5 }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DockButton(Icons.Rounded.Phone, tab == 3) { tab = 3 }
                    DockButton(Icons.Rounded.Navigation, tab == 2) { tab = 2 }
                }
            }

            // ---- İçerik ----
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    0 -> HomePage(onRoute = { mapDest = it; tab = 2 })
                    1 -> AppsPage(onWeb = { t, u -> webTitle = t; webUrl = u; tab = 4 })
                    2 -> MapPage(mapDest)
                    3 -> DialerPage()
                    5 -> TaxiPage(onHome = { tab = 0 })
                    else -> WebAppPage(webTitle, webUrl, onClose = { tab = 1 })
                }
            }
        }
    }
}

@Composable
private fun DockButton(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (selected) Cyan.copy(alpha = 0.25f) else CardColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (selected) Cyan else Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun HomePage(onRoute: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        NavCard(Modifier.weight(1.2f).fillMaxHeight(), onRoute)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ClockCard(Modifier.weight(1f).fillMaxHeight())
                SpeedCard(Modifier.weight(1f).fillMaxHeight())
            }
            MediaCard(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CardBox(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = CardColor,
        contentColor = Color.White
    ) { content() }
}

@Composable
private fun NavCard(modifier: Modifier, onRoute: (String) -> Unit) {
    val ctx = LocalContext.current
    var dest by rememberSaveable { mutableStateOf("") }

    fun go() { onRoute(dest.trim()) }

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E3A5F), Color(0xFF123B8C))))
            .padding(28.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Icon(Icons.Rounded.Navigation, null, tint = Cyan, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("Navigasyon", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Nereye gidiyoruz?", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Adres veya yer ara") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() })
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Cyan)
                        .clickable { go() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Rotayı başlat", color = Color(0xFF04202A), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ClockCard(modifier: Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }
    val tr = Locale.forLanguageTag("tr-TR")
    CardBox(modifier) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 54.sp, fontWeight = FontWeight.Bold)
            Text(now.format(DateTimeFormatter.ofPattern("d MMMM", tr)), fontSize = 18.sp, color = Cyan)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE", tr)), fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun rememberSpeedKmh(): State<Int> {
    val ctx = LocalContext.current
    val speed = remember { mutableIntStateOf(0) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            speed.intValue = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        } catch (_: Exception) { }
        onDispose { lm.removeUpdates(listener) }
    }
    return remember { object : State<Int> { override val value: Int get() = speed.intValue } }
}

@Composable
private fun SpeedCard(modifier: Modifier) {
    val speed by rememberSpeedKmh()
    CardBox(modifier) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$speed", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = if (speed > 120) Color(0xFFFF6B6B) else Color.White)
            Text("km/s", fontSize = 16.sp, color = Cyan)
        }
    }
}

private fun sendMediaKey(ctx: Context, key: Int) {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
}

@Composable
private fun MediaCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }

    CardBox(modifier) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = Cyan, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Müzik", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaBtn(Icons.Rounded.SkipPrevious, 56) { sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                MediaBtn(Icons.Rounded.PlayArrow, 72, filled = true) { sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                MediaBtn(Icons.Rounded.SkipNext, 56) { sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeUp, null, modifier = Modifier.size(24.dp))
                Slider(
                    value = vol,
                    onValueChange = {
                        vol = it
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0)
                    },
                    valueRange = 0f..maxVol,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaBtn(icon: ImageVector, dp: Int, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(dp.dp)
            .clip(CircleShape)
            .background(if (filled) Cyan else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (filled) Color(0xFF04202A) else Color.White, modifier = Modifier.size((dp * 0.55f).dp))
    }
}
