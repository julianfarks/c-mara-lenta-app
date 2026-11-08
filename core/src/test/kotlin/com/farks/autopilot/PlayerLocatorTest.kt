package com.farks.autopilot

import com.farks.autopilot.calibration.ProfileCodec
import com.farks.autopilot.calibration.StoredProfile
import com.farks.autopilot.models.Lane
import com.farks.autopilot.player.PlayerLearnResult
import com.farks.autopilot.player.PlayerLocator
import com.farks.autopilot.player.PlayerLookLearner
import com.farks.autopilot.player.PlayerLook
import com.farks.autopilot.player.WorkGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Localización del vehículo del jugador sobre una captura REAL (la que aportó el usuario, limpia de la superposición
 * de calibración; ver tools/make_player_fixtures.py) y composiciones con el sprite real del coche en otras posiciones.
 * Las composiciones usan la carretera vacía real: no son un juego en ejecución, pero sí píxeles reales.
 */
class PlayerLocatorTest {
    private val K = PlayerTestKit
    private val ref = K.load("reference_with_car.png")
    private val empty = K.load("empty_road.png")
    private val sprite = K.loadSprite()
    private val prof = K.profile()
    private val roadModel = K.learnRoad(ref, K.road, listOf(prof.playerZone))

    private fun learn(img: Img = ref, p: com.farks.autopilot.calibration.GameProfile = prof, model: List<PlayerTestKit.Band> = roadModel): PlayerLearnResult {
        val g = K.grid(img, p, model)
        return PlayerLookLearner.learn(g, p.road, p.playerBox, p.playerZoneBottom, p.playerLane)
    }

    private fun locator(look: PlayerLook, p: com.farks.autopilot.calibration.GameProfile = prof) =
        PlayerLocator(p.road, p.playerBox, p.playerZoneBottom, look)

    private fun obs(img: Img, loc: PlayerLocator, p: com.farks.autopilot.calibration.GameProfile = prof, model: List<PlayerTestKit.Band> = roadModel) =
        loc.locate(K.grid(img, p, model))

    private val look by lazy { learn().look!! }

    private fun at(lane: Lane, base: Img = empty, dxExtra: Int = 0, dy: Int = 0, scale: Float = 1f, recolor: ((Int, Int, Int) -> IntArray)? = null) =
        K.place(base, sprite, K.laneShiftPx(empty.w, Lane.CENTER, lane) + dxExtra, dy, scale, recolor)

    private val shiftHue: (Int, Int, Int) -> IntArray = { r, g, b -> intArrayOf(b, r, g) }          // verde -> rojo, crema -> azulado
    private val vivid: (Int, Int, Int) -> IntArray = { r, g, b -> intArrayOf(255 - g, 255 - b, 255 - r) } // inversión: nada coincide con el original

    // ----------------------------------------------------------------- causa del fallo original

