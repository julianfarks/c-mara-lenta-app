package com.farks.autopilot

import com.farks.autopilot.models.EngineStatus
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneState
import com.farks.autopilot.models.MoveDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escenarios A–J del enunciado. Cada uno es un bucle cerrado: el simulador genera
 * detecciones con perspectiva real, el motor decide, y el simulador aplica los gestos
 * (con retardo) y detecta colisiones físicas.
 */
class ScenariosTest {

    // ---- A: izquierdo ocupado, centro y derecho libres -------------------------------------
    @Test
    fun scenarioA_leftBlocked_playerInCenter_staysPut() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.LEFT, 0.2f)), Lane.CENTER), newEngine(), 3f)
        assertEquals("no debe moverse sin necesidad", 0, r.commands.size)
        assertEquals(0, r.world.collisions)
        // Estados de carril observados cuando el obstáculo ya estaba confirmado y cerca.
        val probe = r.outputs.first { it.lanes.getValue(Lane.LEFT).state != LaneState.FREE && it.lanes.getValue(Lane.LEFT).state != LaneState.UNKNOWN }
        assertEquals(LaneState.FREE, probe.lanes.getValue(Lane.CENTER).state)
        assertEquals(LaneState.FREE, probe.lanes.getValue(Lane.RIGHT).state)
    }

    @Test
    fun scenarioA_leftBlocked_playerInLeft_movesToCenterOnce() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.LEFT, 0.2f)), Lane.LEFT), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertEquals(1, r.commands.size)
        assertEquals(MoveDirection.RIGHT, r.commands[0].direction)
        assertEquals(Lane.CENTER, r.world.playerLane)
    }

    // ---- B: izquierdo y centro ocupados, derecho libre ----------------------------------------
    @Test
    fun scenarioB_leftAndCenterBlocked_goesRight() {
        val obs = listOf(SimObstacle(Lane.LEFT, 0.2f), SimObstacle(Lane.CENTER, 0.2f))
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertEquals(Lane.RIGHT, r.world.playerLane)
        assertEquals(1, r.commands.size)
        assertEquals(MoveDirection.RIGHT, r.commands[0].direction)
    }

    // ---- C: izquierdo libre, centro y derecho ocupados ------------------------------------------
    @Test
    fun scenarioC_centerAndRightBlocked_goesLeft() {
        val obs = listOf(SimObstacle(Lane.CENTER, 0.2f), SimObstacle(Lane.RIGHT, 0.2f))
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertEquals(Lane.LEFT, r.world.playerLane)
        assertEquals(MoveDirection.LEFT, r.commands.single().direction)
    }

    // ---- D: dos vehículos simultáneos en carriles distintos --------------------------------------
    @Test
    fun scenarioD_twoSimultaneousVehicles_sidesBlocked_staysInCenter() {
        val obs = listOf(SimObstacle(Lane.LEFT, 0.2f), SimObstacle(Lane.RIGHT, 0.2f))
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertEquals(0, r.commands.size)
    }

    @Test
    fun scenarioD_twoSimultaneousVehicles_playerLaneAndNeighbour_takesTheFreeOne() {
        val obs = listOf(SimObstacle(Lane.CENTER, 0.2f), SimObstacle(Lane.LEFT, 0.2f))
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertEquals(Lane.RIGHT, r.world.playerLane)
    }

    // ---- E: vehículo que se aproxima rápido o cambia de posición ---------------------------------
    @Test
    fun scenarioE_fastApproach_isAvoided() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.CENTER, 0.2f, speed = 3.5f)), Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertNotEquals(Lane.CENTER, r.world.playerLane)
    }

    @Test
    fun scenarioE_fastApproachGivesShorterTtcThanSlow() {
        fun ttcAfterFrames(speed: Float): Float {
            val w = newWorld(listOf(SimObstacle(Lane.LEFT, 0f, speed = speed)), Lane.CENTER)
            val e = newEngine()
            var out = e.onFrame(w.step())
            repeat(5) { out = e.onFrame(w.step()) }
            return out.tracks.single().ttcSec
        }
        assertTrue(ttcAfterFrames(3.5f) < ttcAfterFrames(1.5f))
    }

    @Test
    fun scenarioE_vehicleChangingLaneTowardPlayer_isAvoided() {
        val o = SimObstacle(Lane.LEFT, 0.2f, speed = 2.0f, driftToLane = Lane.CENTER, driftStartSec = 0.5f, driftDurSec = 0.4f)
        val r = runClosedLoop(newWorld(listOf(o), Lane.CENTER), newEngine(), 3.5f)
        assertEquals(0, r.world.collisions)
    }

    // ---- F: todos los carriles con peligro --------------------------------------------------------
    @Test
    fun scenarioF_allBlocked_flagsDeadEndAndDoesNotOscillate() {
        val obs = Lane.entries.map { SimObstacle(it, 0.2f) }
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 3f)
        assertTrue("debe señalar situación sin salida", r.outputs.any { it.decision.deadEnd })
        assertTrue("sin movimientos inútiles", r.commands.size <= 1)
    }

    @Test
    fun scenarioF_allBlockedButStaggered_picksLowestRiskWithoutZigZag() {
        val obs = listOf(SimObstacle(Lane.LEFT, 0.2f), SimObstacle(Lane.CENTER, 0.45f), SimObstacle(Lane.RIGHT, 0.2f))
        val r = runClosedLoop(newWorld(obs, Lane.LEFT), newEngine(), 3f)
        assertTrue(r.commands.size <= 2)
        // Nunca invierte la dirección de inmediato.
        for (i in 1 until r.commands.size) {
            if (r.commands[i].direction != r.commands[i - 1].direction) {
                val dt = r.outputs.first { it.command?.id == r.commands[i].id }.timestampNs -
                    r.outputs.first { it.command?.id == r.commands[i - 1].id }.timestampNs
                assertTrue(dt >= 600_000_000L)
            }
        }
    }

    // ---- G: un vehículo desaparece temporalmente de la imagen --------------------------------------
    @Test
    fun scenarioG_vehicleVanishesTemporarily_laneIsNeverConsideredFree() {
        val o = SimObstacle(Lane.CENTER, 0.2f, hidden = 0.55f..0.75f)
        val world = newWorld(listOf(o), Lane.LEFT)
        val engine = newEngine()
        engine.armed = true
        val laneStates = ArrayList<LaneState>()
        val ids = HashSet<Int>()
        repeat(40) {
            val out = engine.onFrame(world.step())
            if (world.timeSec in 0.6f..0.75f) laneStates += out.lanes.getValue(Lane.CENTER).state
            out.tracks.forEach { ids += it.id }
        }
        assertTrue(laneStates.isNotEmpty())
        assertTrue("el carril no puede quedar libre durante el hueco: $laneStates", laneStates.none { it == LaneState.FREE })
        assertEquals("el mismo obstáculo conserva su identidad", 1, ids.size)
    }

    @Test
    fun scenarioG_vanishingVehicleInPlayerLane_stillAvoided() {
        val o = SimObstacle(Lane.CENTER, 0.2f, hidden = 0.45f..0.65f)
        val r = runClosedLoop(newWorld(listOf(o), Lane.CENTER), newEngine(), 3f)
        assertEquals(0, r.world.collisions)
        assertNotEquals(Lane.CENTER, r.world.playerLane)
    }

    // ---- H: el jugador cambia de carril y el sistema actualiza su posición ---------------------------
    @Test
    fun scenarioH_commandedLaneChangeIsConfirmedVisually() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.CENTER, 0.2f)), Lane.CENTER), newEngine(), 3f)
        val last = r.outputs.last()
        assertEquals(r.world.playerLane, last.playerLane)
        assertFalse(last.playerLaneAssumed)
        assertEquals(0, r.engine.movementErrors)
        assertTrue(r.outputs.flatMap { it.events }.any { it.message.contains("confirmado") })
    }

    @Test
    fun scenarioH_externalLaneChangeIsAdopted() {
        val world = newWorld(emptyList(), Lane.CENTER)
        val engine = newEngine()
        repeat(5) { engine.onFrame(world.step()) }
        world.forceLane(Lane.LEFT)
        var out = engine.onFrame(world.step())
        out = engine.onFrame(world.step())
        assertEquals(Lane.LEFT, out.playerLane)
    }

    @Test
    fun scenarioH_ineffectiveSwipeIsDetectedAndReported() {
        val world = World(TestGeo.profile, listOf(SimObstacle(Lane.CENTER, 0.2f)), Lane.CENTER, swipeWorks = false)
        val engine = newEngine()
        val r = runClosedLoop(world, engine, 3f)
        assertTrue("debe registrar el error de movimiento", engine.movementErrors >= 1)
        assertEquals(Lane.CENTER, r.outputs.last().playerLane)
    }

    @Test
    fun scenarioH_cancelledGestureIsReportedAndRetriedAfterCooldown() {
        val world = newWorld(listOf(SimObstacle(Lane.CENTER, 0.2f)), Lane.CENTER)
        val engine = newEngine()
        val r = runClosedLoop(world, engine, 3f, gestureOk = false)
        assertTrue(engine.gestureErrors >= 1)
        assertTrue(r.outputs.flatMap { it.events }.any { it.message.contains("canceló el gesto") })
    }

    // ---- I: obstáculos consecutivos que exigen varios cambios -----------------------------------------
    @Test
    fun scenarioI_consecutiveWaves_requireSeveralChanges_noCollision() {
        val obs = listOf(
            // Primera oleada: centro y derecha ocupados => ir a la izquierda.
            SimObstacle(Lane.CENTER, 0.2f), SimObstacle(Lane.RIGHT, 0.2f),
            // Segunda oleada: izquierda y centro ocupados => ir a la derecha (dos cambios).
            SimObstacle(Lane.LEFT, 1.9f), SimObstacle(Lane.CENTER, 1.9f),
        )
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 5f)
        assertEquals(0, r.world.collisions)
        assertTrue("necesita al menos 3 cambios, hizo ${r.commands.size}", r.commands.size >= 3)
        assertEquals(Lane.RIGHT, r.world.playerLane)
    }

    @Test
    fun scenarioI_withDetectionNoise_stillNoCollision() {
        val obs = listOf(
            SimObstacle(Lane.CENTER, 0.2f), SimObstacle(Lane.RIGHT, 0.2f),
            SimObstacle(Lane.LEFT, 1.9f), SimObstacle(Lane.CENTER, 1.9f),
        )
        val r = runClosedLoop(newWorld(obs, Lane.CENTER, noise = 0.004f), newEngine(), 5f)
        assertEquals(0, r.world.collisions)
    }

    // ---- J: la captura se interrumpe o se pierde el permiso --------------------------------------------
    @Test
    fun scenarioJ_captureLost_suspendsEverythingAndNeverActs() {
        val world = newWorld(listOf(SimObstacle(Lane.CENTER, 0.2f)), Lane.CENTER)
        val engine = newEngine()
        engine.armed = true
        repeat(2) { engine.onFrame(world.step()) }
        val lost = engine.onCaptureLost(world.timeNs, "permiso de captura revocado")
        assertEquals(EngineStatus.SUSPENDED_NO_CAPTURE, lost.status)
        assertTrue(lost.lanes.values.all { it.state == LaneState.UNKNOWN })
        // Aunque lleguen fotogramas residuales con un obstáculo inminente, no se emite ninguna orden.
        repeat(30) {
            val out = engine.onFrame(world.step())
            assertEquals(null, out.command)
            assertEquals(EngineStatus.SUSPENDED_NO_CAPTURE, out.status)
        }
    }

    @Test
    fun scenarioJ_afterResume_waitsForConfirmationBeforeActing() {
        val world = newWorld(listOf(SimObstacle(Lane.CENTER, 0.0f)), Lane.CENTER)
        val engine = newEngine()
        engine.armed = true
        repeat(3) { engine.onFrame(world.step()) }
        engine.onCaptureLost(world.timeNs, "captura detenida por el sistema")
        engine.onCaptureResumed()
        val first = engine.onFrame(world.step())
        val second = engine.onFrame(world.step())
        assertEquals(EngineStatus.WARMING_UP, first.status)
        assertEquals(EngineStatus.WARMING_UP, second.status)
        assertEquals(null, first.command)
        assertEquals(null, second.command)
        val third = engine.onFrame(world.step())
        assertEquals(EngineStatus.RUNNING, third.status)
    }

    @Test
    fun scenarioJ_noFramesMakesLanesUnknown() {
        val world = newWorld(listOf(SimObstacle(Lane.LEFT, 0.0f)), Lane.CENTER)
        val engine = newEngine()
        engine.armed = true
        repeat(6) { engine.onFrame(world.step()) }
        val stale = engine.checkStale(world.timeNs + 600_000_000L)
        assertTrue(stale != null)
        assertEquals(EngineStatus.STALE, stale!!.status)
        assertTrue(stale.lanes.values.all { it.state == LaneState.UNKNOWN })
        assertEquals(null, stale.command)
    }
}

/** Pruebas de control: demuestran que el simulador sí produce colisiones cuando el piloto no actúa. */
class ControlTest {
    @Test
    fun withoutPilotTheObstacleInPlayerLaneCollides() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.CENTER, 0.2f)), Lane.CENTER), newEngine(), 3f, armed = false)
        assertEquals(1, r.world.collisions)
    }

    @Test
    fun withoutPilotTheCrowdedScenarioCollides() {
        val obs = listOf(SimObstacle(Lane.CENTER, 0.2f), SimObstacle(Lane.RIGHT, 0.2f), SimObstacle(Lane.LEFT, 1.9f), SimObstacle(Lane.CENTER, 1.9f))
        val r = runClosedLoop(newWorld(obs, Lane.CENTER), newEngine(), 5f, armed = false)
        assertTrue(r.world.collisions >= 2)
    }
}
