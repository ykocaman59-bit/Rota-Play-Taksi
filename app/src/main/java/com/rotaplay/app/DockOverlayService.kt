package com.rotaplay.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * Başka bir uygulama açıkken ekranın solunda duran yuvarlak RotaPlay düğmesi
 * (CarPlay'deki ev düğmesi gibi). Dokununca RotaPlay'e döner.
 */
class DockOverlayService : Service() {
    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view == null && Settings.canDrawOverlays(this)) addDock()
        return START_NOT_STICKY
    }

    private fun addDock() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val size = (64 * d).toInt()

        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 11, 18, 32))
                setStroke((2 * d).toInt(), Color.parseColor("#22D3EE"))
            }
            setOnClickListener {
                startActivity(
                    Intent(this@DockOverlayService, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
                stopSelf()
            }
        }

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (8 * d).toInt()
        }
        runCatching { wm.addView(button, lp) }.onSuccess { view = button }
    }

    override fun onDestroy() {
        view?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        view = null
        super.onDestroy()
    }
}
