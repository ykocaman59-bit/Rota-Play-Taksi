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

/**
 * Şoför çevrimiçiyken çalışır: Firebase'den talepleri 6 sn'de bir yoklar, konumu izler,
 * talep gelince bildirim verir, Yandex üzerinde "Yolcuyu aldım / Bitir" düğmesini gösterir.
 */
class TaxiService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var overlay: Button? = null
    private val locListener = LocationListener { l: Location ->
        TaxiStore.lat = l.latitude; TaxiStore.lon = l.longitude
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
            Thread { pollLoop() }.start()
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

    // ---- Talepleri 6 saniyede bir sorar (Firebase Spark kotasına uygun) ----
    private fun pollLoop() {
        while (running) {
            try {
                val fresh = TaxiStore.pollOnce()
                if (fresh != null) main.post { notifyRide(fresh) }
                if (TaxiStore.message.startsWith("Bağlanılamadı")) TaxiStore.message = ""
            } catch (e: ApiError) {
                TaxiStore.message = e.message ?: "Hata"
                if (e.code == 401) { main.post { TaxiStore.online = false; stopSelf() }; return }
            } catch (e: Exception) { TaxiStore.message = "Bağlanılamadı, tekrar deneniyor…" }
            try { Thread.sleep(6000) } catch (e: InterruptedException) { }
        }
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
        TaxiStore.incoming = null
        super.onDestroy()
    }
}
