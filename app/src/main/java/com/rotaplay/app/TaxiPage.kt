package com.rotaplay.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject

@Composable
fun TaxiPage(onHome: () -> Unit) {
    if (TaxiStore.driverId.isEmpty()) TaxiAuth() else TaxiMain(onHome)
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text,
                  secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CardColor).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content
    )
}

// ---------- Kayıt / giriş ----------
@Composable
private fun TaxiAuth() {
    val ctx = LocalContext.current.applicationContext
    var register by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var plate by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (register) "Şoför kaydı" else "Şoför girişi", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (register) {
            Text("Kayıttan sonra hesabınız onaylanınca talep alabilirsiniz.", color = Color.White.copy(alpha = 0.7f))
            Field("Ad soyad", name) { name = it }
            Field("Plaka", plate) { plate = it }
        }
        Field("Telefon", phone, KeyboardType.Phone) { phone = it }
        Field("Şifre (en az 6 karakter)", pass, secret = true) { pass = it }
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B5E))
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
            busy = true; err = ""
            TaxiStore.bg {
                try {
                    val b = JSONObject().put("phone", phone.trim()).put("password", pass)
                    val r = if (register)
                        TaxiStore.call("POST", "/api/driver/register", b.put("name", name.trim()).put("plate", plate.trim()), false)
                    else TaxiStore.call("POST", "/api/driver/login", b, false)
                    TaxiStore.setLogin(ctx, r.getString("id"), r.getString("apiKey"))
                    TaxiStore.refreshMe()
                } catch (e: ApiError) { err = e.message ?: "Hata" }
                catch (e: Exception) { err = "Sunucuya bağlanılamadı" }
                busy = false
            }
        }) { Text(if (busy) "Bekleyin…" else if (register) "Kayıt ol" else "Giriş yap", fontSize = 17.sp) }
        OutlinedButton(onClick = { register = !register; err = "" }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "Zaten hesabım var" else "Yeni kayıt oluştur")
        }
    }
}

// ---------- Ana taksi ekranı ----------
private fun hasLocation(c: Context) =
    ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun TaxiMain(onHome: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val s = TaxiStore

    LaunchedEffect(Unit) { s.bg { s.refreshMe() } }

    fun goOnline() {
        when {
            s.status != "approved" -> s.message = "Hesabınız henüz onaylanmadı"
            !Settings.canDrawOverlays(ctx) -> {
                s.message = "Yandex'in üstünde düğme gösterebilmek için izin verin, sonra tekrar basın"
                open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            else -> {
                s.message = ""; s.online = true
                ContextCompat.startForegroundService(ctx, Intent(ctx, TaxiService::class.java))
            }
        }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocation(ctx)) goOnline()
        else s.message = "Konum izni olmadan talep alınamaz"
    }
    fun toggle() {
        if (s.online) { ctx.stopService(Intent(ctx, TaxiService::class.java)); s.online = false; return }
        if (!hasLocation(ctx)) {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.launch(list.toTypedArray())
        } else goOnline()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Taksi", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOf(s.name, s.plate).filter { it.isNotBlank() }.joinToString("  ") +
                        (s.rating?.let { "  ★ $it" } ?: ""),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            if (!s.online && s.current == null)
                Text("Çıkış", color = Cyan, modifier = Modifier.clickable { s.logout(app) }.padding(12.dp))
        }
        if (s.message.isNotEmpty()) Text(s.message, color = Color(0xFFFFC857))

        val cur = s.current
        val inc = s.incoming
        when {
            s.status != "approved" -> Card {
                Text(if (s.status == "blocked") "Hesabınız askıya alındı." else "Başvurunuz onay bekliyor.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Onaylandığında çevrimiçi olup talep alabilirsiniz.", color = Color.White.copy(alpha = 0.7f))
                OutlinedButton(onClick = { s.bg { s.refreshMe() } }) { Text("Durumu yenile") }
            }

            cur != null -> ActiveRide(cur, onHome)

            inc != null -> Card {
                Text("Yeni yolculuk talebi", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Text("Yolcu: ${inc.passenger}")
                Text("Alınacak yer: ${inc.pText}")
                inc.distKm?.let { Text("Size uzaklık: $it km") }
                Text("Varış: ${inc.dText}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { s.accept(app, inc) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Kabul et", fontSize = 18.sp) }
                    OutlinedButton(onClick = { s.incoming = null }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Reddet", fontSize = 18.sp) }
                }
            }

            else -> Card {
                Text(if (s.online) "Çevrimiçisiniz" else "Çevrimdışısınız", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (s.online) "Talepler gelince bildirim alırsınız. Kabul edince Yandex otomatik açılır."
                    else "Talep almak için çevrimiçi olun.",
                    color = Color.White.copy(alpha = 0.7f)
                )
                Button(onClick = { toggle() }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                    Text(if (s.online) "Çevrimdışı ol" else "Çevrimiçi ol", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun ActiveRide(r: TaxiRide, onHome: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val s = TaxiStore
    var fare by rememberSaveable { mutableStateOf("") }
    var stars by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf("") }
    var confirmCancel by remember { mutableStateOf(false) }

    Card {
        when (s.phase) {
            "to_pickup" -> {
                Text("Yolcuya gidiyorsunuz", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Text("${r.passenger} · ${r.pText}")
                OutlinedButton(onClick = { launchYandex(ctx, r.pLat, r.pLon) }, modifier = Modifier.fillMaxWidth()) { Text("Yandex'te aç") }
                Button(onClick = { s.pickedUp(ctx) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Yolcuyu aldım", fontSize = 18.sp) }
                OutlinedButton(onClick = { confirmCancel = !confirmCancel }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (confirmCancel) "Vazgeç" else "Yolculuğu iptal et")
                }
                if (confirmCancel) Button(
                    onClick = { s.cancelRide() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Evet, iptal et", fontSize = 17.sp) }
            }
            "on_trip" -> {
                Text("Yolculuk sürüyor", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Text("Varış: ${r.dText}")
                OutlinedButton(onClick = { launchYandex(ctx, r.dLat, r.dLon) }, modifier = Modifier.fillMaxWidth()) { Text("Yandex'te aç") }
                Button(onClick = { s.requestFinish(ctx) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Yolculuğu bitir", fontSize = 18.sp) }
            }
            "finish" -> {
                Text("Taksimetrede yazan tutar", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Text("Ödeme taksimetre tutarı üzerinden alınır.", color = Color.White.copy(alpha = 0.7f))
                Field("Tutar (₺)", fare, KeyboardType.Decimal) { fare = it }
                if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B5E))
                Button(modifier = Modifier.fillMaxWidth().height(56.dp), onClick = {
                    val f = fare.replace(',', '.').toDoubleOrNull()
                    if (f == null || f < 0) err = "Taksimetredeki tutarı girin"
                    else { err = ""; s.complete(f) { e -> if (e != null) err = e } }
                }) { Text("Yolculuğu tamamla", fontSize = 18.sp) }
            }
            "rate" -> {
                Text("Yolcuyu puanla", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 1..5) Text(
                        "★", fontSize = 44.sp,
                        color = if (n <= stars) Cyan else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.clickable { stars = n }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { s.ratePassenger(stars, onHome) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Gönder") }
                    OutlinedButton(onClick = { s.ratePassenger(0, onHome) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Atla") }
                }
            }
        }
    }
}
