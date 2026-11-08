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
    /**
     * Franja sobre el borde lejano de la carretera (fracción de la altura de pantalla) donde se buscan coches que acaban de
     * aparecer: en muchos juegos el coche asoma sobre la línea del horizonte, por encima del asfalto, y sin esta franja
     * solo se ve cuando ya está cerca. Ahí no hay modelo de asfalto sino un fondo temporal por píxel. 0 = desactivada.
     */
    val horizonBandFrac: Float = 0.05f,
    /** Cerca del horizonte (fracción de altura bajo el borde lejano) los umbrales de tamaño se relajan: apenas asoma. */
    val farZoneFrac: Float = 0.035f,
    val farMinWidthLane: Float = 0.22f,
    val farMinHeightLane: Float = 0.07f,
    /** Diferencia (L*0.8 o crominancia*1.6, escala Lab 8 bits) respecto al fondo para considerar un píxel nuevo. */
    val bgDiff: Float = 22f,
    val bgAlpha: Float = 0.04f,
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
    /** Primera fila con asfalto (el modelo de color solo vale desde aquí). */
    private val roadTop = road.farY + 0.002f
    private val cropTop = if (params.horizonBandFrac > 0f) (road.farY - params.horizonBandFrac).coerceAtLeast(0.01f) else roadTop

    // Fondo temporal de la franja del horizonte (Lab de 8 bits por píxel) y edad de cada píxel como primer plano.
    private var bg: FloatArray? = null
    private var fgAge: IntArray? = null
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
        val farFrac = (road.laneWidth(roadTop) / (x1n - x0n)).coerceAtLeast(0.02f)
        (((params.farLanePx / farFrac).toInt().coerceIn(160, 320) + 7) / 8) * 8
    }

    init {
        require(obstBottom > cropTop + 0.05f) {
            "La zona entre el horizonte de la carretera y el jugador es demasiado pequeña: recalibre."
        }
        require(x1n > x0n + 0.1f) { "Zona de carretera inválida: recalibre." }
    }

    /**
     * Franja sobre el horizonte: primer plano = píxeles que difieren del fondo temporal. El fondo se actualiza despacio
     * y solo donde no hay primer plano (un coche que crece no se absorbe); si casi toda la franja cambia (cambio de
     * escena) se reinicia, y un píxel que lleva mucho tiempo distinto se absorbe (objeto estático/decoración nueva).
     */
    private fun bandMask(data: ByteArray, mask: ByteArray, ww: Int, nb: Int) {
        val n = ww * nb
        var b = bg
        var age = fgAge
        if (b == null || b.size != n * 3 || age == null || age.size != n) {
            b = FloatArray(n * 3) { (data[it].toInt() and 0xFF).toFloat() }
            age = IntArray(n)
            bg = b; fgAge = age
        }
        val fg = BooleanArray(n)
        var count = 0
        for (i in 0 until n) {
            val o = i * 3
            val dl = kotlin.math.abs((data[o].toInt() and 0xFF) - b[o]) * 0.8f
            val da = (data[o + 1].toInt() and 0xFF) - b[o + 1]
            val db = (data[o + 2].toInt() and 0xFF) - b[o + 2]
            val dc = kotlin.math.sqrt(da * da + db * db) * 1.6f
            if (max(dl, dc) > params.bgDiff) { fg[i] = true; count++ }
        }
        if (count > 0.35f * n) { // cambio de escena
            for (i in 0 until n * 3) b[i] = (data[i].toInt() and 0xFF).toFloat()
            java.util.Arrays.fill(age, 0)
            return
        }
        for (i in 0 until n) if (fg[i]) { mask[i] = -1; age[i]++ } else age[i] = 0
        // Actualización lenta excluyendo el primer plano y su entorno (dilatación 5×5).
        for (r in 0 until nb) for (c in 0 until ww) {
            val i = r * ww + c
            var near = false
            var rr = max(0, r - 2)
            while (rr <= min(nb - 1, r + 2) && !near) {
                var cc = max(0, c - 2)
                while (cc <= min(ww - 1, c + 2)) { if (fg[rr * ww + cc]) { near = true; break }; cc++ }
                rr++
            }
            if (!near || age[i] > 45) {
                val o = i * 3
                val a = params.bgAlpha
                for (k in 0 until 3) b[o + k] = b[o + k] * (1f - a) + (data[o + k].toInt() and 0xFF) * a
                if (age[i] > 45) age[i] = 0
            }
        }
    }

    /** Olvida el fondo del horizonte (p. ej. al cambiar de partida). */
    fun resetBackground() { bg = null; fgAge = null }

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
        // Filas cuyo centro queda por encima del asfalto -> franja de horizonte (el resto usa el modelo de carretera).
        val nb = if (params.horizonBandFrac > 0f) kotlin.math.ceil((roadTop - cropTop) * ph - 0.5f).toInt().coerceIn(0, wh - 1) else 0
        for (r in nb until wh) {
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
        if (nb > 0) bandMask(data, mask, ww, nb)
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
                val ye = max(yn, road.farY) // sobre el horizonte los carriles se prolongan verticales
                val w = road.laneWidth(ye)
                val xl = road.leftX(ye) + w * lane.index + params.laneShrink * w
                pts += Point(toPx(xl, ww).toDouble(), ((yn - cropTop) * ph).toDouble())
            }
            for (yn in floatArrayOf(obstBottom, cropTop)) {
                val ye = max(yn, road.farY)
                val w = road.laneWidth(ye)
                val xr = road.leftX(ye) + w * (lane.index + 1) - params.laneShrink * w
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
                val lw = road.laneWidth(max(yb, road.farY))
                val far = yb < road.farY + params.farZoneFrac
                val mw = if (far) params.farMinWidthLane else params.minWidthLane
                val mh = if (far) params.farMinHeightLane else params.minHeightLane
                if ((xr - xl) < mw * lw || (yb - yt) < mh * lw) continue
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
