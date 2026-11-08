package com.farks.autopilot.vision

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Pruebas del detector sobre escenas SINTÉTICAS (carretera con ruido, líneas de carril discontinuas,
 * vehículos de colores). No prueban nada sobre juegos reales. Requieren la librería nativa de OpenCV
 * (org.openpnp:opencv), que se carga con nu.pattern.OpenCV.loadLocally().
 */
class DetectorSyntheticTest {

    companion object {
        const val W = 540
        const val H = 1200

        val road = RoadGeometry(PointN(0.05f, 0.95f), PointN(0.95f, 0.95f), PointN(0.38f, 0.30f), PointN(0.62f, 0.30f))
        val playerBox = RectN(0.40f, 0.74f, 0.60f, 0.92f)
        fun profile(lane: Lane = Lane.CENTER) = GameProfile("test.game", "Test", road, playerBox, lane)

        @JvmStatic @BeforeClass
        fun loadOpenCv() { nu.pattern.OpenCV.loadLocally() }

        val COLORS = mapOf(
            "rojo" to intArrayOf(220, 40, 40), "azul" to intArrayOf(30, 90, 220), "blanco" to intArrayOf(240, 240, 240),
            "negro" to intArrayOf(25, 25, 25), "amarillo" to intArrayOf(240, 210, 30), "verde" to intArrayOf(60, 180, 60),
        )
    }

    class Car(val lane: Lane, val depthU: Float, val rgb: IntArray)

    private fun sc(rgb: IntArray) = Scalar(rgb[0].toDouble(), rgb[1].toDouble(), rgb[2].toDouble())

