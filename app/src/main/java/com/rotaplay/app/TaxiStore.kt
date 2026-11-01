package com.rotaplay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class TaxiRide(
    val id: String, val passenger: String,
    val pLat: Double, val pLon: Double, val pText: String,
    val dLat: Double, val dLon: Double, val dText: String,
    val distKm: Double?
)

class ApiError(val code: Int, msg: String) : Exception(msg)

/**
 * Şoför oturumu ve taksi modunun ortak durumu. Veriler Firebase (Firestore + Auth) üzerinde tutulur;
 * Google Play hizmeti gerekmesin diye Firebase'in REST arayüzü kullanılır.
 */
object TaxiStore {
    var driverId by mutableStateOf("")          // Firebase kullanıcı kimliği (uid)
    var name by mutableStateOf("")
    var plate by mutableStateOf("")
    var phone by mutableStateOf("")
    var docIdentityPath by mutableStateOf("")
    var docLicensePath by mutableStateOf("")
    var docRegFrontPath by mutableStateOf("")
    var docRegBackPath by mutableStateOf("")
    var docsStatus by mutableStateOf("missing")
    var autoCheck by mutableStateOf("not_run")
    var plateOcrResult by mutableStateOf("not_run")
    var status by mutableStateOf("")            // pending | approved | blocked
    var rating by mutableStateOf<Double?>(null)
    var online by mutableStateOf(false)
    var incoming by mutableStateOf<TaxiRide?>(null)
    var current by mutableStateOf<TaxiRide?>(null)
    var phase by mutableStateOf("")             // to_pickup | on_trip | finish | rate
    var message by mutableStateOf("")
    var openTaxi by mutableStateOf(false)
    var level by mutableStateOf(1)               // 1-5: puan ve sefer sayısına göre
    var radiusKm by mutableStateOf(3.0)          // seviyeye göre talep alma yarıçapı

    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    var onPhaseChanged: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val rejected = HashSet<String>()
    private var lastLocPush = 0L

    private var authUid = ""                 // giriş yapan kullanıcının kimliği (profil yazılana kadar ekrana yansıtılmaz)
    private var idToken = ""
    private var refreshToken = ""
    private var tokenExp = 0L

    fun changePhase(p: String) { phase = p; main.post { onPhaseChanged?.invoke() } }
    fun bg(block: () -> Unit) { Thread { block() }.start() }

    // ---------- Kalıcı oturum ----------
    private fun prefs(c: Context) = c.getSharedPreferences("taxi", Context.MODE_PRIVATE)
    fun load(c: Context) {
        val p = prefs(c)
        driverId = p.getString("uid", "") ?: ""
        refreshToken = p.getString("refresh", "") ?: ""
        name = p.getString("name", "") ?: ""
        plate = p.getString("plate", "") ?: ""
        phone = p.getString("phone", "") ?: ""
        tokenExp = 0L
    }
    private fun saveSession(c: Context) {
        prefs(c).edit().putString("uid", driverId).putString("refresh", refreshToken)
            .putString("name", name).putString("plate", plate).putString("phone", phone).apply()
    }
    fun logout(c: Context) {
        driverId = ""; idToken = ""; refreshToken = ""; name = ""; plate = ""; phone = ""
        docIdentityPath = ""; docLicensePath = ""; docRegFrontPath = ""; docRegBackPath = ""
        docsStatus = "missing"; autoCheck = "not_run"; plateOcrResult = "not_run"
        status = ""; rating = null; online = false; incoming = null; current = null
        rejected.clear(); changePhase("")
        prefs(c).edit().clear().apply()
    }

