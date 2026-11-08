package com.farks.autopilot

import com.farks.autopilot.automation.PlayerEvent
import com.farks.autopilot.automation.PlayerLaneTracker
import com.farks.autopilot.calibration.LaneAssigner
import com.farks.autopilot.calibration.ProfileCodec
import com.farks.autopilot.calibration.StoredProfile
import com.farks.autopilot.decision.PathPlanner
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneDetection
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.models.MoveDirection
import com.farks.autopilot.models.PlayerObservation
import com.farks.autopilot.models.RectN
import com.farks.autopilot.prediction.ObstacleWindow
import com.farks.autopilot.tracking.ObstacleTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeometryTest {
    private val geo = TestGeo.road

    @Test
    fun horizonIsComputedFromConvergingEdges() {
        val h = geo.horizonY
        assertNotNull(h)
        assertEquals(0.0636f, h!!, 0.002f)
    }

    @Test
    fun depthAndYAreInverse() {
        for (y in listOf(0.32f, 0.5f, 0.74f, 0.9f)) {
            assertEquals(y, geo.yFromDepth(geo.depthProxy(y)), 1e-4f)
        }
    }

    @Test
    fun lanesAreAssignedFromPosition() {
        val y = 0.7f
        assertEquals(Lane.LEFT, geo.laneAt(geo.laneCenterX(Lane.LEFT, y), y))
        assertEquals(Lane.CENTER, geo.laneAt(geo.laneCenterX(Lane.CENTER, y), y))
        assertEquals(Lane.RIGHT, geo.laneAt(geo.laneCenterX(Lane.RIGHT, y), y))
        assertNull(geo.laneAt(0.01f, y))
    }

    @Test
    fun wideVehicleBlocksTwoLanes() {
        val y = 0.6f
        val box = RectN(geo.laneLeftX(Lane.CENTER, y) + 0.01f, y - 0.2f, geo.laneRightX(Lane.RIGHT, y) - 0.01f, y)
        val lanes = geo.lanesCovered(box)
        assertTrue(Lane.CENTER in lanes && Lane.RIGHT in lanes && Lane.LEFT !in lanes)
    }

    @Test
    fun playerCarIsNotAnObstacle() {
        val dets = listOf(Detection(TestGeo.profile.playerBoxAt(Lane.CENTER), 0.9f))
        assertTrue(LaneAssigner.assign(TestGeo.profile, dets, Lane.CENTER, 0.3f).isEmpty())
    }

    @Test
    fun lowConfidenceDetectionsAreDiscarded() {
        val box = World(TestGeo.profile, emptyList(), Lane.CENTER).boxOf(SimObstacle(Lane.LEFT, 0f), 0.3f)!!
        assertTrue(LaneAssigner.assign(TestGeo.profile, listOf(Detection(box, 0.1f)), Lane.CENTER, 0.35f).isEmpty())
        assertEquals(1, LaneAssigner.assign(TestGeo.profile, listOf(Detection(box, 0.9f)), Lane.CENTER, 0.35f).size)
    }
}

class TrackerTest {
    @Test
    fun speedAndTtcMatchTheTruthUnderPerspective() {
        val cfg = EngineConfig()
        val tracker = ObstacleTracker(TestGeo.profile, cfg)
        val obs = SimObstacle(Lane.LEFT, 0f, speed = 2.0f)
        val w = World(TestGeo.profile, listOf(obs), Lane.CENTER)
        var lastNs = 0L
        repeat(8) {
            val f = w.step()
            lastNs = f.timestampNs
            val dets = LaneAssigner.assign(TestGeo.profile, f.detections, Lane.CENTER, 0.3f)
            tracker.update(f.timestampNs, dets)
        }
        val t = tracker.tracks.single()
        assertEquals(2.0f, t.speed!!, 0.15f)
        // TTC real: profundidad actual vs. profundidad en la línea de colisión.
        val geo = TestGeo.road
        val box = w.boxOf(obs, w.timeSec)!!
        val trueTtc = (geo.depthProxy(box.bottom) - geo.depthProxy(TestGeo.profile.collisionY)) / 2.0f
        assertEquals(trueTtc, tracker.ttcSec(t, lastNs), 0.12f)
    }

