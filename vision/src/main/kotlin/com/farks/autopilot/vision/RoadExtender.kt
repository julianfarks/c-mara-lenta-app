package com.farks.autopilot.vision

import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.PointN
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Extiende hacia el horizonte el borde lejano de la carretera marcado por el usuario.
 *
 * Si el usuario toca el borde lejano más abajo de donde de verdad empieza la calzada, el detector no
 * mira por encima de ese punto y los coches se ven tarde. Aquí se sube fila a fila mientras el centro
 * de la calzada siga siendo carretera continua (misma luminosidad y color que la fila anterior, poca
 * dispersión). Se detiene en cuanto cambia (cielo, edificios, niebla) o aparece un objeto, de modo que
 * es conservador: nunca se extiende por encima de lo que se ve como carretera.
 *
 * Mismo algoritmo que tools/extension_study.py (validado solo con imágenes sintéticas).
 */
object RoadExtender {
    private const val STEP = 0.004f
    private const val MAX_EXT = 0.35f
    private const val MAX_DL = 10f
    private const val MAX_DC = 7f
    private const val MAX_SPREAD = 14f
    private const val FAILS_TO_STOP = 3
    private const val WIDTH = 270.0

    data class Result(val road: RoadGeometry, val originalFarY: Float, val newFarY: Float) {
        val extended: Boolean get() = newFarY < originalFarY - 0.01f
    }

    fun extendFar(frame: Mat, road: RoadGeometry): Result {
        val scale = WIDTH / frame.cols()
        val small = Mat()
        Imgproc.resize(frame, small, Size(WIDTH, frame.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat()
        when (small.channels()) {
            4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            3 -> small.copyTo(rgb)
            else -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
        }
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val w = lab.cols()
        val h = lab.rows()
        val data = ByteArray(w * h * 3)
        lab.get(0, 0, data)
        small.release(); rgb.release(); lab.release()

        val farY = road.farY
        val horizon = road.horizonY
        val limit = max((horizon ?: (farY - MAX_EXT - 1f)) + 0.03f, farY - MAX_EXT)

        var prev = rowStats(data, w, h, road, farY + 0.01f) ?: return Result(road, farY, farY)
        var accepted = farY
        var fails = 0
        var y = farY
        while (y > limit) {
            val st = rowStats(data, w, h, road, y)
            val ok = st != null &&
                abs(st.l - prev.l) <= MAX_DL &&
                hypot(st.a - prev.a, st.b - prev.b) <= MAX_DC &&
                st.spread <= MAX_SPREAD
            if (ok) {
                prev = st!!
                accepted = y
                fails = 0
            } else if (++fails >= FAILS_TO_STOP) break
            y -= STEP
        }
        if (accepted >= farY - 0.01f) return Result(road, farY, farY)
        val ext = RoadGeometry(
            nearLeft = road.nearLeft, nearRight = road.nearRight,
            farLeft = PointN(road.leftX(accepted), accepted),
            farRight = PointN(road.rightX(accepted), accepted),
        )
        return Result(ext, farY, accepted)
    }

    private class Row(val l: Float, val a: Float, val b: Float, val spread: Float)

    private fun rowStats(d: ByteArray, w: Int, h: Int, road: RoadGeometry, y: Float): Row? {
        val width = road.rightX(y) - road.leftX(y)
        val x0 = ((road.leftX(y) + 0.25f * width) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((road.leftX(y) + 0.75f * width) * w).toInt().coerceIn(x0 + 1, w)
        val py = (y * h).toInt()
        val ls = ArrayList<Int>(); val aa = ArrayList<Int>(); val bb = ArrayList<Int>()
        for (r in max(0, py - 1)..minOf(h - 1, py + 1)) {
            for (x in x0 until x1) {
                val o = (r * w + x) * 3
                ls += d[o].toInt() and 0xFF; aa += d[o + 1].toInt() and 0xFF; bb += d[o + 2].toInt() and 0xFF
            }
        }
        if (ls.size < 6) return null
        val ml = median(ls)
        val spread = median(ls.map { abs(it - ml.toInt()) }) * 1.4826f
        return Row(ml, median(aa), median(bb), spread)
    }

    private fun median(v: List<Int>): Float = v.sorted()[v.size / 2].toFloat()
}
