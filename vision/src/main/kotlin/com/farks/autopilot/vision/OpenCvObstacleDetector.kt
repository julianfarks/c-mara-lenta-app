package com.farks.autopilot.vision

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PlayerObservation
import com.farks.autopilot.models.RectN
import com.farks.autopilot.player.PlayerLearnResult
import com.farks.autopilot.player.PlayerLocator
import com.farks.autopilot.player.PlayerLookLearner
import com.farks.autopilot.player.WorkGrid
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Clasificador opcional (p. ej. LiteRT) que confirma o descarta un candidato. Devuelve P(obstáculo). */
fun interface CandidateClassifier {
    fun score(frame: Mat, box: RectN): Float
}

data class DetectorParams(
    /** Ancho de trabajo en px; 0 = automático (suficientes píxeles en el carril más lejano). */
    val workWidth: Int = 0,
    /** Píxeles de trabajo que debe tener el carril en su borde lejano en el modo automático. */
    val farLanePx: Int = 18,
    /** Fracción del ancho del carril que se recorta a cada lado para no unir carriles vecinos. */
    val laneShrink: Float = 0.12f,
    val minWidthLane: Float = 0.30f,
    val minHeightLane: Float = 0.15f,
    val minFill: Float = 0.35f,
    /** Fracción de píxeles distintos al asfalto en la caja del jugador para dar por localizado el carril. */
    val playerMinForeground: Float = 0.20f,
    val classifierReject: Float = 0.40f,
)

data class DetectorResult(
    val detections: List<Detection>,
    val player: PlayerObservation,
    /** Candidatos descartados por el clasificador (para diagnóstico). */
    val rejectedByClassifier: Int,
    val processingMs: Float,
)

/**
 * Detector de obstáculos por desviación respecto al modelo de color de la carretera vacía.
 * Mismo algoritmo que tools/prototype_detector.py (validado solo con imágenes sintéticas).
 *
 * Limitación inherente: un vehículo del mismo color que el asfalto no se distingue del fondo.
 * Los efectos de luz, sombras, pickups o recompensas pueden aparecer como falsos positivos;
 * para eso existe el clasificador opcional.
 *
 * No es seguro entre hilos: use una instancia por hilo de análisis.
 */
