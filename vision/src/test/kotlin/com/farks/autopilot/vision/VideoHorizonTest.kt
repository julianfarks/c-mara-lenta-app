package com.farks.autopilot.vision

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import javax.imageio.ImageIO

/**
 * Fotogramas REALES de una grabación de Lane Rush (aportada por el usuario). Un coche naranja aparece sobre la línea del
 * horizonte hacia t=4.4 s en el carril central y golpea al jugador a t≈5.37 s. Sin la franja de horizonte el detector
 * solo lo veía cuando su parte baja entraba en el asfalto (≈5.0 s). Se alimentan los fotogramas en orden, como el servicio.
 */
class VideoHorizonTest {
    companion object {
        @JvmStatic @BeforeClass
        fun loadOpenCv() { nu.pattern.OpenCV.loadLocally() }

        val road = RoadGeometry(nearLeft = PointN(0.010f, 0.931f), nearRight = PointN(1.0f, 0.931f),
            farLeft = PointN(0.262f, 0.629f), farRight = PointN(0.714f, 0.632f))
        val box = RectN(0.351f, 0.743f, 0.654f, 0.843f)
        val profile = GameProfile("lane.rush", "Lane Rush", road, box, Lane.CENTER)
        val times = listOf("2.60", "3.20", "3.80", "4.20", "4.39", "4.50", "4.70", "4.90", "5.10")
    }

    private fun frame(t: String): Mat {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/video/f_$t.jpg"))
        val out = ByteArray(bi.width * bi.height * 4)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = bi.getRGB(x, y); val o = (y * bi.width + x) * 4
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte(); out[o + 3] = -1
        }
        val m = Mat(bi.height, bi.width, CvType.CV_8UC4); m.put(0, 0, out); return m
    }

    private fun run(params: DetectorParams): Map<String, List<Detection>> {
        val ref = frame("2.60")
        val model = RoadColorModel.learn(ref, road, listOf(profile.playerZone))
        ref.release()
        val det = OpenCvObstacleDetector(profile, model, null, params)
        val res = LinkedHashMap<String, List<Detection>>()
        for (t in times) {
            val m = frame(t)
            res[t] = det.detect(m).detections
            m.release()
        }
        return res
    }

    private fun centre(d: List<Detection>) = d.filter { it.box.centerX in 0.40f..0.60f }

    @Test
    fun horizonBandSeesTheCarWhileItIsStillAboveTheRoad() {
        val r = run(DetectorParams())
        r.forEach { (t, d) -> println("t=$t detecciones centrales=${centre(d).map { it.box }}") }
        for (t in listOf("2.60", "3.20", "3.80", "4.20")) assertTrue("falso positivo central en t=$t", centre(r.getValue(t)).isEmpty())
        val seen = centre(r.getValue("4.50"))
        assertTrue("el coche debe verse en t=4.50 (≈0.9 s antes del choque)", seen.isNotEmpty())
        assertTrue("su parte alta está sobre el horizonte", seen.any { it.box.top < road.farY })
        assertTrue(centre(r.getValue("4.70")).isNotEmpty())
    }

    @Test
    fun withoutTheBandTheCarIsSeenMuchLater() {
        val r = run(DetectorParams(horizonBandFrac = 0f))
        assertTrue("sin franja no debería verse aún en t=4.50", centre(r.getValue("4.50")).isEmpty())
        assertNotNull(r["5.10"])
    }
}