    @Test
    fun rootCause_oldSingleMedianColorGaveNoClearWinner() {
        // Réplica del algoritmo ANTERIOR: color único = mediana Lab del 60 % central de la caja; después, por carril,
        // fracción de píxeles de la caja que coinciden (±34 L, ±26 crominancia) y exigencia de ganador >= 0.15 y >= 1.5x el segundo.
        val g = K.grid(ref, prof, roadModel)
        val b = prof.playerBox
        val x0 = ((b.left + 0.2f * b.width) * ref.w).toInt(); val x1 = ((b.right - 0.2f * b.width) * ref.w).toInt()
        val y0 = ((b.top + 0.2f * b.height) * ref.h).toInt(); val y1 = ((b.bottom - 0.2f * b.height) * ref.h).toInt()
        val lab = K.labOf(ref, x0, y0, x1, y1, x1 - x0, y1 - y0)
        fun med(c: Int): Float = (0 until (x1 - x0) * (y1 - y0)).map { lab[it * 3 + c].toInt() and 0xFF }.sorted().let { it[it.size / 2].toFloat() }
        val m = floatArrayOf(med(0), med(1), med(2))
        val fr = Lane.entries.associateWith { lane ->
            val cx = K.road.laneCenterX(lane, b.bottom); val half = b.width * 0.4f
            val c0 = g.col(cx - half).coerceIn(0, g.ww - 1); val c1 = g.col(cx + half).coerceIn(c0 + 1, g.ww)
            val r0 = g.row(b.top).coerceIn(0, g.wh - 1); val r1 = g.row(b.bottom).coerceIn(r0 + 1, g.wh)
            var ok = 0; var tot = 0
            for (r in r0 until r1) for (c in c0 until c1) {
                tot++
                val o = (r * g.ww + c) * 3
                val dL = abs((g.lab[o].toInt() and 0xFF) - m[0])
                val dC = hypot((g.lab[o + 1].toInt() and 0xFF) - m[1], (g.lab[o + 2].toInt() and 0xFF) - m[2])
                if (dL <= 34f && dC <= 26f) ok++
            }
            ok.toFloat() / tot
        }
        println("color único aprendido (L,a,b)=${m.toList()}  fracciones por carril=$fr")
        val sorted = fr.values.sortedDescending()
        val oldWorks = sorted[0] >= 0.15f && sorted[0] >= 1.5f * sorted[1] && fr.maxByOrNull { it.value }!!.key == Lane.CENTER
        assertTrue("el algoritmo anterior NO debería haber localizado el coche con esta captura: $fr", !oldWorks)
    }

    // ----------------------------------------------------------------- aprendizaje

    @Test
    fun learnsFromRealReference_andRecognisesSameCarInSamePlace() {
        val r = learn()
        println(r.message)
        assertNotNull(r.message, r.look)
        val o = obs(ref, locator(r.look!!))
        println(o.diag!!.reason)
        assertEquals(Lane.CENTER, o.lane)
        assertTrue("confianza ${o.confidence}", o.confidence > 0.8f)
    }

    @Test
    fun referenceRegionCoversWholeCar_andReportsMissingBox() {
        // La caja marcada por el usuario se queda corta por abajo (el parachoques sobresale): se detecta y se avisa.
        val r = learn()
        println("faltan abajo=${r.boxMissingBottom} lados=${r.boxMissingSide} fgBox=${r.fgBox}")
        assertTrue("debe detectar que la caja no cubre el parachoques (${r.boxMissingBottom})", r.boxMissingBottom > 0.08f)
        // y aun así el vehículo completo cae dentro de la zona analizada
        val fg = r.fgBox!!
        assertTrue(fg.bottom <= prof.playerZoneBottom + 1e-3f && fg.top >= prof.playerBox.top - 0.01f)
    }

    @Test
    fun coordinatesOfLearningAndVerificationMatch() {
        // Misma rejilla en aprendizaje y verificación: el primer plano del coche cae dentro de la ventana del carril central
        // y su caja coincide con el sprite real (medido al extraerlo: x 0.337-0.680, y 0.650-0.818).
        val r = learn()
        val fg = r.fgBox!!
        // (extremos robustos: no cuentan los retrovisores finos, de ahí la tolerancia lateral mayor)
        assertEquals(0.337f, fg.left, 0.04f); assertEquals(0.680f, fg.right, 0.04f)
        assertEquals(0.650f, fg.top, 0.02f); assertEquals(0.818f, fg.bottom, 0.02f)
    }

    // ----------------------------------------------------------------- distintas posiciones

    @Test
    fun recognisesCarInEachLane() {
        val loc = locator(look)
        for (lane in Lane.entries) {
            val o = obs(at(lane), loc)
            println("$lane -> ${o.lane} ${o.diag!!.reason}")
            assertEquals(lane, o.lane)
            loc.reset()
        }
    }