    @Test
    fun trackIdPersistsAcrossFramesAndGaps() {
        val cfg = EngineConfig()
        val tracker = ObstacleTracker(TestGeo.profile, cfg)
        val obs = SimObstacle(Lane.RIGHT, 0f, hidden = 0.25f..0.4f)
        val w = World(TestGeo.profile, listOf(obs), Lane.CENTER)
        val ids = HashSet<Int>()
        repeat(14) {
            val f = w.step()
            tracker.update(f.timestampNs, LaneAssigner.assign(TestGeo.profile, f.detections, Lane.CENTER, 0.3f))
            tracker.tracks.forEach { ids += it.id }
        }
        assertEquals(1, ids.size)
    }

    @Test
    fun lostTrackIsDroppedAfterCoastTime() {
        val cfg = EngineConfig(maxCoastMs = 200)
        val tracker = ObstacleTracker(TestGeo.profile, cfg)
        val w = World(TestGeo.profile, listOf(SimObstacle(Lane.LEFT, 0f, hidden = 0.2f..9f)), Lane.CENTER)
        repeat(20) {
            val f = w.step()
            tracker.update(f.timestampNs, LaneAssigner.assign(TestGeo.profile, f.detections, Lane.CENTER, 0.3f))
        }
        assertTrue(tracker.tracks.isEmpty())
    }
}

class PlannerTest {
    private val cfg = EngineConfig()
    private val planner = PathPlanner(cfg)

    private fun win(arrive: Float) = listOf(ObstacleWindow(1, arrive, arrive + cfg.dwellSec, 1f))

    @Test
    fun staysWhenCurrentLaneIsSafe() {
        val r = planner.plan(Lane.CENTER, mapOf(Lane.LEFT to win(0.6f)), 10f, 0, 0f)
        assertNull(r.moveNow)
        assertEquals(false, r.deadEnd)
    }

    @Test
    fun movesToTheOnlyFreeLane() {
        val w = mapOf(Lane.LEFT to win(0.6f), Lane.CENTER to win(0.6f))
        val r = planner.plan(Lane.CENTER, w, 10f, 0, 0f)
        assertEquals(MoveDirection.RIGHT, r.moveNow)
        assertEquals(Lane.RIGHT, r.finalLane)
    }

    @Test
    fun picksTheLaneWithMoreMarginWhenTwoAreSafe() {
        // Jugador a la izquierda bloqueado; el centro libre pero con obstáculo muy próximo después,
        // la derecha totalmente libre: el plan debe terminar en la derecha.
        val w = mapOf(Lane.LEFT to win(0.5f), Lane.CENTER to win(1.0f))
        val r = planner.plan(Lane.LEFT, w, 10f, 0, 0f)
        assertEquals(Lane.RIGHT, r.finalLane)
    }

    @Test
    fun consecutiveObstaclesNeedSeveralMoves() {
        // Centro bloqueado ya; luego izquierda y centro bloqueados más tarde: hay que acabar a la derecha.
        val w = mapOf(
            Lane.CENTER to win(0.5f) + win(1.1f),
            Lane.LEFT to win(1.1f),
        )
        val r = planner.plan(Lane.CENTER, w, 10f, 0, 0f)
        assertEquals(Lane.RIGHT, r.finalLane)
        assertEquals(MoveDirection.RIGHT, r.moveNow)
    }

    @Test
    fun allBlockedIsFlaggedAsDeadEndAndDoesNotMoveForNothing() {
        val w = Lane.entries.associateWith { win(0.5f) }
        val r = planner.plan(Lane.CENTER, w, 10f, 0, 0f)
        assertTrue(r.deadEnd)
        assertNull(r.moveNow)
    }