    // ---------- HTTP ----------
    private fun friendly(text: String, code: Int): String {
        val raw = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { "" }
        return when {
            raw.startsWith("EMAIL_EXISTS") -> "Bu telefon zaten kayıtlı"
            raw.startsWith("INVALID_LOGIN_CREDENTIALS") || raw.startsWith("INVALID_PASSWORD") || raw.startsWith("EMAIL_NOT_FOUND") -> "Telefon veya şifre hatalı"
            raw.startsWith("WEAK_PASSWORD") -> "Şifre en az 6 karakter olmalı"
            raw.startsWith("OPERATION_NOT_ALLOWED") -> "Firebase'de E-posta/Şifre girişi açık değil"
            raw.startsWith("TOO_MANY_ATTEMPTS") -> "Çok fazla deneme, biraz bekleyin"
            code == 403 -> "İzin yok (403)" + (if (raw.isNotEmpty()) ": $raw" else "")
            else -> if (raw.isNotEmpty()) "$raw ($code)" else "Hata ($code)"
        }
    }

    private fun httpText(method: String, url: String, body: String?, token: String?,
                         contentType: String = "application/json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw ApiError(code, friendly(text, code))
            return text
        } finally { c.disconnect() }
    }

    // ---------- Giriş / kayıt (Firebase Authentication) ----------
    private fun emailOf(phone: String) = "p" + phone.filter { it.isDigit() } + "@rotaplay-taksi.app"

    private fun authCall(endpoint: String, phone: String, pass: String) {
        val body = JSONObject().put("email", emailOf(phone)).put("password", pass).put("returnSecureToken", true)
        val j = JSONObject(httpText("POST", "https://identitytoolkit.googleapis.com/v1/accounts:$endpoint?key=$FB_API_KEY", body.toString(), null))
        authUid = j.getString("localId")
        idToken = j.getString("idToken")
        refreshToken = j.getString("refreshToken")
        tokenExp = System.currentTimeMillis() + (j.getString("expiresIn").toLong() - 60) * 1000
    }

    private fun token(): String {
        if (idToken.isEmpty() || System.currentTimeMillis() > tokenExp) {
            if (refreshToken.isEmpty()) throw ApiError(401, "Oturum süresi doldu, tekrar giriş yapın")
            val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
            val j = JSONObject(httpText("POST", "https://securetoken.googleapis.com/v1/token?key=$FB_API_KEY", form, null, "application/x-www-form-urlencoded"))
            idToken = j.getString("id_token")
            refreshToken = j.getString("refresh_token")
            tokenExp = System.currentTimeMillis() + (j.getString("expires_in").toLong() - 60) * 1000
        }
        return idToken
    }

    /** Şoför profil belgesini (onay bekliyor olarak) yazar. Kayıtta ve "Kaydı yeniden dene" düğmesinde kullanılır. */
    fun createProfile() {
        val uid = driverId.ifEmpty { authUid }
        fsWrite("drivers/$uid", mapOf(
            "name" to name, "phone" to phone, "plate" to plate,
            "status" to "pending", "createdAt" to System.currentTimeMillis(),
            "identityPath" to "", "licensePath" to "", "registrationFrontPath" to "",
            "registrationBackPath" to "", "docsStatus" to "missing", "autoCheck" to "not_run", "plateOcrResult" to "not_run"))
    }

    fun register(c: Context, n: String, ph: String, pl: String, pass: String) {
        if (n.isBlank() || pl.isBlank() || ph.filter { it.isDigit() }.length < 10) throw ApiError(400, "Ad, plaka ve geçerli bir telefon girin")
        authCall("signUp", ph, pass)
        name = n; plate = pl.uppercase(); phone = ph
        var ok = true
        try {
            createProfile()                      // önce şoför belgesi yazılır...
        } catch (e: Exception) {
            ok = false
            message = "Hesap açıldı ama şoför kaydı yazılamadı: " + (if (e is ApiError) e.message else "bağlantı hatası")
        }
        driverId = authUid                       // ...sonra ekran taksi sayfasına geçer
        saveSession(c)
        if (!ok) return
        message = ""
        track("kayit_oldu", plate)
        refreshMe()
    }


    /**
     * Belgeyi Firebase Storage'a özel (private) yükler; dosya yolu Firestore profilinde tutulur.
     * docType: identity | license | registrationFront | registrationBack
     */
    fun uploadDriverDocument(c: Context, uri: Uri, docType: String) {
        val allowed = setOf("identity", "license", "registrationFront", "registrationBack")
        if (docType !in allowed) throw ApiError(400, "Geçersiz belge türü")
        val uid = driverId.ifEmpty { authUid }
        if (uid.isBlank()) throw ApiError(401, "Önce giriş yapın")
        var thisOcr = "not_applicable"
        if (docType == "registrationFront" || docType == "registrationBack") {
            try {
                val image = InputImage.fromFilePath(c, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val recognized = try { Tasks.await(recognizer.process(image), 25, TimeUnit.SECONDS).text } finally { recognizer.close() }
                val compactText = recognized.uppercase().replace(Regex("[^0-9A-Z]"), "")
                val expected = plate.uppercase().replace(Regex("[^0-9A-Z]"), "")
                val candidates = Regex("[0-9]{2}[A-Z]{1,3}[0-9]{2,4}").findAll(compactText).map { it.value }.toList()
                thisOcr = when {
                    expected.isNotBlank() && compactText.contains(expected) -> "match"
                    candidates.isNotEmpty() -> "mismatch"
                    else -> "unreadable"
                }
            } catch (_: Exception) { thisOcr = "unreadable" }
        }
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiError(400, "Dosya okunamadı")
        if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) throw ApiError(400, "Dosya boş veya 12 MB sınırını aşıyor")
        val mime = c.contentResolver.getType(uri) ?: "image/jpeg"
        if (!mime.startsWith("image/")) throw ApiError(400, "Yalnızca fotoğraf yükleyebilirsiniz")
        val objectPath = "driver-documents/$uid/$docType"
        val bucket = "rotaplay-taksi.firebasestorage.app"
        val encodedPath = URLEncoder.encode(objectPath, "UTF-8").replace("+", "%20")
        val conn = URL("https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=media&name=$encodedPath").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${token()}")
            conn.setRequestProperty("Content-Type", mime)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw ApiError(code, "Belge yüklenemedi: $response")
        } finally { conn.disconnect() }

        val field = when (docType) {
            "identity" -> "identityPath"
            "license" -> "licensePath"
            "registrationFront" -> "registrationFrontPath"
            else -> "registrationBackPath"
        }
        val values = mutableMapOf<String, Any?>(field to objectPath)
        val paths = mapOf(
            "identityPath" to if (docType == "identity") objectPath else docIdentityPath,
            "licensePath" to if (docType == "license") objectPath else docLicensePath,
            "registrationFrontPath" to if (docType == "registrationFront") objectPath else docRegFrontPath,
            "registrationBackPath" to if (docType == "registrationBack") objectPath else docRegBackPath
        )
        val complete = paths.values.all { it.isNotBlank() }
        val normalizedPlate = plate.uppercase().replace(" ", "")
        val plateFormatOk = Regex("^[0-9]{2}[A-Z]{1,3}[0-9]{2,4}$").matches(normalizedPlate)
        val previousOcr = plateOcrResult
        val combinedOcr = when {
            thisOcr == "match" || previousOcr == "match" -> "match"
            thisOcr == "mismatch" && previousOcr == "mismatch" -> "mismatch"
            thisOcr == "unreadable" || previousOcr == "unreadable" -> "unreadable"
            else -> previousOcr
        }
        if (docType == "registrationFront" || docType == "registrationBack") values["plateOcrResult"] = combinedOcr
        val check = when {
            !complete -> "incomplete"
            !plateFormatOk || combinedOcr == "mismatch" -> "suspicious"
            combinedOcr == "match" -> "review_required"
            else -> "manual_review"
        }
        values["docsStatus"] = if (complete) "submitted" else "incomplete"
        values["autoCheck"] = check
        fsWrite("drivers/$uid", values)
        when (docType) {
            "identity" -> docIdentityPath = objectPath
            "license" -> docLicensePath = objectPath
            "registrationFront" -> docRegFrontPath = objectPath
            "registrationBack" -> docRegBackPath = objectPath
        }
        docsStatus = values["docsStatus"].toString()
        autoCheck = check
        plateOcrResult = combinedOcr
    }

    fun login(c: Context, ph: String, pass: String) {
        authCall("signInWithPassword", ph, pass)
        driverId = authUid
        phone = ph
        saveSession(c)
        refreshMe(c)
        track("giris_yapti")
    }

    // ---------- Firestore (REST) ----------
    private val FS get() = "https://firestore.googleapis.com/v1/projects/$FB_PROJECT_ID/databases/(default)/documents"

    private fun enc(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int -> JSONObject().put("integerValue", v.toString())
        is Long -> JSONObject().put("integerValue", v.toString())
        is Double -> JSONObject().put("doubleValue", v)
        is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(v.map { enc(it) })))
        else -> JSONObject().put("stringValue", v.toString())
    }
    private fun encFields(m: Map<String, Any?>): JSONObject { val o = JSONObject(); for ((k, v) in m) o.put(k, enc(v)); return o }

    private fun dec(v: JSONObject): Any = when {
        v.has("stringValue") -> v.getString("stringValue")
        v.has("integerValue") -> v.getString("integerValue").toLong()
        v.has("doubleValue") -> v.getDouble("doubleValue")
        v.has("booleanValue") -> v.getBoolean("booleanValue")
        v.has("mapValue") -> decFields(v.getJSONObject("mapValue").optJSONObject("fields"))
        v.has("arrayValue") -> {
            val out = JSONArray()
            val vals = v.getJSONObject("arrayValue").optJSONArray("values")
            if (vals != null) for (i in 0 until vals.length()) out.put(dec(vals.getJSONObject(i)))
            out
        }
        else -> JSONObject.NULL
    }
    private fun decFields(f: JSONObject?): JSONObject {
        val o = JSONObject()
        if (f != null) for (k in f.keys()) o.put(k, dec(f.getJSONObject(k)))
        return o
    }
    /** Firestore belgesini düz JSON'a çevirir; "_id" ve "_ut" (güncelleme zamanı) alanları eklenir. */
    private fun docJson(d: JSONObject): JSONObject {
        val o = decFields(d.optJSONObject("fields"))
        o.put("_id", d.optString("name").substringAfterLast('/'))
        o.put("_ut", d.optString("updateTime"))
        return o
    }

    private fun fsGet(path: String): JSONObject? = try {
        docJson(JSONObject(httpText("GET", "$FS/$path", null, token())))
    } catch (e: ApiError) { if (e.code == 404) null else throw e }

    private fun writeObj(path: String, fields: Map<String, Any?>, updateTime: String? = null, mustNotExist: Boolean = false): JSONObject {
        val upd = JSONObject()
            .put("name", "projects/$FB_PROJECT_ID/databases/(default)/documents/$path")
            .put("fields", encFields(fields))
        val w = JSONObject().put("update", upd)
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray(fields.keys.toList())))
        if (mustNotExist) w.put("currentDocument", JSONObject().put("exists", false))
        else if (!updateTime.isNullOrEmpty()) w.put("currentDocument", JSONObject().put("updateTime", updateTime))
        return w
    }

    /** Birden fazla yazmayı tek işlemde yapar (hepsi olur ya da hiçbiri). */
    private fun fsCommit(writes: List<JSONObject>) {
        val arr = JSONArray()
        for (w in writes) arr.put(w)
        httpText("POST", "$FS:commit", JSONObject().put("writes", arr).toString(), token())
    }

    /** Verilen alanları yazar (belge yoksa oluşturur). updateTime verilirse, belge o andan beri değiştiyse yazma reddedilir. */
    private fun fsWrite(path: String, fields: Map<String, Any?>, updateTime: String? = null) =
        fsCommit(listOf(writeObj(path, fields, updateTime)))

    private fun fsQuery(coll: String, eq: List<Pair<String, String>>, limit: Int = 20): List<JSONObject> {
        val filters = JSONArray()
        for ((f, v) in eq) filters.put(JSONObject().put("fieldFilter", JSONObject()
            .put("field", JSONObject().put("fieldPath", f)).put("op", "EQUAL")
            .put("value", JSONObject().put("stringValue", v))))
        val where = if (filters.length() == 1) filters.getJSONObject(0)
        else JSONObject().put("compositeFilter", JSONObject().put("op", "AND").put("filters", filters))
        val sq = JSONObject().put("from", JSONArray().put(JSONObject().put("collectionId", coll)))
            .put("where", where).put("limit", limit)
        val arr = JSONArray(httpText("POST", "$FS:runQuery", JSONObject().put("structuredQuery", sq).toString(), token()))
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.getJSONObject(i).optJSONObject("document")?.let { out.add(docJson(it)) }
        return out
    }

    private fun km(a: Double, b: Double, c: Double, d: Double): Double {
        val r = Math.PI / 180
        val h = sin((c - a) * r / 2).pow(2) + cos(a * r) * cos(c * r) * sin((d - b) * r / 2).pow(2)
        return 2 * 6371 * asin(sqrt(h))
    }

    fun rideFrom(j: JSONObject): TaxiRide {
        val p = j.getJSONObject("pickup"); val d = j.getJSONObject("dest")
        val la = lat; val lo = lon
        val dist = if (la != null && lo != null) Math.round(km(la, lo, p.getDouble("lat"), p.getDouble("lon")) * 10) / 10.0 else null
        return TaxiRide(
            j.getString("_id"), j.optString("passengerName", "Yolcu"),
            p.getDouble("lat"), p.getDouble("lon"), p.optString("text", "Yolcunun konumu"),
            d.getDouble("lat"), d.getDouble("lon"), d.optString("text", "Varış noktası"), dist
        )
    }

    private fun fail(e: Exception) { message = if (e is ApiError) (e.message ?: "Hata") else "Bağlanılamadı, internetinizi kontrol edin" }

    /** Seviye eşikleri firestore.rules içindeki levelRadius ile aynı olmalıdır. */
    private fun applyLevel(count: Long, avg: Double) {
        val (lv, r) = when {
            count >= 100 && avg >= 4.8 -> 5 to 15.0
            count >= 60 && avg >= 4.6 -> 4 to 12.0
            count >= 30 && avg >= 4.3 -> 3 to 8.0
            count >= 10 && avg >= 4.0 -> 2 to 5.0
            else -> 1 to 3.0
        }
        level = lv; radiusKm = r
    }

    // ---------- Profil ----------
    fun refreshMe(c: Context? = null) {
        try {
            val d = fsGet("drivers/$driverId")
            if (d == null) { status = ""; message = "Şoför kaydı bulunamadı"; return }
            name = d.optString("name"); plate = d.optString("plate"); phone = d.optString("phone"); status = d.optString("status")
            docIdentityPath = d.optString("identityPath")
            docLicensePath = d.optString("licensePath")
            docRegFrontPath = d.optString("registrationFrontPath")
            docRegBackPath = d.optString("registrationBackPath")
            docsStatus = d.optString("docsStatus", "missing")
            autoCheck = d.optString("autoCheck", "not_run")
            plateOcrResult = d.optString("plateOcrResult", "not_run")
            if (message.startsWith("Şoför kaydı bulunamadı")) message = ""
            if (c != null) saveSession(c)
            if (status != "approved") return
            val st = fsGet("driverStats/$driverId")
            if (st != null) {
                val avg = st.optDouble("avg", 0.0)
                rating = Math.round(avg * 10) / 10.0
                applyLevel(st.optLong("count", 0L), avg)
            } else { rating = null; applyLevel(0L, 0.0) }
            if (current == null) {
                var act = fsQuery("rides", listOf("driverUid" to driverId, "status" to "accepted"), 1).firstOrNull()
                var ph = "to_pickup"
                if (act == null) { act = fsQuery("rides", listOf("driverUid" to driverId, "status" to "picked_up"), 1).firstOrNull(); ph = "on_trip" }
                if (act != null) { current = rideFrom(act); changePhase(ph) }
            }
        } catch (e: Exception) { fail(e) }
    }

    // ---------- Talep yoklama (servis 6 sn'de bir çağırır) ----------
    /** Yeni bir talep bulunduysa onu döndürür (bildirim için). */
    fun pollOnce(): TaxiRide? {
        val cur = current
        if (cur != null) {
            val d = fsGet("rides/${cur.id}")
            if (d != null && d.optString("status") == "cancelled") {
                current = null; message = "Yolcu yolculuğu iptal etti"; changePhase("")
            } else if (d != null && (phase == "to_pickup" || phase == "on_trip")) pushLocation(cur.id)
            return null
        }
        val now = System.currentTimeMillis()
        val la = lat; val lo = lon
        if (la == null || lo == null) { message = "Konum bekleniyor (GPS)…"; return null }
        if (message.startsWith("Konum bekleniyor")) message = ""
        // Mesafe kuralı: şoförün seviyesine göre 3-15 km. Yarıçapın dışındaki şoför talebi hiç görmez.
        fun distOf(r: JSONObject): Double {
            val p = r.getJSONObject("pickup")
            return km(la, lo, p.getDouble("lat"), p.getDouble("lon"))
        }
        val found = fsQuery("rides", listOf("status" to "searching"), 20).filter { r ->
            if (r.optJSONObject("pickup") == null) return@filter false
            val declined = r.optJSONArray("declined")
            var mine = false
            if (declined != null) for (i in 0 until declined.length()) if (declined.optString(i) == driverId) mine = true
            val age = now - r.optLong("createdAt", 0L)
            val d = distOf(r)
            !mine && !rejected.contains(r.optString("_id")) && age < 90000 && d <= radiusKm
        }
        val inc = incoming
        if (inc != null && found.none { it.optString("_id") == inc.id }) incoming = null
        if (incoming == null && found.isNotEmpty()) {
            val r = rideFrom(found.minByOrNull { distOf(it) }!!)
            incoming = r
            return r
        }
        return null
    }

    private fun pushLocation(rideId: String) {
        val la = lat; val lo = lon
        val now = System.currentTimeMillis()
        if (la == null || lo == null || now - lastLocPush < 10000) return
        lastLocPush = now
        try { fsWrite("rides/$rideId", mapOf("driverLat" to la, "driverLon" to lo)) } catch (e: Exception) { }
    }

    // ---------- Yolculuk adımları ----------
    fun reject(r: TaxiRide) { rejected.add(r.id); incoming = null; track("talep_reddet", r.id) }

    fun accept(ctx: Context, r: TaxiRide) = bg {
        try {
            val la = lat; val lo = lon
            if (la == null || lo == null) throw ApiError(400, "Konumunuz alınamadı, GPS'in açık olduğundan emin olun")
            val d = fsGet("rides/${r.id}") ?: throw ApiError(404, "Yolculuk bulunamadı")
            if (d.optString("status") != "searching") throw ApiError(409, "Bu yolculuğu başka şoför aldı")
            // Belge okuduğumuz andan beri değiştiyse yazma reddedilir: talebi ilk yazan şoför alır
            try {
                fsWrite("rides/${r.id}", mapOf(
                    "status" to "accepted", "driverUid" to driverId, "driverName" to name, "driverPlate" to plate,
                    "driverPhone" to phone, "acceptedAt" to System.currentTimeMillis(), "driverCancelled" to false,
                    "driverLat" to la, "driverLon" to lo
                ), d.optString("_ut"))
            } catch (e: ApiError) { throw ApiError(409, "Bu yolculuğu başka şoför aldı, iptal edildi veya 5 km dışında") }
            incoming = null; current = r; message = ""
            changePhase("to_pickup")
            track("talep_kabul", "${r.pText} → ${r.dText}")
            launchYandex(ctx, r.pLat, r.pLon)
        } catch (e: Exception) { fail(e); incoming = null }
    }

    fun pickedUp(ctx: Context) {
        val r = current ?: return
        bg {
            try {
                fsWrite("rides/${r.id}", mapOf("status" to "picked_up", "pickedAt" to System.currentTimeMillis()))
                changePhase("on_trip")
                track("yolcu_alindi", r.id)
                launchYandex(ctx, r.dLat, r.dLon)
            } catch (e: Exception) { fail(e) }
        }
    }

    /** Yolcuyu almadan önce şoför iptal edebilir; talep başka şoförlere yeniden açılır. */
    fun cancelRide() {
        val r = current ?: return
        bg {
            try {
                val d = fsGet("rides/${r.id}")
                val declined = ArrayList<String>()
                val arr = d?.optJSONArray("declined")
                if (arr != null) for (i in 0 until arr.length()) declined.add(arr.getString(i))
                if (!declined.contains(driverId)) declined.add(driverId)
                fsWrite("rides/${r.id}", mapOf(
                    "status" to "searching", "driverUid" to null, "driverName" to "", "driverPlate" to "", "driverPhone" to "",
                    "driverCancelled" to true, "declined" to declined, "createdAt" to System.currentTimeMillis(),
                    "driverLat" to null, "driverLon" to null
                ))
                track("sofor_iptal", r.id)
                current = null; message = "Yolculuğu iptal ettiniz"; changePhase("")
            } catch (e: Exception) { fail(e) }
        }
    }

    fun requestFinish(ctx: Context) {
        changePhase("finish"); openTaxi = true
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Taksimetrede yazan tutar kaydedilir; tahmini ücret hiçbir yerde yok. */
    fun complete(fare: Double, done: (String?) -> Unit) {
        val r = current ?: return
        bg {
            try {
                fsWrite("rides/${r.id}", mapOf("status" to "completed", "fare" to fare, "doneAt" to System.currentTimeMillis()))
                track("yolculuk_bitti", "$fare TL")
                changePhase("rate"); done(null)
            } catch (e: Exception) { done(if (e is ApiError) e.message else "Bağlanılamadı") }
        }
    }

    /** Puan hem yolculuğa hem yolcunun genel puan özetine (passengerStats) tek işlemde yazılır. */
    private fun writePassengerRating(rideId: String, stars: Int) {
        val ride = fsGet("rides/$rideId") ?: throw ApiError(404, "Yolculuk bulunamadı")
        val pUid = ride.optString("passengerUid")
        val old = fsGet("passengerStats/$pUid")
        val sum = (old?.optLong("sum", 0L) ?: 0L) + stars
        val count = (old?.optLong("count", 0L) ?: 0L) + 1
        val stat = mapOf("sum" to sum, "count" to count, "avg" to sum.toDouble() / count,
            "lastRide" to rideId, "name" to ride.optString("passengerName", "Yolcu"))
        fsCommit(listOf(
            writeObj("rides/$rideId", mapOf("passengerRating" to stars), ride.optString("_ut")),
            writeObj("passengerStats/$pUid", stat, old?.optString("_ut"), old == null)
        ))
    }

    fun ratePassenger(stars: Int, done: () -> Unit) {
        val r = current
        bg {
            var failed = ""
            try {
                if (r != null && stars > 0) {
                    var attempt = 0
                    while (true) {                       // aynı anda başka puan geldiyse yeniden dener
                        try { writePassengerRating(r.id, stars); break }
                        catch (e: ApiError) { attempt++; if (attempt >= 3) throw e }
                    }
                    track("yolcuyu_puanladi", "$stars yıldız")
                }
            } catch (e: Exception) { failed = "Puan kaydedilemedi: " + (e.message ?: "bağlantı hatası") }
            current = null; message = if (failed.isEmpty()) "Yolculuk tamamlandı" else failed
            changePhase("")
            refreshMe()
            main.post(done)
        }
    }

    /** Uygulamadaki bir işlemi hareket kaydına (events) yazar. */
    fun track(name: String, detail: String = "") {
        if (driverId.isEmpty()) return
        bg {
            try {
                val body = JSONObject().put("fields", encFields(mapOf(
                    "uid" to driverId, "who" to "app", "name" to name, "detail" to detail.take(200), "t" to System.currentTimeMillis())))
                httpText("POST", "$FS/events", body.toString(), token())
            } catch (e: Exception) { }
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
