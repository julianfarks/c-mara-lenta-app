package com.farks.autopilot

import com.farks.autopilot.engine.AutopilotEngine
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import java.util.Locale
import java.util.Random

/**
 * Banco de pruebas comparativo con latencias realistas. NO es un juego real: es una simulación
 * con perspectiva, retardo de imagen, retardo del gesto, rango de visión limitado y parpadeos
 * falsos, pensada para comparar configuraciones del motor sobre escenarios idénticos.
 */
object LatencyStudy {

    /** Condiciones de la simulación (valores típicos de un teléfono medio, no medidos en uno concreto). */
    data class Conditions(
        val dtMs: Long = 33,
        val frameLatencyMs: Long = 70,
        val actuationDelayMs: Long = 100,
        val laneChangeMs: Long = 250,
        val visibleFromY: Float = 0.38f,
        val noise: Float = 0.002f,
    )

    class Metrics {
        var runs = 0
        var collisions = 0
        var runsWithCollision = 0
        var moves = 0
        var needlessMoves = 0
        var dangerousMoves = 0
        var phantomMoves = 0
        val margins = ArrayList<Float>()
        fun summary(name: String): String = String.format(
            Locale.US,
            "%-34s runs=%d  choques=%d (en %d runs)  movimientos=%d  innecesarios=%d  hacia_carril_peligroso=%d  margen_medio=%.2fs",
            name, runs, collisions, runsWithCollision, moves, needlessMoves, dangerousMoves,
            if (margins.isEmpty()) Float.NaN else margins.average().toFloat(),
        )
    }

    /** Llegada (s) del obstáculo al plano de colisión, calculada con la verdad del simulador. */
    private fun arrival(w: World, o: SimObstacle): Float {
        val startDepth = w.geo.depthProxy(w.geo.farY + 0.01f)
        val collisionDepth = w.geo.depthProxy(w.profile.collisionY)
        return o.spawnSec + (startDepth - collisionDepth) / o.speed
    }

    fun scenario(seed: Long): List<SimObstacle> {
        val r = Random(seed)
        val speeds = floatArrayOf(1.8f, 2.2f, 2.6f, 3.0f)
        val out = ArrayList<SimObstacle>()
        var t = 0.4f
        repeat(7) {
            val sp = speeds[r.nextInt(speeds.size)]
            val lanes = Lane.entries.shuffled(r)
            val n = if (r.nextBoolean()) 1 else 2
            for (l in lanes.take(n)) out += SimObstacle(l, t + r.nextFloat() * 0.15f, sp)
            t += 1.5f + r.nextFloat() * 0.9f
        }
        return out
    }

    fun run(
        name: String,
        config: EngineConfig,
        cond: Conditions = Conditions(),
        seeds: IntRange = 0 until 150,
        phantomsPerRun: Int = 0,
    ): Metrics {
        val m = Metrics()
        for (seed in seeds) {
            val obs = scenario(seed.toLong())
            val r = Random(seed * 7919L)
            val phantoms = List(phantomsPerRun) {
                Triple(0.8f + r.nextFloat() * 9f, Lane.entries[r.nextInt(3)], 1 + r.nextInt(2))
            }
            val world = World(
                TestGeo.profile, obs, Lane.CENTER, dtMs = cond.dtMs,
                laneChangeMs = cond.laneChangeMs, noise = cond.noise, seed = seed.toLong(),
                frameLatencyMs = cond.frameLatencyMs, actuationDelayMs = cond.actuationDelayMs,
                visibleFromY = cond.visibleFromY, midpointObservation = true, phantoms = phantoms,
            )
            val engine = AutopilotEngine(TestGeo.profile, config)
            val laneAtCmd = HashMap<Long, Pair<Lane, Float>>()
            val res = runClosedLoop(world, engine, 15f, hook = { _, out ->
                out.command?.let { laneAtCmd[it.id] = it.from to world.timeSec }
            })
            res.outputs.last().reactions.forEach { r -> r.marginAtChangeSec?.let { m.margins += it } }
            m.runs++
            m.collisions += world.collisions
            if (world.collisions > 0) m.runsWithCollision++
            m.moves += res.commands.size
            val l = cond.actuationDelayMs / 1000f
            val tt = cond.laneChangeMs / 1000f
            for (c in res.commands) {
                val (from, tCmd) = laneAtCmd.getValue(c.id)
                val arrivals = obs.groupBy { it.lane }.mapValues { e -> e.value.map { arrival(world, it) } }
                val needed = arrivals[from].orEmpty().any { it in (tCmd - 0.3f)..(tCmd + 2.4f) }
                if (!needed) {
                    m.needlessMoves++
                    if (phantomsPerRun > 0) m.phantomMoves++
                }
                val danger = arrivals[c.target].orEmpty().any { it in (tCmd + l - 0.15f)..(tCmd + l + tt + 0.35f) }
                if (danger) m.dangerousMoves++
            }
        }
        return m
    }
}