    @Test
    fun respectsBlockedWindowAfterAMove() {
        val w = mapOf(Lane.CENTER to win(0.4f))
        val r = planner.plan(Lane.CENTER, w, 0f, 1, 0f)
        // Acaba de moverse a la derecha: no puede volver a la izquierda tan pronto.
        assertTrue(r.moveNow != MoveDirection.LEFT)
    }
}

class PlayerLaneTrackerTest {
    private val cfg = EngineConfig()
    private fun cmd(from: Lane, to: Lane) = MoveCommand(1, if (to.index > from.index) MoveDirection.RIGHT else MoveDirection.LEFT, from, to)
    private fun obs(l: Lane) = PlayerObservation(l, 0.9f)

    @Test
    fun confirmsMoveAfterConsecutiveObservations() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg.copy(confirmExpectedInOneFrame = false))
        t.onMoveIssued(0, cmd(Lane.CENTER, Lane.RIGHT))
        assertNull(t.onObservation(obs(Lane.RIGHT)))
        val e = t.onObservation(obs(Lane.RIGHT))
        assertTrue(e is PlayerEvent.MoveConfirmed)
        assertEquals(Lane.RIGHT, t.lane)
    }

    @Test
    fun orderedLaneIsConfirmedWithOneReliableObservation() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        t.onMoveIssued(0, cmd(Lane.CENTER, Lane.RIGHT))
        assertTrue(t.onObservation(obs(Lane.RIGHT)) is PlayerEvent.MoveConfirmed)
    }

    @Test
    fun unorderedLaneNeedsSeveralFramesAndPersistence() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        val ms = 1_000_000L
        // un coche que pasa junto al jugador: 200 ms en otro carril, sin orden previa
        for (i in 0..6) assertNull(t.onObservation(obs(Lane.LEFT), i * 33 * ms))
        assertEquals(Lane.CENTER, t.lane)
        t.onObservation(obs(Lane.CENTER), 7 * 33 * ms) // vuelve
        // un cambio real persiste más de 350 ms
        var ev: PlayerEvent? = null
        for (i in 8..22) ev = t.onObservation(obs(Lane.LEFT), i * 33 * ms) ?: ev
        assertTrue(ev is PlayerEvent.ExternalChange)
        assertEquals(Lane.LEFT, t.lane)
    }

    @Test
    fun singleSpuriousObservationDoesNotChangeLane() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        t.onObservation(obs(Lane.LEFT))
        t.onObservation(obs(Lane.CENTER))
        assertEquals(Lane.CENTER, t.lane)
    }

    @Test
    fun detectsIneffectiveSwipe() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        t.onMoveIssued(0, cmd(Lane.CENTER, Lane.LEFT))
        t.onObservation(obs(Lane.CENTER))
        val e = t.poll(cfg.moveConfirmTimeoutMs * 1_000_000L + 1)
        assertTrue(e is PlayerEvent.MoveFailed)
        assertEquals(Lane.CENTER, t.lane)
    }

    @Test
    fun assumesMoveWhenVisionCannotSeeThePlayer() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        t.onMoveIssued(0, cmd(Lane.CENTER, Lane.LEFT))
        val e = t.poll(cfg.moveConfirmTimeoutMs * 1_000_000L + 1)
        assertTrue(e is PlayerEvent.MoveAssumed)
        assertEquals(Lane.LEFT, t.lane)
        assertTrue(t.assumed)
    }

    @Test
    fun externalChangeIsAdopted() {
        val t = PlayerLaneTracker(Lane.CENTER, cfg)
        t.onObservation(obs(Lane.RIGHT))
        val e = t.onObservation(obs(Lane.RIGHT))
        assertTrue(e is PlayerEvent.ExternalChange)
        assertEquals(Lane.RIGHT, t.lane)
    }
}

