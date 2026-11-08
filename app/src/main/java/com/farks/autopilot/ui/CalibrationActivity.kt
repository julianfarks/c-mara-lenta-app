package com.farks.autopilot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.ProfileStore
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.calibration.StoredProfile
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import com.farks.autopilot.vision.OpenCvObstacleDetector
import com.farks.autopilot.vision.RoadColorModel
import com.farks.autopilot.vision.RoadExtender
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.min

/** Vista que muestra la captura de referencia y recoge, en orden, los 6 toques de la calibración. */
class CalibrationView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }
    val points = mutableListOf<PointN>()
    var onChange: (() -> Unit)? = null
    /** Diagnóstico visual de la verificación del vehículo del jugador (se pinta sobre la captura). */
    var playerDiag: com.farks.autopilot.models.PlayerDiag? = null
        set(v) { field = v; invalidate() }

    private val paintPt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.FILL }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val paintLane = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val paintBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.AMBER; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val paintTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val b = bitmap
        val height = if (b == null) width else (width.toLong() * b.height / b.width).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && points.size < 6 && width > 0 && height > 0) {
            points += PointN((e.x / width).coerceIn(0f, 1f), (e.y / height).coerceIn(0f, 1f))
            invalidate(); onChange?.invoke()
        }
        return true
    }

    fun undo() { if (points.isNotEmpty()) { points.removeAt(points.size - 1); invalidate(); onChange?.invoke() } }
    fun reset() { points.clear(); invalidate(); onChange?.invoke() }

    private fun px(p: PointN) = p.x * width to p.y * height

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        c.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        points.forEachIndexed { i, p ->
            val (x, y) = px(p)
            c.drawCircle(x, y, 10f, paintPt)
            c.drawText("${i + 1}", x + 14f, y - 14f, paintTxt)
        }
        if (points.size >= 4) {
            val (fl, fr, nl, nr) = points.take(4)
            val order = listOf(fl, fr, nr, nl, fl)
            for (i in 0 until 4) {
                val (x0, y0) = px(order[i]); val (x1, y1) = px(order[i + 1])
                c.drawLine(x0, y0, x1, y1, paintLine)
            }
            // Divisiones de carril (tercios)
            for (k in 1..2) {
                val t = k / 3f
                val (ax, ay) = px(PointN(fl.x + (fr.x - fl.x) * t, fl.y + (fr.y - fl.y) * t))
                val (bx, by) = px(PointN(nl.x + (nr.x - nl.x) * t, nl.y + (nr.y - nl.y) * t))
                c.drawLine(ax, ay, bx, by, paintLane)
            }
        }
        if (points.size >= 6) {
            val a = points[4]; val d = points[5]
            val (x0, y0) = px(PointN(min(a.x, d.x), min(a.y, d.y)))
            val (x1, y1) = px(PointN(max(a.x, d.x), max(a.y, d.y)))
            c.drawRect(x0, y0, x1, y1, paintBox)
        }
        playerDiag?.let { PlayerDiagDraw.draw(c, it, width.toFloat(), height.toFloat()) }
    }
}

class CalibrationActivity : AppCompatActivity() {

    private lateinit var pkg: String
    private lateinit var store: ProfileStore
    private lateinit var view: CalibrationView
    private lateinit var instruction: TextView
    private lateinit var result: TextView
    private lateinit var learnBtn: Button
    private lateinit var saveBtn: Button
    private var refBitmap: Bitmap? = null
    private var pending: StoredProfile? = null

    private val steps = listOf(
        "1/6  Toque el borde IZQUIERDO de la carretera, en la parte LEJANA (donde empieza a verse).",
        "2/6  Toque el borde DERECHO de la carretera, en la parte LEJANA.",
        "3/6  Toque el borde IZQUIERDO de la carretera, en la parte CERCANA (abajo).",
        "4/6  Toque el borde DERECHO de la carretera, en la parte CERCANA.",
        "5/6  Toque la esquina SUPERIOR-IZQUIERDA de su vehículo.",
        "6/6  Toque la esquina INFERIOR-DERECHA de su vehículo.",
        "Listo. Pulse «Aprender y verificar».",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        store = ProfileStore(this)
        OpenCVLoader.initLocal()

        val col = Ui.column(this)
        col.addView(Ui.title(this, "Calibración"))
        instruction = Ui.text(this, "", 15f, Ui.AMBER)
        col.addView(instruction)

        view = CalibrationView(this)
        val file = store.referenceFile(pkg)
        refBitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        view.bitmap = refBitmap
        view.onChange = { pending = null; view.playerDiag = null; saveBtn.isEnabled = false; result.text = ""; updateUi() }
        col.addView(view)

        if (refBitmap == null) {
            col.addView(Ui.text(this, "No hay captura de referencia. Vuelva a «Calibrar el juego».", 14f, Ui.RED))
        }

        result = Ui.text(this, "", 13f)
        learnBtn = Ui.button(this, "Aprender y verificar", Ui.GREEN) { learn() }
        saveBtn = Ui.button(this, "Guardar calibración", Ui.GREEN) { save() }
        col.addView(result)
        col.addView(learnBtn)
        col.addView(saveBtn)
        col.addView(Ui.button(this, "Deshacer último toque") { view.undo() })
        col.addView(Ui.button(this, "Reiniciar toques") { view.reset() })
        col.addView(Ui.spacer(this, 8))
        col.addView(Ui.text(this,
            "Consejo: la captura debería mostrar la carretera SIN obstáculos para que el color se aprenda bien " +
                "(se usa la mediana, tolera algún objeto pequeño). Si el juego cambia de escenario o iluminación, recalibre.",
            12f, Ui.MUTED))
        setContentView(Ui.scroll(this, col))
        updateUi()
    }

