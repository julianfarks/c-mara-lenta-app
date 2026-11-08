package com.farks.autopilot

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.engine.AutopilotEngine
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.EngineOutput
import com.farks.autopilot.models.FrameObservation
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.models.PlayerObservation
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import java.util.Random

/** Geometría de prueba: carretera en perspectiva, punto de fuga en y≈0.064. */
object TestGeo {
    val road = RoadGeometry(PointN(0.05f, 0.95f), PointN(0.95f, 0.95f), PointN(0.38f, 0.30f), PointN(0.62f, 0.30f))
    val player = RectN(0.40f, 0.74f, 0.60f, 0.92f)
    val profile = GameProfile("test.game", "Juego de prueba", road, player, Lane.CENTER)
}

/**
 * Un obstáculo sintético. Se acerca con velocidad constante en profundidad (perspectiva real),
 * por lo que en pantalla acelera igual que en un juego 3D.
 */
class SimObstacle(
    val lane: Lane,
    val spawnSec: Float,
    /** Unidades de profundidad por segundo (≈2.0 => cruza la carretera en ~1.4 s). */
    val speed: Float = 2.0f,
    val driftToLane: Lane? = null,
    val driftStartSec: Float = 0f,
    val driftDurSec: Float = 0.4f,
    /** Intervalo (segundos de simulación) en que la visión NO lo detecta aunque exista. */
    val hidden: ClosedFloatingPointRange<Float>? = null,
) {
    var collided = false
}

class World(
    val profile: GameProfile,
    val obstacles: List<SimObstacle>,
    startLane: Lane,
    val dtMs: Long = 50,
    /** Tiempo que dura físicamente un cambio de carril. */
    val laneChangeMs: Long = 200,
    val swipeWorks: Boolean = true,
    val noise: Float = 0f,
    val playerVisible: Boolean = true,
    seed: Long = 1234,
) {
    val geo = profile.road
    private val rnd = Random(seed)
    var timeNs = 0L
    var playerLane = startLane
    var collisions = 0
    private var moveEndNs: Long? = null
    private var moveFrom: Lane? = null
    private var moveTo: Lane? = null

    private val startDepth = geo.depthProxy(geo.farY + 0.01f)
    val timeSec: Float get() = timeNs / 1e9f
    val transitioning: Boolean get() = moveEndNs != null

    fun queueMove(target: Lane) {
        if (!swipeWorks || moveEndNs != null) return
        moveFrom = playerLane
        moveTo = target
        moveEndNs = timeNs + laneChangeMs * 1_000_000L
    }

    fun forceLane(l: Lane) {
        playerLane = l
    }

    fun boxOf(o: SimObstacle, tSec: Float): RectN? {
        if (tSec < o.spawnSec) return null
        val depth = startDepth - o.speed * (tSec - o.spawnSec)
        val y = geo.yFromDepth(depth)
        if (y < geo.farY + 0.005f || y > 1.0f) return null
        val w = 0.7f * geo.laneWidth(y)
        val h = w * 1.1f
        var cx = geo.laneCenterX(o.lane, y)
        val target = o.driftToLane
        if (target != null) {
            val f = ((tSec - o.driftStartSec) / o.driftDurSec).coerceIn(0f, 1f)
            cx += (geo.laneCenterX(target, y) - cx) * f
        }
        return RectN(cx - w / 2, y - h, cx + w / 2, y)
    }

    /** Avanza un paso y devuelve lo que vería la visión. */
    fun step(): FrameObservation {
        timeNs += dtMs * 1_000_000L
        val end = moveEndNs
        if (end != null && timeNs >= end) {
            playerLane = moveTo!!
            moveEndNs = null
        }
        val dets = ArrayList<Detection>()
        for (o in obstacles) {
            val box = boxOf(o, timeSec) ?: continue
            // colisión física
            if (!o.collided && box.bottom >= profile.collisionY) {
                val occupied = mutableSetOf(playerLane)
                if (moveEndNs != null) {
                    occupied += moveFrom!!
                    occupied += moveTo!!
                }
                if (geo.lanesCovered(box).any { it in occupied }) {
                    o.collided = true
                    collisions++
                }
            }
            if (o.hidden != null && timeSec in o.hidden) continue
            val n = { (rnd.nextFloat() - 0.5f) * 2f * noise }
            dets += Detection(
                RectN(box.left + n(), box.top + n(), box.right + n(), (box.bottom + n())),
                0.9f,
            )
        }
        val obs = if (playerVisible && moveEndNs == null) PlayerObservation(playerLane, 0.95f) else null
        return FrameObservation(timeNs, dets, obs)
    }
}

class RunResult(
    val outputs: List<EngineOutput>,
    val commands: List<MoveCommand>,
    val world: World,
    val engine: AutopilotEngine,
)

fun runClosedLoop(
    world: World,
    engine: AutopilotEngine,
    seconds: Float,
    armed: Boolean = true,
    gestureOk: Boolean = true,
    hook: ((Int, EngineOutput) -> Unit)? = null,
): RunResult {
    engine.armed = armed
    val outs = ArrayList<EngineOutput>()
    val cmds = ArrayList<MoveCommand>()
    val steps = (seconds * 1000f / world.dtMs).toInt()
    for (i in 0 until steps) {
        val frame = world.step()
        val out = engine.onFrame(frame)
        outs += out
        val c = out.command
        if (c != null) {
            cmds += c
            if (gestureOk) world.queueMove(c.target)
            engine.onGestureResult(c.id, gestureOk)
        }
        hook?.invoke(i, out)
    }
    return RunResult(outs, cmds, world, engine)
}

fun newEngine(config: EngineConfig = EngineConfig()) = AutopilotEngine(TestGeo.profile, config)

fun newWorld(obstacles: List<SimObstacle>, startLane: Lane = Lane.CENTER, noise: Float = 0f) =
    World(TestGeo.profile, obstacles, startLane, noise = noise)
