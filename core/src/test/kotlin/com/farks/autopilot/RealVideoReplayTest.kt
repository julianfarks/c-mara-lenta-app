package com.farks.autopilot

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.engine.AutopilotEngine
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.FrameObservation
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PlayerObservation
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduce contra el motor Kotlin las detecciones obtenidas sobre una GRABACIÓN REAL de una partida de Lane Rush
 * (tools/replay_video.py; el video lo aportó el usuario). En ese video un coche naranja aparece en el horizonte
 * hacia t=4.4 s en el carril central, donde está el jugador, y lo golpea a t≈5.37 s (medido a mano sobre los fotogramas).
 * Las detecciones las produce el espejo en Python del detector (no el detector Kotlin): esto comprueba el motor sobre
 * datos reales de tiempo y geometría, no la visión en Android.
 */
class RealVideoReplayTest {
    private val road = RoadGeometry(
        nearLeft = PointN(0.010f, 0.931f), nearRight = PointN(1.0f, 0.931f),
        farLeft = PointN(0.262f, 0.629f), farRight = PointN(0.714f, 0.632f),
    )
    private val profile = GameProfile("lane.rush", "Lane Rush", road, RectN(0.351f, 0.743f, 0.654f, 0.843f), Lane.CENTER)
    private val impactSec = 5.37
    private val fps = 60.331

    private class Cmd(val t: Double, val from: Lane, val target: Lane, val reason: String)

    private fun load(name: String): Map<Int, List<Detection>> {
        val lines = javaClass.getResourceAsStream("/$name")!!.bufferedReader().readLines().drop(1)
        val m = HashMap<Int, MutableList<Detection>>()
        for (l in lines) {
            val c = l.split(',')
            val frame = Math.round(c[0].toDouble() * fps).toInt()
            m.getOrPut(frame) { ArrayList() } += Detection(RectN(c[2].toFloat(), c[3].toFloat(), c[4].toFloat(), c[5].toFloat()), c[6].toFloat())
        }
        return m
    }

    private fun replay(csv: String, config: EngineConfig, processingMs: Long = 25): List<Cmd> {
        val dets = load(csv)
        val engine = AutopilotEngine(profile, config)
        engine.armed = true
        val out = ArrayList<Cmd>()
        var i = (2.4 * fps).toInt()
        i += i % 2
        while (i / fps < 5.5) {
            val t = i / fps
            val tNs = (t * 1e9).toLong()
            val d = dets[i].orEmpty()
            val o = engine.onFrame(FrameObservation(tNs, d, PlayerObservation(Lane.CENTER, 0.95f), tNs + processingMs * 1_000_000L))
            o.command?.let { c ->
                out += Cmd(t, c.from, c.target, o.decision.reason)
                engine.onGestureDispatched(c.id, tNs + 5_000_000L)
                engine.onGestureResult(c.id, true, tNs + 90_000_000L)
            }
            i += 2
        }
        return out
    }

    private val scale = EngineConfig(scaleDepth = true, laneChangeSec = 0.25f)

    private fun show(name: String, cmds: List<Cmd>) =
        println("%-46s -> %s".format(name, if (cmds.isEmpty()) "SIN ORDEN (choque)" else cmds.joinToString { "t=%.2f %s→%s (%.2fs antes del choque)".format(it.t, it.from, it.target, impactSec - it.t) }))

    @Test
    fun oldPipelineReactsTooLate_reproducesTheUsersReport() {
        val old = replay("real_video_detections_noband.csv", EngineConfig.legacy(EngineConfig(laneChangeSec = 0.25f)))
        show("antes (sin franja, profundidad y)", old)
        // Gesto (~0.1 s) + cambio de carril (~0.25 s) necesitan >0.35 s: con menos, el coche llega antes de salir del carril.
        assertTrue("la orden antigua debería llegar con menos de 0.4 s de margen", old.isEmpty() || impactSec - old.first().t < 0.4)
    }

    @Test
    fun newPipelineOrdersTheChangeWithPlentyOfMargin_andNeverIntoDanger() {
        val cmds = replay("real_video_detections_horizon.csv", scale)
        show("nuevo (franja de horizonte + escala)", cmds)
        assertEquals("una sola orden, sin duplicados", 1, cmds.size)
        val c = cmds.single()
        assertEquals(Lane.CENTER, c.from)
        assertTrue("debe ir a un carril libre (no hay obstáculos en los laterales): ${c.target}", c.target != Lane.CENTER)
        assertTrue("no antes de que el coche exista (aparece en ~4.3 s): t=${c.t}", c.t >= 4.3)
        assertTrue("al menos 0.6 s de margen antes del choque: ${impactSec - c.t}", impactSec - c.t >= 0.6)
    }

    @Test
    fun noMovementWithoutObstacles_staticEdgeBlobIsIgnored() {
        // La detección estática del borde de la carretera (carril izquierdo) no debe provocar ningún movimiento antes de que aparezca el coche.
        val cmds = replay("real_video_detections_horizon.csv", scale)
        assertTrue(cmds.none { it.t < 4.3 })
    }

    @Test
    fun printComparison() {
        show("sin franja, profundidad y", replay("real_video_detections_noband.csv", EngineConfig(laneChangeSec = 0.25f)))
        show("sin franja, escala", replay("real_video_detections_noband.csv", scale))
        show("franja, profundidad y", replay("real_video_detections_horizon.csv", EngineConfig(laneChangeSec = 0.25f)))
        show("franja + escala (nuevo)", replay("real_video_detections_horizon.csv", scale))
    }
}
