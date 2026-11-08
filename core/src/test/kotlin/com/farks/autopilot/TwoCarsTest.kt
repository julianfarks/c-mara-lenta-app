package com.farks.autopilot

import com.farks.autopilot.engine.AutopilotEngine
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dos coches a la vez (centro y un lateral) con el otro lateral libre: debe ir SIEMPRE al hueco libre. */
class TwoCarsTest {
    private fun runCase(speed: Float, other: Lane, dt: Float): Triple<Lane?, Int, List<Lane>> {
        val obs = listOf(SimObstacle(Lane.CENTER, 1.0f, speed), SimObstacle(other, 1.0f + dt * 0.1f, speed))
        val world = World(
            TestGeo.profile, obs, Lane.CENTER, dtMs = 33, laneChangeMs = 250, frameLatencyMs = 70,
            actuationDelayMs = 100, visibleFromY = 0.315f, midpointObservation = true, seed = 3,
        )
        val engine = AutopilotEngine(TestGeo.profile, EngineConfig(laneChangeSec = 0.25f))
        val res = runClosedLoop(world, engine, 5f)
        return Triple(res.commands.firstOrNull()?.target, world.collisions, res.commands.map { it.target })
    }

    @Test fun goesToTheEmptyLane() {
        // Casos en que el segundo coche es visible (o casi) cuando hay que decidir. Con desfases mayores o velocidades
        // extremas el segundo coche aparece DESPUÉS de la orden y no hay información para elegir mejor (limitación real).
        val bad = ArrayList<String>()
        for (speed in listOf(2.0f, 2.6f, 3.2f)) for (other in listOf(Lane.RIGHT, Lane.LEFT)) for (dt in listOf(-3f, -1f, 0f)) {
            val empty = if (other == Lane.RIGHT) Lane.LEFT else Lane.RIGHT
            val (first, coll, all) = runCase(speed, other, dt)
            if (first != empty || coll > 0) bad += "v=$speed otro=$other desfase=${dt * 0.1f}s -> primera=$first choques=$coll todas=$all"
        }
        bad.forEach { println("MAL $it") }
        assertTrue("casos mal: ${bad.size}", bad.isEmpty())
    }
}