    @Test
    fun toleratesJitterAndSmallScaleChanges() {
        val loc = locator(look)
        val cases = listOf(
            Triple(8, 0, 1f), Triple(-8, 0, 1f), Triple(0, 6, 1f), Triple(0, -6, 1f), Triple(5, 4, 1f),
            Triple(0, 0, 0.9f), Triple(0, 0, 1.1f), Triple(-6, 3, 1.08f),
        )
        for ((dx, dy, sc) in cases) {
            loc.reset()
            val o = obs(at(Lane.CENTER, dxExtra = dx, dy = dy, scale = sc), loc)
            assertEquals("dx=$dx dy=$dy escala=$sc: ${o.diag!!.reason}", Lane.CENTER, o.lane)
        }
    }

    @Test
    fun toleratesNoise() {
        val loc = locator(look)
        for (seed in 1L..4L) {
            loc.reset()
            val o = obs(K.noise(at(Lane.CENTER), 5f, seed), loc)
            assertEquals(o.diag!!.reason, Lane.CENTER, o.lane)
        }
    }

    @Test
    fun learnsInAnotherLaneAndRecognisesInTheOthers() {
        // Calibrado con el coche en el carril DERECHO (caja desplazada) y verificado en los tres carriles.
        val shift = (K.road.laneCenterX(Lane.RIGHT, K.box.bottom) - K.road.laneCenterX(Lane.CENTER, K.box.bottom))
        val rb = com.farks.autopilot.models.RectN(K.box.left + shift, K.box.top, K.box.right + shift, K.box.bottom)
        val pr = K.profile(Lane.RIGHT, rb)
        val img = at(Lane.RIGHT)
        val model = K.learnRoad(img, K.road, listOf(pr.playerZone))
        val r = learn(img, pr, model)
        assertNotNull(r.message, r.look)
        val loc = locator(r.look!!, pr)
        for (lane in Lane.entries) {
            loc.reset()
            val o = obs(at(lane), loc, pr, model)
            assertEquals("${lane}: ${o.diag!!.reason}", lane, o.lane)
        }
    }

    @Test
    fun sloppyUserBoxStillLearns() {
        // Cajas descentradas o demasiado grandes/pequeñas: el vehículo se aprende por sus píxeles, no por la caja.
        val b = K.box
        val boxes = listOf(
            com.farks.autopilot.models.RectN(b.left + 0.02f, b.top, b.right + 0.02f, b.bottom),
            com.farks.autopilot.models.RectN(b.left - 0.02f, b.top - 0.01f, b.right, b.bottom + 0.03f),
            com.farks.autopilot.models.RectN(b.left + 0.03f, b.top + 0.02f, b.right - 0.03f, b.bottom - 0.03f),
        )
        for (bx in boxes) {
            val p = K.profile(Lane.CENTER, bx)
            val model = K.learnRoad(ref, K.road, listOf(p.playerZone))
            val r = learn(ref, p, model)
            assertNotNull("caja $bx: ${r.message}", r.look)
            val o = obs(ref, locator(r.look!!, p), p, model)
            assertEquals("caja $bx: ${o.diag!!.reason}", Lane.CENTER, o.lane)
        }
    }

    // ----------------------------------------------------------------- no confundir jugador y obstáculos

    @Test
    fun differentColourObstacleBesidePlayerDoesNotConfuse() {
        val loc = locator(look)
        for (recolor in listOf(shiftHue, vivid)) {
            for (obstacleLane in listOf(Lane.LEFT, Lane.RIGHT)) {
                loc.reset()
                val img = K.place(at(Lane.CENTER), sprite, K.laneShiftPx(empty.w, Lane.CENTER, obstacleLane), 0, 1f, recolor)
                val o = obs(img, loc)
                assertEquals("obstáculo en $obstacleLane: ${o.diag!!.reason}", Lane.CENTER, o.lane)
            }
        }
    }

