package com.rotaplay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Taksi sunucusunun adresi. Yayına çıkarken kendi https adresinle değiştir. */
const val TAXI_SERVER = "http://10.0.2.2:3000" // 10.0.2.2 = emülatörden bilgisayarın localhost'u

data class TaxiRide(
    val id: String, val passenger: String,
    val pLat: Double, val pLon: Double, val pText: String,
    val dLat: Double, val dLon: Double, val dText: String,
    val distKm: Double?
)

class ApiError(val code: Int, msg: String) : Exception(msg)

/** Şoför oturumu + taksi modunun ortak durumu (arayüz, servis ve yüzen düğme buradan okur). */
object TaxiStore {
    var driverId by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var name by mutableStateOf("")
    var plate by mutableStateOf("")
    var status by mutableStateOf("")            // pending | approved | blocked
    var rating by mutableStateOf<Double?>(null)
    var online by mutableStateOf(false)
    var incoming by mutableStateOf<TaxiRide?>(null)
    var current by mutableStateOf<TaxiRide?>(null)
    var phase by mutableStateOf("")             // to_pickup | on_trip | finish | rate
    var message by mutableStateOf("")
    var openTaxi by mutableStateOf(false)       // true olunca Dashboard taksi sekmesini açar

    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    var onPhaseChanged: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun changePhase(p: String) { phase = p; main.post { onPhaseChanged?.invoke() } }

    // ---- Kalıcı oturum ----
    private fun prefs(c: Context) = c.getSharedPreferences("taxi", Context.MODE_PRIVATE)
    fun load(c: Context) {
        val p = prefs(c)
        driverId = p.getString("id", "") ?: ""
        apiKey = p.getString("key", "") ?: ""
        name = p.getString("name", "") ?: ""
        plate = p.getString("plate", "") ?: ""
    }
    fun setLogin(c: Context, id: String, key: String) {
        driverId = id; apiKey = key
        prefs(c).edit().putString("id", id).putString("key", key).apply()
    }
    fun logout(c: Context) {
        driverId = ""; apiKey = ""; name = ""; plate = ""; status = ""; rating = null; online = false
        incoming = null; current = null; changePhase("")
        prefs(c).edit().clear().apply()
    }

    // ---- HTTP ----
    fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val c = URL(TAXI_SERVER + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (auth && apiKey.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) throw ApiError(code, j.optString("error", "Hata ($code)"))
            return j
        } finally { c.disconnect() }
    }
    private fun dp(path: String) = "/api/driver/$driverId$path"

    fun rideFrom(j: JSONObject): TaxiRide {
        val p = j.getJSONObject("pickup"); val d = j.getJSONObject("dest")
        val who = j.optString("passengerName", j.optJSONObject("passenger")?.optString("name") ?: "Yolcu")
        return TaxiRide(
            j.getString("id"), who,
            p.getDouble("lat"), p.getDouble("lon"), p.optString("text", "Yolcunun konumu"),
            d.getDouble("lat"), d.getDouble("lon"), d.optString("text", "Varış noktası"),
            if (j.isNull("distanceKm") || !j.has("distanceKm")) null else j.getDouble("distanceKm")
        )
    }

    fun bg(block: () -> Unit) { Thread { block() }.start() }
    private fun fail(e: Exception) { message = if (e is ApiError) (e.message ?: "Hata") else "Sunucuya bağlanılamadı" }

    /** Profil + varsa yarım kalmış yolculuk */
    fun refreshMe() {
        try {
            val j = call("GET", dp("/me"))
            name = j.optString("name"); plate = j.optString("plate"); status = j.optString("status")
            rating = if (j.isNull("rating")) null else j.getDouble("rating")
            if (current == null && !j.isNull("activeRide")) {
                val a = j.getJSONObject("activeRide")
                current = rideFrom(a)
                changePhase(if (a.getString("status") == "accepted") "to_pickup" else "on_trip")
            }
        } catch (e: Exception) { fail(e) }
    }

    // ---- Yolculuk adımları ----
    fun accept(ctx: Context, r: TaxiRide) = bg {
        try {
            call("POST", dp("/rides/${r.id}/accept"), JSONObject())
            incoming = null; current = r; message = ""
            changePhase("to_pickup")
            launchYandex(ctx, r.pLat, r.pLon)          // yolcunun konumuna rota
        } catch (e: Exception) { fail(e); incoming = null }
    }
    fun pickedUp(ctx: Context) {
        val r = current ?: return
        bg {
            try {
                call("POST", dp("/rides/${r.id}/pickup"), JSONObject())
                changePhase("on_trip")
                launchYandex(ctx, r.dLat, r.dLon)      // yolcunun gideceği adrese rota
            } catch (e: Exception) { fail(e) }
        }
    }
    /** Yolcuyu almadan önce şoför iptal edebilir; yolculuk başka şoföre gider. */
    fun cancelRide() {
        val r = current ?: return
        bg {
            try {
                call("POST", dp("/rides/${r.id}/cancel"), JSONObject())
                current = null; message = "Yolculuğu iptal ettiniz"; changePhase("")
            } catch (e: Exception) { fail(e) }
        }
    }
    fun requestFinish(ctx: Context) {
        changePhase("finish"); openTaxi = true
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    /** Taksimetrede yazan tutar sunucuya yazılır; tahmini ücret hiçbir yerde yok. */
    fun complete(fare: Double, done: (String?) -> Unit) {
        val r = current ?: return
        bg {
            try {
                call("POST", dp("/rides/${r.id}/complete"), JSONObject().put("fare", fare))
                changePhase("rate"); done(null)
            } catch (e: Exception) { done(if (e is ApiError) e.message else "Sunucuya bağlanılamadı") }
        }
    }
    fun ratePassenger(stars: Int, done: () -> Unit) {
        val r = current
        bg {
            try { if (r != null && stars > 0) call("POST", dp("/rides/${r.id}/rate"), JSONObject().put("rating", stars)) }
            catch (e: Exception) { }
            current = null; message = "Yolculuk tamamlandı"; changePhase("")
            refreshMe()
            main.post(done)
        }
    }
}

/** Yandex Navigatör, yoksa Yandex Harita ile rota çizer; ikisi de yoksa mağazaya yönlendirir. */
fun launchYandex(ctx: Context, lat: Double, lon: Double) {
    val tries = listOf(
        "ru.yandex.yandexnavi" to "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon",
        "ru.yandex.yandexmaps" to "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto"
    )
    for ((pkg, uri) in tries) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { }
    }
    TaxiStore.message = "Yandex Navigatör kurulu değil"
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=ru.yandex.yandexnavi")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}