    private fun updateUi() {
        instruction.text = steps[view.points.size.coerceAtMost(6)]
        learnBtn.isEnabled = view.points.size == 6 && refBitmap != null
        saveBtn.isEnabled = pending != null
    }

    private fun buildProfile(): GameProfile? {
        val p = view.points
        return try {
            val road = RoadGeometry(nearLeft = p[2], nearRight = p[3], farLeft = p[0], farRight = p[1])
            val box = RectN(min(p[4].x, p[5].x), min(p[4].y, p[5].y), max(p[4].x, p[5].x), max(p[4].y, p[5].y))
            require(box.width > 0.03f && box.height > 0.02f) { "La caja del vehículo es demasiado pequeña." }
            val lane = road.laneAt(box.centerX, box.bottom)
                ?: throw IllegalArgumentException("Su vehículo no está dentro de la carretera marcada.")
            val name = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
            GameProfile(pkg, name, road, box, lane)
        } catch (e: IllegalArgumentException) {
            result.setTextColor(Ui.RED); result.text = "✖ ${e.message}"
            null
        }
    }

    private fun learn() {
        val bmp = refBitmap ?: return
        val base = buildProfile() ?: return
        val mat = Mat()
        try {
            Utils.bitmapToMat(bmp, mat)
            // 1) Si el borde lejano se marcó por debajo del inicio real de la calzada, se extiende hacia el horizonte
            //    (mayor alcance de detección). 2) Se aprende la apariencia del vehículo del jugador.
            val ext = RoadExtender.extendFar(mat, base.road)
            val profile = base.copy(road = ext.road)
            // Carretera vacía: se excluye TODA la zona del vehículo (caja ampliada), no solo la caja marcada.
            val model = RoadColorModel.learn(mat, profile.road, listOf(profile.playerZone))
            // Apariencia del vehículo: píxeles que no son asfalto (paleta, área, forma, posición), no un color medio.
            val learned = OpenCvObstacleDetector(profile, model).learnPlayer(mat)
            val withLook = profile.copy(playerLook = learned.look)
            val detector = OpenCvObstacleDetector(withLook, model)
            val res = detector.detect(mat)
            val playerOk = res.player.lane == profile.playerLane
            view.playerDiag = res.player.diag
            val sb = StringBuilder()
            sb.append("Modelo de color aprendido (${RoadColorModel.N_BANDS} franjas).\n")
            if (ext.extended) sb.append("Borde lejano extendido automáticamente de y=%.2f a y=%.2f (más alcance de detección).\n".format(ext.originalFarY, ext.newFarY))
            else sb.append("Borde lejano: sin extensión posible (la calzada no continúa por encima del punto marcado).\n")
            sb.append(learned.message).append('\n')
            sb.append("Verificación sobre la propia referencia:\n")
            sb.append(" • Obstáculos detectados: ${res.detections.size} (esperado 0 si la carretera estaba vacía)\n")
            sb.append(" • Su vehículo se localiza en: ${res.player.lane ?: "no localizado"} (marcado: ${profile.playerLane})\n")
            res.player.diag?.let { sb.append(" • ").append(it.reason).append('\n') }
            sb.append(" • Tiempo de análisis: ${"%.0f".format(res.processingMs)} ms")
            if (learned.look != null) sb.append("\nEn la imagen: ventanas por carril (verde = aceptado, rojo = rechazado), magenta = lo que no es asfalto; A=área F=forma C=color P=posición.")
            val warn = res.detections.isNotEmpty() || !playerOk
            result.setTextColor(if (warn) Ui.AMBER else Ui.GREEN)
            if (warn) sb.append("\n⚠ Revise los toques o repita la captura con la carretera vacía; puede guardar igualmente y comprobar en Diagnóstico.")
            result.text = sb.toString()
            pending = StoredProfile(withLook, model.toFloatArray())
        } catch (e: Exception) {
            pending = null
            result.setTextColor(Ui.RED)
            result.text = "✖ No se pudo calibrar: ${e.message}"
        } finally {
            mat.release()
        }
        updateUi()
    }

    private fun save() {
        val p = pending ?: return
        store.save(p)
        result.setTextColor(Ui.GREEN)
        result.text = "✔ Calibración guardada para ${p.profile.displayName}. Ya puede pulsar INICIAR."
        saveBtn.isEnabled = false
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