    @Test
    fun obstacleAloneIsNeverTakenForPlayer() {
        // Sin vehículo del jugador, un coche de otro color (mismo tamaño y posición que el jugador) NO debe aceptarse.
        val loc = locator(look)
        for (recolor in listOf(shiftHue, vivid)) for (lane in Lane.entries) {
            loc.reset()
            val o = obs(at(lane, recolor = recolor), loc)
            assertNull("coche de otro color en $lane aceptado como jugador: ${o.diag!!.reason}", o.lane)
        }
    }

    @Test
    fun emptyRoadGivesNoPlayerWithExplanation() {
        val o = obs(empty, locator(look))
        assertNull(o.lane)
        assertTrue(o.diag!!.reason, o.diag!!.reason.contains("no hay píxeles"))
    }

    @Test
    fun identicalCarNextToPlayerIsAmbiguousWithoutHistoryButKeepsLaneWithContinuity() {
        // Un coche idéntico en el carril vecino es indistinguible por apariencia: sin historial no se adivina;
        // con historial (el jugador estaba en CENTER) se conserva por continuidad temporal.
        val both = K.place(at(Lane.CENTER), sprite, K.laneShiftPx(empty.w, Lane.CENTER, Lane.LEFT), 0, 1f, null)
        val fresh = locator(look)
        val o0 = obs(both, fresh)
        println("sin historial: ${o0.diag!!.reason}")
        assertTrue("no debe elegir al azar: ${o0.lane}", o0.lane == null || o0.lane == Lane.CENTER || o0.lane == Lane.LEFT)
        val loc = locator(look)
        assertEquals(Lane.CENTER, obs(at(Lane.CENTER), loc).lane)          // historial: CENTER
        val o = obs(both, loc)
        assertEquals(o.diag!!.reason, Lane.CENTER, o.lane)
    }

    @Test
    fun laneChangeInProgressIsNotMisreportedAsTheOtherLane() {
        val loc = locator(look)
        assertEquals(Lane.CENTER, obs(at(Lane.CENTER), loc).lane)
        val full = K.laneShiftPx(empty.w, Lane.CENTER, Lane.LEFT)
        var last: Lane? = Lane.CENTER
        // El coche recorre del carril central al izquierdo: nunca debe saltar al DERECHO ni oscilar de vuelta.
        for (frac in listOf(0.15f, 0.3f, 0.5f, 0.7f, 0.85f, 1.0f)) {
            val o = obs(K.place(empty, sprite, (full * frac).toInt(), 0), loc)
            assertTrue("fracción $frac: ${o.lane} ${o.diag!!.reason}", o.lane == null || o.lane == Lane.CENTER || o.lane == Lane.LEFT)
            if (last == Lane.LEFT) assertTrue("oscila de vuelta: ${o.lane}", o.lane == Lane.LEFT || o.lane == null)
            if (o.lane != null) last = o.lane
        }
        assertEquals(Lane.LEFT, last)
    }

    // ----------------------------------------------------------------- límites honestos

    @Test
    fun carSameColourAsAsphaltIsReportedNotFaked() {
        // Si el coche fuese del color del asfalto, el aprendizaje debe decirlo en lugar de inventar un resultado.
        val dark = K.place(empty, sprite, 0, 0, 1f) { _, _, _ -> intArrayOf(59, 45, 44) }
        val model = K.learnRoad(dark, K.road, listOf(prof.playerZone))
        val r = learn(dark, prof, model)
        assertNull(r.look)
        assertTrue(r.message, r.message.contains("No se distingue"))
    }

    @Test
    fun lookSurvivesProfileCodec() {
        val st = StoredProfile(prof.copy(playerLook = look), FloatArray(80) { it.toFloat() })
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertNotNull(back)
        val l2 = back!!.profile.playerLook!!
        assertEquals(look.palette.size, l2.palette.size)
        assertEquals(look.areaFrac, l2.areaFrac, 1e-4f)
        // y localiza igual tras recargar
        assertEquals(Lane.CENTER, obs(ref, locator(l2)).lane)
    }
}
