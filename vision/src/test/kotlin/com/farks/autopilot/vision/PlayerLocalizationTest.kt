package com.farks.autopilot.vision

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Localización del vehículo del jugador con el detector REAL (OpenCV) sobre la captura real aportada por el usuario
 * (limpia de la superposición de calibración; ver tools/make_player_fixtures.py) y composiciones con el sprite real del
 * coche en otros carriles. Es el equivalente, con OpenCV, de PlayerLocatorTest del módulo :core.
 */
class PlayerLocalizationTest {
    companion object {
        @JvmStatic @BeforeClass
        fun loadOpenCv() { nu.pattern.OpenCV.loadLocally() }

        val road = RoadGeometry(nearLeft = PointN(0.0088f, 0.9048f), nearRight = PointN(1.0f, 0.9048f),
            farLeft = PointN(0.261f, 0.4875f), farRight = PointN(0.7134f, 0.4905f))
        val box = RectN(0.3503f, 0.6449f, 0.6526f, 0.7834f)
        val profile = GameProfile("test", "Test", road, box, Lane.CENTER)
        const val SPRITE_X0 = 182; const val SPRITE_Y0 = 565
    }

    private class Raster(val w: Int, val h: Int, val rgba: ByteArray)

    private fun read(name: String): Raster {
        val bi = ImageIO.read(javaClass.getResourceAsStream("/player/$name"))
        val out = ByteArray(bi.width * bi.height * 4)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = bi.getRGB(x, y); val o = (y * bi.width + x) * 4
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte(); out[o + 3] = (p ushr 24).toByte()
        }
        return Raster(bi.width, bi.height, out)
    }

    private val ref = read("reference_with_car.png")
    private val empty = read("empty_road.png")
    private val sprite = read("car_sprite.png")

    /** Composición: el sprite desplazado a otro carril (y opcionalmente recoloreado). */
    private fun compose(base: Raster, dx: Int, recolor: Boolean = false, sprites: List<Int> = listOf(dx)): Raster {
        val out = Raster(base.w, base.h, base.rgba.copyOf())
        for (sdx in sprites) for (y in 0 until sprite.h) for (x in 0 until sprite.w) {
            val so = (y * sprite.w + x) * 4
            if ((sprite.rgba[so + 3].toInt() and 0xFF) < 128) continue
            val px = SPRITE_X0 + x + sdx; val py = SPRITE_Y0 + y
            if (px < 0 || px >= out.w || py < 0 || py >= out.h) continue
            val o = (py * out.w + px) * 4
            val r = sprite.rgba[so].toInt() and 0xFF; val g = sprite.rgba[so + 1].toInt() and 0xFF; val b = sprite.rgba[so + 2].toInt() and 0xFF
            if (recolor) { out.rgba[o] = (255 - g).toByte(); out.rgba[o + 1] = (255 - b).toByte(); out.rgba[o + 2] = (255 - r).toByte() }
            else { out.rgba[o] = r.toByte(); out.rgba[o + 1] = g.toByte(); out.rgba[o + 2] = b.toByte() }
            out.rgba[o + 3] = -1
        }
        return out
    }

    private fun mat(r: Raster): Mat { val m = Mat(r.h, r.w, CvType.CV_8UC4); m.put(0, 0, r.rgba); return m }

    private fun shift(from: Lane, to: Lane) =
        ((road.laneCenterX(to, box.bottom) - road.laneCenterX(from, box.bottom)) * ref.w).roundToInt()

    /** Calibra como la app: modelo con la zona del vehículo excluida, apariencia del vehículo, y detector con ella. */
    private fun calibrate(): OpenCvObstacleDetector {
        val m = mat(ref)
        val model = RoadColorModel.learn(m, road, listOf(profile.playerZone))
        val learned = OpenCvObstacleDetector(profile, model).learnPlayer(m)
        m.release()
        println(learned.message)
        assertNotNull(learned.message, learned.look)
        return OpenCvObstacleDetector(profile.copy(playerLook = learned.look), model)
    }

    @Test
    fun referenceCarIsLocatedWhereItWasLearned() {
        val det = calibrate()
        val m = mat(ref)
        val r = det.detect(m); m.release()
        println(r.player.diag?.reason)
        assertEquals(r.player.diag?.reason, Lane.CENTER, r.player.lane)
        assertTrue("confianza ${r.player.confidence}", r.player.confidence > 0.8f)
        assertEquals("el coche del jugador no debe verse como obstáculo", 0, r.detections.size)
    }

    @Test
    fun carIsLocatedInEveryLane_andNeverCountedAsObstacle() {
        val det = calibrate()
        for (lane in Lane.entries) {
            val m = mat(compose(empty, shift(Lane.CENTER, lane)))
            val r = det.detect(m); m.release()
            assertEquals("$lane: ${r.player.diag?.reason}", lane, r.player.lane)
            assertEquals("$lane: el vehículo del jugador se detectó como obstáculo", 0, r.detections.size)
        }
    }

    @Test
    fun otherColourCarBesidePlayerDoesNotConfuse() {
        val det = calibrate()
        val img = compose(compose(empty, 0), shift(Lane.CENTER, Lane.LEFT), recolor = true)
        val m = mat(img)
        val r = det.detect(m); m.release()
        assertEquals(r.player.diag?.reason, Lane.CENTER, r.player.lane)
    }

    @Test
    fun otherColourCarAloneIsNotThePlayer() {
        val det = calibrate()
        for (lane in Lane.entries) {
            val m = mat(compose(empty, shift(Lane.CENTER, lane), recolor = true))
            val r = det.detect(m); m.release()
            assertNull("$lane aceptado como jugador: ${r.player.diag?.reason}", r.player.lane)
        }
    }

    @Test
    fun emptyRoadHasNoPlayerAndExplainsWhy() {
        val det = calibrate()
        val m = mat(empty)
        val r = det.detect(m); m.release()
        assertNull(r.player.lane)
        assertTrue(r.player.diag!!.reason, r.player.diag!!.reason.contains("no hay píxeles"))
    }
}