    /** @return (imagen RGB, cajas reales normalizadas por carril) */
    private fun render(cars: List<Car>, playerLane: Lane, seed: Long, withPlayer: Boolean = true): Pair<Mat, Map<Lane, RectN>> {
        val rnd = Random(seed)
        val data = ByteArray(W * H * 3)
        for (y in 0 until H) {
            val yn = y.toFloat() / H
            val l = road.leftX(yn); val r = road.rightX(yn)
            val onRoad = yn in 0.30f..0.95f
            val base = 95f + 35f * (1f - ((yn - 0.3f) / 0.65f).coerceIn(0f, 1f))
            for (x in 0 until W) {
                val xn = x.toFloat() / W
                val o = (y * W + x) * 3
                val rgb = if (onRoad && xn in l..r) {
                    val n = (rnd.nextGaussian() * 6.0).toFloat()
                    intArrayOf((base + n + 4).toInt(), (base + n).toInt(), (base + n).toInt())
                } else if (yn >= 0.28f) intArrayOf(60, 140, 70) else intArrayOf(120, 170, 200)
                for (c in 0 until 3) data[o + c] = rgb[c].coerceIn(0, 255).toByte()
            }
        }
        val img = Mat(H, W, CvType.CV_8UC3)
        img.put(0, 0, data)

        // Líneas de carril discontinuas
        for (k in 1..2) {
            var s = 0.30f
            while (s < 0.95f) {
                val y0 = s; val y1 = s + 0.022f
                val x0 = road.leftX(y0) + road.laneWidth(y0) * k
                val x1 = road.leftX(y1) + road.laneWidth(y1) * k
                val w0 = max(1f, road.laneWidth(y0) * 0.05f * W); val w1 = max(1f, road.laneWidth(y1) * 0.05f * W)
                val pts = org.opencv.core.MatOfPoint(
                    Point(x0 * W - w0 / 2.0, y0 * H.toDouble()), Point(x0 * W + w0 / 2.0, y0 * H.toDouble()),
                    Point(x1 * W + w1 / 2.0, y1 * H.toDouble()), Point(x1 * W - w1 / 2.0, y1 * H.toDouble()),
                )
                Imgproc.fillConvexPoly(img, pts, Scalar(235.0, 235.0, 235.0))
                pts.release()
                s += 0.045f
            }
        }

        val truth = HashMap<Lane, RectN>()
        val horizon = road.horizonY!!
        for (car in cars) {
            val y = horizon + 1f / car.depthU
            if (y > 1f || y < 0.305f) continue
            val w = 0.7f * road.laneWidth(y)
            val h = w * 1.1f
            val cx = road.laneCenterX(car.lane, y)
            val x0 = ((cx - w / 2) * W).toInt(); val x1 = ((cx + w / 2) * W).toInt()
            val y0 = ((y - h) * H).toInt(); val y1 = (y * H).toInt()
            Imgproc.rectangle(img, Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()), sc(car.rgb), -1)
            Imgproc.rectangle(img, Point((x0 + (x1 - x0) / 6).toDouble(), (y0 + (y1 - y0) / 6).toDouble()),
                Point((x1 - (x1 - x0) / 6).toDouble(), (y0 + (y1 - y0) / 2).toDouble()), Scalar(30.0, 30.0, 30.0), -1)
            Imgproc.rectangle(img, Point(x0.toDouble(), (y1 - (y1 - y0) / 8).toDouble()), Point(x1.toDouble(), y1.toDouble()), Scalar(20.0, 20.0, 20.0), -1)
            // El detector también mira la franja sobre el borde lejano (franja de horizonte): la caja real llega hasta allí.
            truth[car.lane] = RectN(x0.toFloat() / W, max(y0.toFloat() / H, road.farY - DetectorParams().horizonBandFrac), x1.toFloat() / W, y1.toFloat() / H)
        }
        if (withPlayer) {
            val pw = playerBox.width
            val cx = road.laneCenterX(playerLane, playerBox.bottom)
            Imgproc.rectangle(img, Point(((cx - pw / 2) * W).toDouble(), playerBox.top * H.toDouble()),
                Point(((cx + pw / 2) * W).toDouble(), playerBox.bottom * H.toDouble()), Scalar(200.0, 60.0, 30.0), -1)
        }
        return img to truth
    }

    private fun iou(a: RectN, b: RectN): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val u = a.width * a.height + b.width * b.height - inter
        return if (u > 0) inter / u else 0f
    }

    private fun fmt(b: RectN) = "[%.3f,%.3f,%.3f,%.3f]".format(b.left, b.top, b.right, b.bottom)

    private fun detector(): OpenCvObstacleDetector {
        val (ref, _) = render(emptyList(), Lane.CENTER, seed = 99)
        val grow = RectN(playerBox.left - 0.02f, playerBox.top - 0.02f, playerBox.right + 0.02f, playerBox.bottom + 0.02f)
        val model = RoadColorModel.learn(ref, road, listOf(grow))
        ref.release()
        return OpenCvObstacleDetector(profile(), model)
    }

    @Test
    fun emptyRoadsProduceNoDetections() {
        val det = detector()
        var fp = 0
        for (seed in 0 until 20) {
            val (img, _) = render(emptyList(), Lane.values()[seed % 3], seed = 1000L + seed)
            fp += det.detect(img).detections.size
            img.release()
        }
        assertEquals("Falsos positivos en carreteras vacías", 0, fp)
    }

    @Test
    fun coloredCarsAreDetectedInTheRightLane() {
        val det = detector()
        val rnd = Random(5)
        var total = 0; var hits = 0; var falsePos = 0
        val missed = ArrayList<String>()
        val names = COLORS.keys.toList()
        for (trial in 0 until 60) {
            val lanes = Lane.values().toList().shuffled(rnd).take(1 + rnd.nextInt(3))
            val cars = lanes.map { Car(it, 1.8f + rnd.nextFloat() * 2.1f, COLORS.getValue(names[rnd.nextInt(names.size)])) }
            val pl = Lane.values()[rnd.nextInt(3)]
            // Cada prueba es independiente: se reinicia el fondo temporal y se calienta con la carretera vacía
            // (como en el juego real, donde la vía se ve libre antes de que aparezca un coche).
            det.resetBackground()
            for (w in 0 until 3) { val (e, _) = render(emptyList(), pl, seed = 5000L + trial * 10 + w); det.detect(e); e.release() }
            val (img, truth) = render(cars, pl, seed = trial.toLong())
            val dets = det.detect(img).detections
            img.release()
            val used = HashSet<Int>()
            for ((lane, box) in truth) {
                total++
                val idx = dets.indices.filter { it !in used }.maxByOrNull { iou(dets[it].box, box) }
                if (idx != null && iou(dets[idx].box, box) >= 0.5f) { hits++; used += idx }
                else {
                    val c = cars.first { it.lane == lane }
                    missed += "t$trial/$lane d=${c.depthU} rgb=${c.rgb.toList()} box=${fmt(box)} dets=${dets.map { fmt(it.box) }}"
                }
            }
            falsePos += dets.size - used.size
        }
        assertTrue("Recall $hits/$total demasiado bajo; fallos: ${missed.joinToString(" | ")}", hits.toFloat() / total >= 0.95f)
        assertEquals("Falsos positivos", 0, falsePos)
    }

    @Test
    fun playerLaneIsLocated() {
        val det = detector()
        for (lane in Lane.entries) {
            val (img, _) = render(emptyList(), lane, seed = 7L + lane.index)
            val r = det.detect(img)
            img.release()
            assertEquals("Carril del jugador", lane, r.player.lane)
        }
    }

    @Test
    fun noPlayerMeansNoPlayerLane() {
        val det = detector()
        val (img, _) = render(emptyList(), Lane.CENTER, seed = 3, withPlayer = false)
        val r = det.detect(img)
        img.release()
        assertEquals(null, r.player.lane)
    }

    @Test
    fun colorModelRoundTripsThroughArray() {
        val (ref, _) = render(emptyList(), Lane.CENTER, seed = 99)
        val m = RoadColorModel.learn(ref, road, emptyList())
        ref.release()
        val back = RoadColorModel.fromFloatArray(m.toFloatArray())
        assertEquals(m.bands, back.bands)
        assertEquals(80, m.toFloatArray().size)
    }
}
