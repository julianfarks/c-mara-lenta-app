package com.farks.autopilot.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.farks.autopilot.diagnostics.Snapshot
import com.farks.autopilot.models.EngineStatus
import java.util.Locale

/**
 * Panel flotante: estado del piloto, carril actual y recomendado, obstáculos, peligro, FPS de análisis
 * y botón de parada inmediata. Se coloca arriba del todo para no tapar la carretera: lo que tape
 * también lo vería el detector (la superposición aparece en la captura).
 */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var detail: TextView? = null
    private var stopButton: Button? = null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(4); y = dp(4)
    }

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.argb(205, 14, 17, 22)); cornerRadius = dp(10).toFloat()
        }
    }

    /** Panel del piloto automático con botón de parada/rearme. */
    fun showAutopilot(onEmergency: () -> Unit, onRearm: () -> Unit) = main.post {
        remove()
        val p = panel()
        status = TextView(context).apply { setTextColor(Color.WHITE); textSize = 13f; text = "Iniciando…" }
        detail = TextView(context).apply { setTextColor(Color.rgb(190, 200, 215)); textSize = 11f }
        stopButton = Button(context).apply {
            text = "■ PARAR"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(200, 30, 40))
            textSize = 13f
            minimumHeight = dp(40)
            setOnClickListener {
                if (tag == "stopped") onRearm() else onEmergency()
            }
        }
        p.addView(status); p.addView(detail); p.addView(stopButton)
        root = p
        wm.addView(p, params())
    }

    /** Un solo botón para capturar la pantalla de referencia durante la calibración. */
    fun showCalibrationButton(onCapture: () -> Unit) = main.post {
        remove()
        val p = panel()
        p.addView(TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f
            text = "Abra el juego, deje la carretera vacía\ny con su vehículo visible."
        })
        p.addView(Button(context).apply {
            text = "Capturar referencia"
            setOnClickListener { onCapture() }
        })
        root = p
        wm.addView(p, params())
    }

    fun update(s: Snapshot) = main.post {
        val st = status ?: return@post
        val d = detail ?: return@post
        val o = s.output
        val label = when {
            s.lastError != null && o == null -> "Error"
            o == null -> "Esperando imagen"
            else -> when (o.status) {
                EngineStatus.RUNNING -> "ACTIVO"
                EngineStatus.DISABLED -> "En espera (sin gestos)"
                EngineStatus.WARMING_UP -> "Calentando"
                EngineStatus.SUSPENDED_NO_CAPTURE -> "Suspendido (sin imagen)"
                EngineStatus.STALE -> "Suspendido (imagen vieja)"
                EngineStatus.EMERGENCY_STOP -> "PARADA DE EMERGENCIA"
            }
        }
        st.text = "AutoPilot: $label"
        val btn = stopButton
        if (btn != null) {
            val stopped = o?.status == EngineStatus.EMERGENCY_STOP
            btn.tag = if (stopped) "stopped" else "running"
            btn.text = if (stopped) "▶ REARMAR" else "■ PARAR"
        }
        d.text = if (o == null) (s.lastError ?: "") else String.format(
            Locale.US,
            "Carril: %s%s → %s\nObstáculos: %d  Peligro: %.0f%%\nAnálisis: %.0f/s (%.0f ms)",
            o.playerLane, if (o.playerLaneAssumed) "?" else "", o.recommendedLane ?: "-",
            o.tracks.count { it.confirmed }, o.dangerLevel * 100f, s.analysisFps, s.processingMs,
        )
    }

    /** Borde inferior del panel (0..1 de la altura de pantalla) para avisar si tapa la carretera. */
    fun bottomNormalized(screenH: Int): Float {
        val r = root ?: return 0f
        return (r.bottom.takeIf { it > 0 } ?: r.measuredHeight.takeIf { it > 0 } ?: dp(120)).toFloat() / screenH
    }

    fun remove() {
        val r = root ?: return
        try { wm.removeView(r) } catch (_: Exception) {}
        root = null
    }

    fun removeAsync() = main.post { remove() }
}