class EngineGuardTest {
    private fun frames(world: World, engine: com.farks.autopilot.engine.AutopilotEngine, n: Int) =
        (0 until n).map { engine.onFrame(world.step()) }

    @Test
    fun neverCommandsWhenDisarmed() {
        val r = runClosedLoop(newWorld(listOf(SimObstacle(Lane.CENTER, 0f))), newEngine(), 3f, armed = false)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun emergencyStopBlocksAllCommands() {
        val engine = newEngine()
        engine.armed = true
        engine.emergencyStop()
        val world = newWorld(listOf(SimObstacle(Lane.CENTER, 0f)))
        val outs = frames(world, engine, 40)
        assertTrue(outs.none { it.command != null })
        assertEquals(com.farks.autopilot.models.EngineStatus.EMERGENCY_STOP, outs.last().status)
    }

    @Test
    fun respectsMinimumIntervalAndNeverDuplicatesPendingMoves() {
        val obstacles = listOf(
            SimObstacle(Lane.CENTER, 0f), SimObstacle(Lane.RIGHT, 0.6f), SimObstacle(Lane.LEFT, 1.0f),
        )
        val r = runClosedLoop(newWorld(obstacles), newEngine(), 4f)
        val cfg = EngineConfig()
        val times = r.outputs.filter { it.command != null }.map { it.timestampNs }
        for (i in 1 until times.size) {
            assertTrue("movimientos demasiado seguidos", times[i] - times[i - 1] >= cfg.minMoveIntervalMs * 1_000_000L)
        }
    }

    @Test
    fun framesAreTrackedButNothingIsDecidedDuringWarmup() {
        val engine = newEngine()
        engine.armed = true
        val world = newWorld(listOf(SimObstacle(Lane.CENTER, 0f)))
        val first = engine.onFrame(world.step())
        assertEquals(com.farks.autopilot.models.EngineStatus.WARMING_UP, first.status)
        assertTrue(first.lanes.values.all { it.state == com.farks.autopilot.models.LaneState.UNKNOWN })
        assertNull(first.command)
    }
}

class ProfileCodecTest {
    private fun sample(name: String = "Juego ñandú 🚗") = StoredProfile(
        com.farks.autopilot.calibration.GameProfile(
            "com.ejemplo.juego", name,
            com.farks.autopilot.calibration.RoadGeometry(
                com.farks.autopilot.models.PointN(0.05f, 0.95f), com.farks.autopilot.models.PointN(0.95f, 0.95f),
                com.farks.autopilot.models.PointN(0.38f, 0.30f), com.farks.autopilot.models.PointN(0.62f, 0.30f),
            ),
            com.farks.autopilot.models.RectN(0.40f, 0.74f, 0.60f, 0.92f),
            com.farks.autopilot.models.Lane.CENTER,
        ),
        FloatArray(ProfileCodec.MODEL_SIZE) { it * 1.5f + 0.25f },
    )

    @Test fun roundTripKeepsEverything() {
        val s = sample()
        val back = ProfileCodec.decode(ProfileCodec.encode(s))
        org.junit.Assert.assertEquals(s, back)
    }

    @Test fun garbageIsRejectedWithoutThrowing() {
        org.junit.Assert.assertNull(ProfileCodec.decode(""))
        org.junit.Assert.assertNull(ProfileCodec.decode("hola"))
        org.junit.Assert.assertNull(ProfileCodec.decode("farks-profile-v1\nroad=1,2"))
        val truncated = ProfileCodec.encode(sample()).lines().filterNot { it.startsWith("model=") }.joinToString("\n")
        org.junit.Assert.assertNull(ProfileCodec.decode(truncated))
    }

    @Test fun invalidGeometryIsRejected() {
        val bad = ProfileCodec.encode(sample()).replace(Regex("road=.*"), "road=0.9,0.9,0.1,0.9,0.4,0.3,0.6,0.3")
        org.junit.Assert.assertNull(ProfileCodec.decode(bad))
    }
}
