package com.farks.autopilot

import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de la optimización de reacción (latencias modeladas; simulación, no un juego real). */
class ReactionTest {
    private val base = EngineConfig(laneChangeSec = 0.25f)
    private val real = LatencyStudy.Conditions(visibleFromY = 0.315f)

    private fun world(obs: List<SimObstacle>, k: LatencyStudy.Conditions = real, start: Lane = Lane.CENTER) = World(
        TestGeo.profile, obs, start, dtMs = k.dtMs, laneChangeMs = k.laneChangeMs, frameLatencyMs = k.frameLatencyMs,
        actuationDelayMs = k.actuationDelayMs, visibleFromY = k.visibleFromY, midpointObservation = true,
    )

    @Test
    fun timelineIsOrderedAndComplete() {
        val r = runClosedLoop(world(listOf(SimObstacle(Lane.CENTER, 0.3f, 2.2f))), newEngine(base), 6f)
        assertEquals(0, r.world.collisions)
        val rec = r.outputs.last().reactions.first()
        assertTrue(rec.firstSeenNs!! <= rec.confirmedNs!!)
        assertTrue(rec.confirmedNs!! <= rec.decisionNs)
        assertTrue(rec.detectToDecisionMs!! >= 0f)
        assertTrue("ttc en la decisión debe ser positivo", rec.ttcAtDecisionSec!! > 0f)
    }

    @Test
    fun decidesBeforeDanger_marginPositive() {
        val r = runClosedLoop(world(listOf(SimObstacle(Lane.CENTER, 0.3f, 2.2f))), newEngine(base), 6f)
        val rec = r.outputs.last().reactions.first()
        val m = rec.marginAtChangeSec
        assertTrue("el cambio debe verse completado antes de la llegada (margen=$m)", m == null || m > 0f)
    }

    @Test
    fun neverMovesIntoOccupiedLane() {
        // Centro y derecha llegan juntos: debe ir a la izquierda; nunca a un carril ocupado.
        val obs = listOf(SimObstacle(Lane.CENTER, 0.3f, 2.2f), SimObstacle(Lane.RIGHT, 0.3f, 2.2f))
        val r = runClosedLoop(world(obs), newEngine(base), 6f)
        assertEquals(0, r.world.collisions)
        assertEquals(Lane.LEFT, r.world.playerLane)
        assertEquals(1, r.commands.size)
    }

    @Test
    fun noDuplicateOrdersForOneObstacle() {
        val r = runClosedLoop(world(listOf(SimObstacle(Lane.CENTER, 0.3f, 2.0f))), newEngine(base), 6f)
        assertEquals(1, r.commands.size)
    }

    @Test
    fun singleFramePhantomNeverMoves() {
        var moves = 0
        for (seed in 0L until 40L) {
            val ph = listOf(Triple(1.0f + (seed % 7) * 0.5f, Lane.CENTER, 1))
            val w = World(TestGeo.profile, emptyList(), Lane.CENTER, dtMs = 33, frameLatencyMs = 70, actuationDelayMs = 100,
                midpointObservation = true, phantoms = ph, seed = seed)
            moves += runClosedLoop(w, newEngine(base), 6f).commands.size
        }
        assertEquals("un parpadeo de 1 fotograma no debe provocar movimientos", 0, moves)
    }

    @Test
    fun newEngineNotWorseThanLegacyUnderLatency() {
        val seeds = 0 until 60
        val n = LatencyStudy.run("nuevo", base, real, seeds)
        val o = LatencyStudy.run("legacy", EngineConfig.legacy(base), real, seeds)
        println(n.summary("nuevo")); println(o.summary("legacy"))
        assertTrue("choques nuevo=${n.collisions} legacy=${o.collisions}", n.collisions <= o.collisions)
        assertTrue("movimientos innecesarios nuevo=${n.needlessMoves}", n.needlessMoves <= o.needlessMoves + 3)
    }

    @Test
    fun learnsActuationDelay() {
        val k = real
        val e = newEngine(base)
        var any = false
        for (seed in 0 until 8) {
            val w = world(LatencyStudy.scenario(seed.toLong()), k)
            val r = runClosedLoop(w, e, 12f)
            any = any || r.outputs.last().latency.actuationSamples > 0
        }
        assertTrue(any)
    }
}
