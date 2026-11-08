package com.farks.autopilot

import com.farks.autopilot.models.EngineConfig
import org.junit.Test

/** Imprime la comparación de configuraciones bajo latencias realistas (solo informativo). */
class LatencyStudyRunTest {
    @Test
    fun printComparison() {
        val base = EngineConfig(laneChangeSec = 0.25f)
        val oldRange = LatencyStudy.Conditions(visibleFromY = 0.38f)
        val newRange = LatencyStudy.Conditions(visibleFromY = 0.315f)
        val ideal = LatencyStudy.Conditions(frameLatencyMs = 0, actuationDelayMs = 0, visibleFromY = 0f)
        fun row(n: String, c: EngineConfig, k: LatencyStudy.Conditions) = println(LatencyStudy.run(n, c, k).summary(n))
        row("legacy  | alcance viejo", EngineConfig.legacy(base), oldRange)
        row("nuevo   | alcance viejo", base, oldRange)
        row("legacy  | alcance nuevo", EngineConfig.legacy(base), newRange)
        row("nuevo   | alcance nuevo", base, newRange)
        val oracle = LatencyStudy.Conditions(frameLatencyMs = 0, actuationDelayMs = 100, visibleFromY = 0f)
        row("techo: visión perfecta, gesto 100ms", base, oracle)
        println("--- descomposición de la brecha (motor nuevo) ---")
        row("gesto100 + imagen70 + alcance perfecto", base, LatencyStudy.Conditions(visibleFromY = 0f))
        row("gesto100 + imagen0  + alcance 0.315", base, LatencyStudy.Conditions(frameLatencyMs = 0, visibleFromY = 0.315f))
        row("gesto100 + imagen35 + alcance 0.315", base, LatencyStudy.Conditions(frameLatencyMs = 35, visibleFromY = 0.315f))
        row("gesto100 + imagen70 + alcance 0.34", base, LatencyStudy.Conditions(visibleFromY = 0.34f))
        println("--- confirmación rápida (2 fotogramas coherentes) ---")
        row("3 fotogramas (actual)", base, newRange)
        row("2 coherentes", base.copy(fastConfirm = true), newRange)
        fun rowP(n: String, c: EngineConfig) = println(LatencyStudy.run(n, c, newRange, phantomsPerRun = 8).summary(n + " +8 parpadeos"))
        rowP("3 fotogramas (actual)", base)
        rowP("2 coherentes", base.copy(fastConfirm = true))
        println("--- barrido margen de seguridad (incertidumbre=1) ---")
        for (m in listOf(0.05f, 0.10f, 0.18f, 0.28f)) row("margen=$m", base.copy(safetyMarginSec = m), newRange)
        println("--- barrido incertidumbre K (margen=0.18) ---")
        for (k in listOf(0f, 0.5f, 1f, 2f)) row("K=$k", base.copy(uncertaintyK = k), newRange)
        println("--- barrido min intervalo ---")
        for (ms in listOf(100L, 200L, 350L)) row("intervalo=$ms", base.copy(minMoveIntervalMs = ms), newRange)
        println("--- ablaciones (alcance nuevo) ---")
        row("nuevo sin compensar latencia", base.copy(compensateLatency = false), newRange)
        row("nuevo sin incertidumbre", base.copy(uncertaintyK = 0f), newRange)
        row("nuevo sin dwell adaptativo", base.copy(adaptiveDwell = false), newRange)
        row("nuevo sin prior de velocidad", base.copy(speedPrior = false), newRange)
        row("nuevo sin veto", base.copy(transitVeto = false), newRange)
        row("nuevo sin aprender latencia", base.copy(learnLatency = false), newRange)
    }

    @Test
    fun debugGapSeeds() {
        val base = EngineConfig(laneChangeSec = 0.25f)
        val real = LatencyStudy.Conditions(visibleFromY = 0.315f)
        val ceil = LatencyStudy.Conditions(frameLatencyMs = 0, actuationDelayMs = 100, visibleFromY = 0f)
        fun bad(c: LatencyStudy.Conditions) = (0 until 150).filter { LatencyStudy.run("x", base, c, it..it).collisions > 0 }.toSet()
        val a = bad(real); val b = bad(ceil)
        println("choca real=${a.size} techo=${b.size} solo_real=${(a - b).sorted()}")
        for (seed in (a - b).sorted().take(2)) {
            val obs = LatencyStudy.scenario(seed.toLong())
            val k = real
            val w = World(TestGeo.profile, obs, com.farks.autopilot.models.Lane.CENTER, dtMs = k.dtMs, laneChangeMs = k.laneChangeMs,
                noise = k.noise, seed = seed.toLong(), frameLatencyMs = k.frameLatencyMs, actuationDelayMs = k.actuationDelayMs,
                visibleFromY = k.visibleFromY, midpointObservation = true)
            val g = TestGeo.profile
            val sd = g.road.depthProxy(g.road.farY + 0.01f); val cd = g.road.depthProxy(g.collisionY)
            println("seed $seed")
            obs.sortedBy { it.spawnSec }.forEach { println("  obst ${it.lane} spawn=%.2f v=%.1f llega=%.2f".format(it.spawnSec, it.speed, it.spawnSec + (sd - cd) / it.speed)) }
            val e = com.farks.autopilot.engine.AutopilotEngine(g, base)
            var colAt = -1f
            val r = runClosedLoop(w, e, 15f, hook = { i, _ -> if (colAt < 0 && w.collisions > 0) colAt = i * k.dtMs / 1000f })
            r.outputs.forEachIndexed { i, o -> if (o.command != null) println("  t=%.2f cmd %s->%s ttc=%s %s".format(i * k.dtMs / 1000f, o.command!!.from, o.command!!.target, o.lanes.values.map { it.nearestArrivalSec?.let { v -> "%.2f".format(v) } ?: "-" }, o.decision.reason.take(90))) }
            println("  primer choque en t=%.2f".format(colAt))
        }
    }
}
