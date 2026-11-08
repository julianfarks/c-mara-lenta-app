package com.farks.autopilot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.ProfileStore
import com.farks.autopilot.diagnostics.AutopilotState
import com.farks.autopilot.diagnostics.Snapshot
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneState
import com.farks.autopilot.models.LogLevel
import com.farks.autopilot.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dibuja la última captura con carriles, estado por carril, detecciones, seguimientos y TTC. */
class DiagnosticsView(context: Context) : View(context) {
    var snapshot: Snapshot = Snapshot()
    var profile: GameProfile? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val b = AutopilotState.lastFrame
        val height = if (b == null) width / 2 else (width.toLong() * b.height / b.width).toInt()
        setMeasuredDimension(width, height.coerceAtMost((resources.displayMetrics.heightPixels * 0.62f).toInt()))
    }

    private fun stateColor(s: LaneState) = when (s) {
        LaneState.FREE -> Color.argb(70, 43, 217, 124)
        LaneState.OCCUPIED -> Color.argb(90, 229, 72, 77)
        LaneState.POSSIBLE_DANGER -> Color.argb(90, 245, 165, 36)
        LaneState.IMMINENT_DANGER -> Color.argb(140, 255, 0, 40)
        LaneState.UNKNOWN -> Color.argb(70, 150, 150, 150)
    }

    override fun onDraw(c: Canvas) {
        val bmp = AutopilotState.lastFrame
        if (bmp == null) {
            txt.textSize = 30f
            c.drawText("Sin imagen. Inicie el piloto y vuelva aquí.", 20f, height / 2f, txt)
            return
        }
        // Se mantiene la proporción de la captura (puede recortarse por abajo si es muy alta).
        val scale = width.toFloat() / bmp.width
        c.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), bmp.height * scale), null)
        val sw = width.toFloat()
        val sh = bmp.height * scale
        val p = profile ?: return
        val out = snapshot.output

        // Carriles
        for (lane in Lane.entries) {
            val st = out?.lanes?.get(lane)?.state ?: LaneState.UNKNOWN
            fill.color = stateColor(st)
            val y0 = p.road.farY
            val y1 = p.playerBox.bottom
            val path = Path().apply {
                moveTo(p.road.laneLeftX(lane, y0) * sw, y0 * sh)
                lineTo(p.road.laneRightX(lane, y0) * sw, y0 * sh)
                lineTo(p.road.laneRightX(lane, y1) * sw, y1 * sh)
                lineTo(p.road.laneLeftX(lane, y1) * sw, y1 * sh)
                close()
            }
            c.drawPath(path, fill)
        }

        // Detecciones crudas (amarillo)
        stroke.color = Color.YELLOW
        for (d in snapshot.rawDetections) {
            c.drawRect(d.box.left * sw, d.box.top * sh, d.box.right * sw, d.box.bottom * sh, stroke)
        }
        // Seguimientos: verde confirmados, naranja provisionales; trayectoria hasta la línea de colisión
        out?.tracks?.forEach { t ->
            stroke.color = if (t.confirmed) Color.rgb(43, 217, 124) else Color.rgb(245, 165, 36)
            c.drawRect(t.box.left * sw, t.box.top * sh, t.box.right * sw, t.box.bottom * sh, stroke)
            val cx = t.box.centerX * sw
            c.drawLine(cx, t.box.bottom * sh, cx, p.collisionY * sh, stroke)
            val ttc = if (t.ttcSec.isFinite() && t.ttcSec < 99f) "%.2fs".format(Locale.US, t.ttcSec) else "-"
            txt.textSize = 24f
            c.drawText("#${t.id} $ttc${if (t.coasting) " ~" else ""}", t.box.left * sw, t.box.top * sh - 6f, txt)
        }
        snapshot.detector?.player?.diag?.let { PlayerDiagDraw.draw(c, it, sw, sh) }
        // Línea de colisión y jugador
        stroke.color = Color.CYAN
        val ly = p.collisionY * sh
        c.drawLine(0f, ly, sw, ly, stroke)
        out?.let {
            val pb = p.playerBoxAt(it.playerLane)
            stroke.color = Color.WHITE
            c.drawRect(pb.left * sw, pb.top * sh, pb.right * sw, pb.bottom * sh, stroke)
        }
    }
}

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var diag: DiagnosticsView
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var errorsOnly = false
    private var store: ProfileStore? = null
    private var pkg: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        val col = Ui.column(this)
        col.addView(Ui.title(this, "Diagnóstico"))
        diag = DiagnosticsView(this)
        col.addView(diag)
        info = Ui.text(this, "", 13f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(info)
        col.addView(Ui.button(this, "Solo errores / todo") { errorsOnly = !errorsOnly })
        col.addView(Ui.button(this, "Copiar registro") { copyLog() })
        col.addView(Ui.button(this, "Borrar registro") { AutopilotState.log.clear() })
        logView = Ui.text(this, "", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(logView)
        setContentView(Ui.scroll(this, col))
    }

    override fun onResume() {
        super.onResume()
        AutopilotState.wantFrames = true
        ui.post(tick)
    }

    override fun onPause() {
        AutopilotState.wantFrames = false
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val s = AutopilotState.snapshot
        val sel = s.packageName ?: AppSettings(this).selectedPackage
        if (sel != pkg) { pkg = sel; diag.profile = sel?.let { store?.load(it)?.profile } }
        diag.snapshot = s
        diag.requestLayout()
        diag.invalidate()

        val o = s.output
        val sb = StringBuilder()
        sb.append("Estado: ${if (s.running) "en marcha" else "detenido"}  juego al frente: ${s.gameInForeground}\n")
        sb.append("Análisis: %.1f/s  proceso: %.1f ms  fotogramas: %d\n".format(Locale.US, s.analysisFps, s.processingMs, s.framesAnalyzed))
        sb.append("Gestos enviados: ${s.gesturesSent}  fallidos: ${s.gesturesFailed}\n")
        s.detector?.player?.diag?.let { sb.append("Jugador (visión): ${it.reason}\n") }
        if (o != null) {
            sb.append("Motor: ${o.status}  armado: ${o.armed}\n")
            sb.append("Jugador: ${o.playerLane}${if (o.playerLaneAssumed) " (supuesto)" else ""}  recomendado: ${o.recommendedLane ?: "-"}\n")
            sb.append("Peligro: %.0f%%\n".format(Locale.US, o.dangerLevel * 100))
            for (l in Lane.entries) {
                val a = o.lanes.getValue(l)
                sb.append(" %-6s %-16s llega: %s  margen: %.2fs  obst: %d\n".format(
                    Locale.US, l.name, a.state.name,
                    a.nearestArrivalSec?.let { "%.2fs".format(Locale.US, it) } ?: "-", a.marginSec, a.obstacleCount,
                ))
            }
            sb.append("Decisión: ${o.decision.action}${o.decision.targetLane?.let { " → $it" } ?: ""}\n")
            sb.append("Motivo: ${o.decision.reason}\n")
            sb.append("Riesgo del plan: %.2f  sin salida: %s\n".format(Locale.US, o.decision.risk, o.decision.deadEnd))
            sb.append("Latencias: edad de imagen %.0f ms  retardo gesto→movimiento %.0f ms (%d muestras)\n".format(
                Locale.US, o.latency.frameAgeMs, o.latency.actuationMs, o.latency.actuationSamples))
            val now = System.nanoTime()
            if (o.tracks.isNotEmpty()) {
                sb.append("Obstáculos seguidos (visto hace / confirmado tras / TTC±σ):\n")
                for (t in o.tracks.sortedBy { it.ttcSec }.take(6)) {
                    val seen = (now - t.firstSeenNs) / 1e6f
                    val conf = t.confirmedNs?.let { "%.0f ms".format(Locale.US, (it - t.firstSeenNs) / 1e6f) } ?: "sin confirmar"
                    val ttc = if (t.ttcSec.isFinite() && t.ttcSec < 99f) "%.2f±%.2fs".format(Locale.US, t.ttcSec, t.ttcSigmaSec) else "-"
                    sb.append(" #%d %-6s %4.0f ms | %-12s | %s\n".format(Locale.US, t.id, t.lane.name, seen, conf, ttc))
                }
            }
            if (o.reactions.isNotEmpty()) {
                sb.append("Reacciones recientes (ms desde el primer avistamiento):\n")
                fun f(v: Float?) = v?.let { "%.0f".format(Locale.US, it) } ?: "-"
                for (r in o.reactions.takeLast(4).reversed()) {
                    sb.append(" #%d %s→%s [%s]\n   visto→confirmado %s | →decisión %s | decisión→gesto %s | gesto→fin %s | decisión→cambio visto %s\n   TOTAL %s ms  margen al llegar %s s  ttc en decisión %s s\n".format(
                        Locale.US, r.commandId, r.from.name, r.target.name, r.outcome,
                        f(r.detectToConfirmMs), f(r.detectToDecisionMs), f(r.decisionToSentMs), f(r.sentToDoneMs), f(r.decisionToChangeMs),
                        f(r.totalReactionMs), r.marginAtChangeSec?.let { "%.2f".format(Locale.US, it) } ?: "-",
                        r.ttcAtDecisionSec?.let { "%.2f".format(Locale.US, it) } ?: "-"))
                }
            }
        }
        s.lastError?.let { sb.append("ÚLTIMO ERROR: $it\n") }
        info.text = sb.toString()

        val entries = (if (errorsOnly) AutopilotState.log.errors() else AutopilotState.log.snapshot()).takeLast(60).reversed()
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val span = SpannableStringBuilder()
        for (e in entries) {
            val line = "${fmt.format(Date(e.timeMs))} ${e.level.name.take(4)} ${e.message}\n"
            val start = span.length
            span.append(line)
            val color = when (e.level) { LogLevel.ERROR -> Ui.RED; LogLevel.WARN -> Ui.AMBER; LogLevel.INFO -> Ui.MUTED }
            span.setSpan(ForegroundColorSpan(color), start, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logView.text = span
    }

    private fun copyLog() {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val text = AutopilotState.log.snapshot().joinToString("\n") { "${fmt.format(Date(it.timeMs))} ${it.level} ${it.message}" }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("FarKs log", text))
        Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
    }
}
