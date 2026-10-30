package com.rotaplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Şoför çevrimiçiyken çalışır: sunucuya canlı bağlanır (SSE), konumu gönderir,
 * talep gelince bildirim verir, Yandex üzerinde "Yolcuyu aldım / Bitir" düğmesini gösterir.
 */
class TaxiService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var overlay: Button? = null
    private var lastSend = 0L
    private val locListener = LocationListener { l: Location ->
        TaxiStore.lat = l.latitude; TaxiStore.lon = l.longitude
        val now = System.currentTimeMillis()
        if (running && now - lastSend > 5000) { lastSend = now; TaxiStore.bg { sendLocation() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("taxi_svc", "Taksi modu", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("taxi_req", "Yolculuk talepleri", NotificationManager.IMPORTANCE_HIGH))
        }
        TaxiStore.onPhaseChanged = { updateOverlay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n: Notification = NotificationCompat.Builder(this, "taxi_svc")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("RotaPlay Taksi çevrimiçi")
            .setContentText("Yolculuk talepleri bekleniyor")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
        if (!running) {
            running = true
            startLocation()
            Thread { streamLoop() }.start()
        }
        return START_NOT_STICKY
    }

    // ---- Konum ----
    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 10f, locListener, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000L, 30f, locListener, Looper.getMainLooper())
        } catch (e: SecurityException) { TaxiStore.message = "Konum izni gerekli" }
    }
    private fun body(): JSONObject {
        val b = JSONObject()
        TaxiStore.lat?.let { b.put("lat", it) }; TaxiStore.lon?.let { b.put("lon", it) }
        return b
    }
    private fun sendLocation() {
        try { TaxiStore.call("POST", "/api/driver/${TaxiStore.driverId}/location", body()) } catch (e: Exception) { }
    }
    private fun sendOnline(on: Boolean) {
        try { TaxiStore.call("POST", "/api/driver/${TaxiStore.driverId}/online", body().put("online", on)) } catch (e: Exception) { }
    }

    // ---- Sunucu akışı ----
    private fun streamLoop() {
        while (running) {
            var c: HttpURLConnection? = null
            try {
                c = URL("$TAXI_SERVER/api/driver/${TaxiStore.driverId}/stream").openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer ${TaxiStore.apiKey}")
                c.setRequestProperty("Accept", "text/event-stream")
                c.connectTimeout = 10000
                c.readTimeout = 60000          // sunucu 20 sn'de bir kalp atışı yollar
                val code = c.responseCode
                if (code != 200) {
                    TaxiStore.message = if (code == 403) "Hesabınız henüz onaylanmadı" else "Bağlanılamadı ($code)"
                    if (code in 401..403) { main.post { TaxiStore.online = false; stopSelf() }; return }
                } else {
                    var ev = ""
                    val rd = c.inputStream.bufferedReader()
                    while (running) {
                        val line = rd.readLine() ?: break
                        when {
                            line.startsWith("event:") -> ev = line.substring(6).trim()
                            line.startsWith("data:") -> handle(ev, line.substring(5).trim())
                            line.isEmpty() -> ev = ""
                        }
                    }
                }
            } catch (e: Exception) { /* bağlantı koptu, yeniden dene */ }
            finally { c?.disconnect() }
            if (running) try { Thread.sleep(3000) } catch (e: InterruptedException) { }
        }
    }

    private fun handle(ev: String, data: String) {
        try {
            when (ev) {
                "hello" -> { TaxiStore.message = ""; sendOnline(true) }
                "ride" -> {
                    if (TaxiStore.current != null) return
                    val r = TaxiStore.rideFrom(JSONObject(data))
                    TaxiStore.incoming = r
                    main.post { notifyRide(r) }
                }
                "ride_gone" -> {
                    val id = JSONObject(data).getString("id")
                    if (TaxiStore.incoming?.id == id) TaxiStore.incoming = null
                }
                "ride_cancelled" -> {
                    TaxiStore.current = null; TaxiStore.incoming = null
                    TaxiStore.message = "Yolcu yolculuğu iptal etti"
                    TaxiStore.changePhase("")
                }
            }
        } catch (e: Exception) { }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notifyRide(r: TaxiRide) {
        val n = NotificationCompat.Builder(this, "taxi_req")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Yeni yolculuk talebi")
            .setContentText(r.pText + (r.distKm?.let { " · $it km" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openApp()).setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
        // RotaPlay arka plandaysa öne getir (yüzen pencere izni sayesinde izinli)
        if (Settings.canDrawOverlays(this)) {
            TaxiStore.openTaxi = true
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { }
        }
    }

    // ---- Yandex'in üstünde duran "Yolcuyu aldım / Yolculuğu bitir" düğmesi ----
    private fun updateOverlay() {
        val label = when (TaxiStore.phase) {
            "to_pickup" -> "Yolcuyu aldım"
            "on_trip" -> "Yolculuğu bitir"
            else -> null
        }
        if (label == null || !Settings.canDrawOverlays(this)) { removeOverlay(); return }
        if (overlay == null) createOverlay()
        overlay?.text = label
    }

    private fun createOverlay() {
        val d = resources.displayMetrics.density
        val b = Button(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#0B1220"))
            isAllCaps = false
            setPadding((28 * d).toInt(), (14 * d).toInt(), (28 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * d; setColor(Color.parseColor("#22D3EE")) }
            setOnClickListener {
                when (TaxiStore.phase) {
                    "to_pickup" -> TaxiStore.pickedUp(applicationContext)
                    "on_trip" -> TaxiStore.requestFinish(applicationContext)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (28 * d).toInt() }
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(b, lp); overlay = b } catch (e: Exception) { }
    }

    private fun removeOverlay() {
        overlay?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { } }
        overlay = null
    }

    override fun onDestroy() {
        running = false
        TaxiStore.onPhaseChanged = null
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        removeOverlay()
        TaxiStore.online = false
        if (TaxiStore.driverId.isNotEmpty()) TaxiStore.bg { sendOnline(false) }
        super.onDestroy()
    }
}