class OpenCvObstacleDetector(
    private val profile: GameProfile,
    private val model: RoadColorModel,
    private val classifier: CandidateClassifier? = null,
    private val params: DetectorParams = DetectorParams(),
) {
    private val road = profile.road
    private val cropTop = road.farY + 0.002f
    private val obstBottom = profile.playerBox.top - 0.004f
    /** Se analiza hasta por debajo de la caja: el coche suele sobresalir (parachoques, ruedas). */
    private val regionBottom = profile.playerZoneBottom
    private val locator: PlayerLocator? = profile.playerLook?.let { PlayerLocator(profile.road, profile.playerBox, regionBottom, it) }
    private val x0n = (road.leftX(regionBottom) - 0.02f).coerceAtLeast(0f)
    private val x1n = (road.rightX(regionBottom) + 0.02f).coerceAtMost(1f)

    private val bandCenters = FloatArray(RoadColorModel.N_BANDS) {
        road.farY + (it + 0.5f) / RoadColorModel.N_BANDS * (road.nearY - road.farY)
    }

    private val kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    private val kClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 7.0))

    /** Ancho de trabajo: más resolución cuanto más estrecho se ve el carril al fondo (mejor alcance y mejor TTC). */
    private val workW: Int = if (params.workWidth > 0) params.workWidth else {
        val farFrac = (road.laneWidth(cropTop) / (x1n - x0n)).coerceAtLeast(0.02f)
        (((params.farLanePx / farFrac).toInt().coerceIn(160, 320) + 7) / 8) * 8
    }

    init {
        require(obstBottom > cropTop + 0.05f) {
            "La zona entre el horizonte de la carretera y el jugador es demasiado pequeña: recalibre."
        }
        require(x1n > x0n + 0.1f) { "Zona de carretera inválida: recalibre." }
    }

    private class Prepared(val data: ByteArray, val mask: ByteArray, val ww: Int, val wh: Int, val ph: Float)

    /** Recorta, reduce y convierte a Lab, y calcula la máscara de desviación respecto a la carretera vacía. */
    private fun prepare(frame: Mat): Prepared {
        val fx0 = (x0n * frame.cols()).toInt().coerceIn(0, frame.cols() - 2)
        val fx1 = (x1n * frame.cols()).toInt().coerceIn(fx0 + 1, frame.cols())
        val fy0 = (cropTop * frame.rows()).toInt().coerceIn(0, frame.rows() - 2)
        val fy1 = (regionBottom * frame.rows()).toInt().coerceIn(fy0 + 1, frame.rows())
        val sub = frame.submat(Rect(fx0, fy0, fx1 - fx0, fy1 - fy0))

        val ww = workW
        val wh = max(16, (sub.rows().toDouble() * ww / sub.cols()).roundToInt())
        val small = Mat()
        Imgproc.resize(sub, small, Size(ww.toDouble(), wh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        sub.release()
        val rgb = Mat()
        when (small.channels()) {
            4 -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            3 -> small.copyTo(rgb)
            else -> Imgproc.cvtColor(small, rgb, Imgproc.COLOR_GRAY2RGB)
        }
        small.release()
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        rgb.release()
        val data = ByteArray(ww * wh * 3)
        lab.get(0, 0, data)
        lab.release()

        // Píxeles de trabajo por unidad normalizada de y
        val ph = wh / (regionBottom - cropTop)
        val mask = ByteArray(ww * wh)
        val bands = model.bands
        for (r in 0 until wh) {
            val yn = cropTop + (r + 0.5f) / ph
            val e = interpolate(bands, yn)
            val base = r * ww
            for (c in 0 until ww) {
                val o = (base + c) * 3
                val dl = kotlin.math.abs((data[o].toInt() and 0xFF) - e[0])
                val da = (data[o + 1].toInt() and 0xFF) - e[1]
                val db = (data[o + 2].toInt() and 0xFF) - e[2]
                if (dl > e[3] || da * da + db * db > e[4] * e[4]) mask[base + c] = -1 // 255
            }
        }
        return Prepared(data, mask, ww, wh, ph)
    }

    private fun grid(p: Prepared) = WorkGrid(p.data, p.mask, p.ww, p.wh, x0n, x1n, cropTop, regionBottom)

    /**
     * Aprende la apariencia del vehículo del jugador (carril calibrado) con EXACTAMENTE la misma preparación de
     * imagen que usa [detect], de modo que lo aprendido y lo verificado comparten coordenadas y escala.
     */
    fun learnPlayer(frame: Mat): PlayerLearnResult {
        val p = prepare(frame)
        return PlayerLookLearner.learn(grid(p), road, profile.playerBox, regionBottom, profile.playerLane)
    }

    /** Qué ve el detector (máscara de no-asfalto, en el espacio normalizado de la captura) para el diagnóstico. */
    fun foregroundMask(frame: Mat): Triple<ByteArray, Int, Int> { val p = prepare(frame); return Triple(p.mask, p.ww, p.wh) }

    /** @param frame captura completa, RGBA/RGB de 8 bits. */
    fun detect(frame: Mat): DetectorResult {
        val t0 = System.nanoTime()
        val prep = prepare(frame)
        val data = prep.data; val mask = prep.mask; val ww = prep.ww; val wh = prep.wh; val ph = prep.ph

        val player = locator?.locate(grid(prep)) ?: locatePlayer(mask, ww, wh, ph)

        val obstRows = min(wh, ((obstBottom - cropTop) * ph).toInt())
        val maskMat = Mat(obstRows, ww, CvType.CV_8UC1)
        maskMat.put(0, 0, mask.copyOfRange(0, obstRows * ww))

        val dets = ArrayList<Detection>()
        var rejected = 0
        for (lane in Lane.entries) {
            val valid = Mat.zeros(obstRows, ww, CvType.CV_8UC1)
            val pts = ArrayList<Point>(4)
            for (yn in floatArrayOf(cropTop, obstBottom)) {
                val w = road.laneWidth(yn)
                val xl = road.leftX(yn) + w * lane.index + params.laneShrink * w
                pts += Point(toPx(xl, ww).toDouble(), ((yn - cropTop) * ph).toDouble())
            }
            for (yn in floatArrayOf(obstBottom, cropTop)) {
                val w = road.laneWidth(yn)
                val xr = road.leftX(yn) + w * (lane.index + 1) - params.laneShrink * w
                pts += Point(toPx(xr, ww).toDouble(), ((yn - cropTop) * ph).toDouble())
            }
            val poly = MatOfPoint()
            poly.fromList(pts)
            Imgproc.fillConvexPoly(valid, poly, Scalar(255.0))
            poly.release()

            val m = Mat()
            org.opencv.core.Core.bitwise_and(maskMat, valid, m)
            valid.release()
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, kOpen)
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, kClose)

            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val n = Imgproc.connectedComponentsWithStats(m, labels, stats, centroids, 8, CvType.CV_32S)
            val s = IntArray(5)
            for (i in 1 until n) {
                stats.get(i, 0, s)
                val x = s[0]; val y = s[1]; val w = s[2]; val h = s[3]; val area = s[4]
                if (w <= 0 || h <= 0) continue
                val xl = x0n + x.toFloat() / ww * (x1n - x0n)
                val xr = x0n + (x + w).toFloat() / ww * (x1n - x0n)
                val yt = cropTop + y / ph
                val yb = cropTop + (y + h) / ph
                val lw = road.laneWidth(yb)
                if ((xr - xl) < params.minWidthLane * lw || (yb - yt) < params.minHeightLane * lw) continue
                val fill = area.toFloat() / (w * h)
                if (fill < params.minFill) continue
                val box = RectN(xl, yt, xr, yb)
                var conf = 0.5f + 0.5f * fill
                if (classifier != null) {
                    val p = classifier.score(frame, box)
                    if (p < params.classifierReject) { rejected++; continue }
                    conf = (conf + p) / 2f
                }
                dets += Detection(box, conf.coerceIn(0f, 1f))
            }
            labels.release(); stats.release(); centroids.release(); m.release()
        }
        maskMat.release()

        return DetectorResult(dets, player, rejected, (System.nanoTime() - t0) / 1e6f)
    }

    /** Carril del jugador: el de mayor fracción de píxeles distintos al asfalto bajo la caja del jugador. */
    private fun locatePlayer(mask: ByteArray, ww: Int, wh: Int, ph: Float): PlayerObservation {
        val pb = profile.playerBox
        val r0 = ((pb.top - cropTop) * ph).toInt().coerceIn(0, wh - 1)
        val r1 = ((pb.bottom - cropTop) * ph).toInt().coerceIn(r0 + 1, wh)
        var bestLane: Lane? = null
        var bestFrac = 0f
        for (lane in Lane.entries) {
            val cx = road.laneCenterX(lane, pb.bottom)
            val half = pb.width * 0.4f
            val c0 = toPx(cx - half, ww).coerceIn(0, ww - 1)
            val c1 = toPx(cx + half, ww).coerceIn(c0 + 1, ww)
            var fg = 0
            var tot = 0
            for (r in r0 until r1) {
                val base = r * ww
                for (c in c0 until c1) {
                    tot++
                    if (mask[base + c].toInt() != 0) fg++
                }
            }
            val f = if (tot > 0) fg.toFloat() / tot else 0f
            if (f > bestFrac) { bestFrac = f; bestLane = lane }
        }
        return if (bestLane != null && bestFrac >= params.playerMinForeground) {
            PlayerObservation(bestLane, bestFrac.coerceAtMost(1f))
        } else PlayerObservation(null, 0f)
    }

    private fun toPx(xn: Float, ww: Int): Int = ((xn - x0n) / (x1n - x0n) * ww).roundToInt()

    private val interp = FloatArray(5)

    private fun interpolate(bands: List<BandStats>, y: Float): FloatArray {
        val c = bandCenters
        if (y <= c[0]) return fill(bands[0])
        if (y >= c[c.size - 1]) return fill(bands[bands.size - 1])
        var i = 0
        while (i < c.size - 2 && y > c[i + 1]) i++
        val t = (y - c[i]) / (c[i + 1] - c[i])
        val a = bands[i]
        val b = bands[i + 1]
        interp[0] = a.l + (b.l - a.l) * t
        interp[1] = a.a + (b.a - a.a) * t
        interp[2] = a.b + (b.b - a.b) * t
        interp[3] = a.tolL + (b.tolL - a.tolL) * t
        interp[4] = a.tolC + (b.tolC - a.tolC) * t
        return interp
    }

    private fun fill(s: BandStats): FloatArray {
        interp[0] = s.l; interp[1] = s.a; interp[2] = s.b; interp[3] = s.tolL; interp[4] = s.tolC
        return interp
    }
}
