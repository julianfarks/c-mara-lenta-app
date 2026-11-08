package com.farks.autopilot.vision

import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.RectN
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Estadísticas robustas del color de la carretera en una franja horizontal (espacio Lab 8 bits). */
data class BandStats(
    val l: Float,
    val a: Float,
    val b: Float,
    val tolL: Float,
    val tolC: Float,
)

/**
 * Modelo del aspecto de la carretera vacía: una mediana y una tolerancia por franja de profundidad
 * (el asfalto se aclara con la distancia). Los píxeles que se desvían de este modelo son candidatos
 * a obstáculo. Se aprende de una captura de referencia durante la calibración.
 *
 * Se usa Lab (luminosidad + crominancia) en lugar del tono de HSV porque el tono de un asfalto casi
 * sin saturación es puro ruido.
 */
class RoadColorModel(val bands: List<BandStats>) {

    init {
        require(bands.size == N_BANDS) { "El modelo necesita $N_BANDS franjas" }
    }

    fun toFloatArray(): FloatArray {
        val out = FloatArray(bands.size * 5)
        bands.forEachIndexed { i, s ->
            out[i * 5] = s.l; out[i * 5 + 1] = s.a; out[i * 5 + 2] = s.b
            out[i * 5 + 3] = s.tolL; out[i * 5 + 4] = s.tolC
        }
        return out
    }

    companion object {
        const val N_BANDS = 16
        const val MIN_TOL_L = 24f
        const val MIN_TOL_C = 14f
        private const val LEARN_WIDTH = 270.0

        fun fromFloatArray(a: FloatArray): RoadColorModel {
            require(a.size == N_BANDS * 5) { "Modelo de color corrupto" }
            return RoadColorModel(List(N_BANDS) { i ->
                BandStats(a[i * 5], a[i * 5 + 1], a[i * 5 + 2], a[i * 5 + 3], a[i * 5 + 4])
            })
        }

        /**
         * @param reference captura de referencia (RGBA o RGB, 8 bits)
         * @param exclude zonas a ignorar (por ejemplo el automóvil del jugador)
         */
        fun learn(reference: Mat, road: RoadGeometry, exclude: List<RectN>): RoadColorModel {
            val scale = LEARN_WIDTH / reference.cols()
            val small = Mat()
            Imgproc.resize(reference, small, Size(LEARN_WIDTH, reference.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
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

            val hist = Array(N_BANDS) { Array(3) { IntArray(256) } }
            val count = IntArray(N_BANDS)
            val y0 = road.farY
            val y1 = road.nearY
            for (py in 0 until h) {
                val yn = (py + 0.5f) / h
                if (yn < y0 || yn > y1) continue
                val band = (((yn - y0) / (y1 - y0)) * N_BANDS).toInt().coerceIn(0, N_BANDS - 1)
                val lw = road.laneWidth(yn)
                val xMin = road.leftX(yn) + 0.10f * lw
                val xMax = road.rightX(yn) - 0.10f * lw
                for (px in 0 until w) {
                    val xn = (px + 0.5f) / w
                    if (xn < xMin || xn > xMax) continue
                    if (exclude.any { xn >= it.left && xn <= it.right && yn >= it.top && yn <= it.bottom }) continue
                    val o = (py * w + px) * 3
                    for (c in 0 until 3) hist[band][c][data[o + c].toInt() and 0xFF]++
                    count[band]++
                }
            }

            val stats = ArrayList<BandStats>(N_BANDS)
            for (b in 0 until N_BANDS) {
                if (count[b] < 50) {
                    stats += stats.lastOrNull() ?: BandStats(110f, 128f, 128f, MIN_TOL_L, MIN_TOL_C)
                    continue
                }
                val med = FloatArray(3)
                val mad = FloatArray(3)
                for (c in 0 until 3) {
                    val m = median(hist[b][c], count[b])
                    med[c] = m.toFloat()
                    val dev = IntArray(256)
                    for (v in 0 until 256) dev[abs(v - m)] += hist[b][c][v]
                    mad[c] = median(dev, count[b]) * 1.4826f
                }
                stats += BandStats(
                    med[0], med[1], med[2],
                    max(MIN_TOL_L, 4f * mad[0]),
                    max(MIN_TOL_C, 4f * hypot(mad[1], mad[2])),
                )
            }
            return RoadColorModel(stats)
        }

        private fun median(hist: IntArray, total: Int): Int {
            var acc = 0
            val half = (total + 1) / 2
            for (v in hist.indices) {
                acc += hist[v]
                if (acc >= half) return v
            }
            return hist.size - 1
        }
    }
}

/** Color característico del vehículo del jugador (mediana Lab de la parte central de su caja). */
object PlayerAppearance {
    fun learn(frame: Mat, box: RectN): com.farks.autopilot.calibration.LabColor? {
        val x0 = ((box.left + 0.2f * box.width) * frame.cols()).toInt().coerceIn(0, frame.cols() - 2)
        val x1 = ((box.right - 0.2f * box.width) * frame.cols()).toInt().coerceIn(x0 + 1, frame.cols())
        val y0 = ((box.top + 0.2f * box.height) * frame.rows()).toInt().coerceIn(0, frame.rows() - 2)
        val y1 = ((box.bottom - 0.2f * box.height) * frame.rows()).toInt().coerceIn(y0 + 1, frame.rows())
        val sub = frame.submat(org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0))
        val rgb = Mat()
        when (sub.channels()) {
            4 -> Imgproc.cvtColor(sub, rgb, Imgproc.COLOR_RGBA2RGB)
            3 -> sub.copyTo(rgb)
            else -> Imgproc.cvtColor(sub, rgb, Imgproc.COLOR_GRAY2RGB)
        }
        sub.release()
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val n = lab.rows() * lab.cols()
        if (n < 20) { lab.release(); return null }
        val data = ByteArray(n * 3)
        lab.get(0, 0, data)
        lab.release()
        fun med(c: Int): Float {
            val h = IntArray(256)
            for (i in 0 until n) h[data[i * 3 + c].toInt() and 0xFF]++
            var acc = 0
            for (v in 0 until 256) { acc += h[v]; if (acc >= (n + 1) / 2) return v.toFloat() }
            return 255f
        }
        return com.farks.autopilot.calibration.LabColor(med(0), med(1), med(2))
    }
}

internal fun Float.px(total: Int): Int = (this * total).roundToInt()
