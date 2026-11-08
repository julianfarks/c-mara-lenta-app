package com.farks.autopilot.decision

import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.MoveDirection
import com.farks.autopilot.prediction.ObstacleWindow
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PlannedMove(val atSec: Float, val direction: MoveDirection)

data class PlanResult(
    /** Primer movimiento si debe ejecutarse YA; null si lo mejor es quedarse. */
    val moveNow: MoveDirection?,
    val finalLane: Lane,
    /** Riesgo del plan elegido (segundos ponderados dentro de ventanas de obstáculos). */
    val penalty: Float,
    /** Riesgo si el jugador se queda quieto. */
    val baselinePenalty: Float,
    val minClearanceSec: Float,
    val moves: List<PlannedMove>,
    val deadEnd: Boolean,
    val note: String,
)

/**
 * Planificador por programación dinámica sobre (carril, tiempo).
 * Encuentra la secuencia de cambios de carril que evita las ventanas de obstáculos,
 * con pocos movimientos y el mayor margen posible. Soporta obstáculos consecutivos
 * (varios cambios de carril) y calcula la alternativa de menor riesgo si no hay salida.
 */
class PathPlanner(private val config: EngineConfig) {

    private class Step(val step: Int, val dir: Int, val prev: Step?)

    private class Node(
        val pen: Float,
        val moves: Int,
        val minClear: Float,
        val startMove: Int,
        val last: Step?,
        val lane: Int,
        /** Suma de los pasos en que se inician los movimientos (a igualdad, se prefiere actuar antes). */
        val delaySum: Int = 0,
    ) {
        val cost: Float get() = 100f * pen + 0.25f * moves - min(minClear, 1f) + 0.002f * delaySum
    }

    fun plan(
        start: Lane,
        windows: Map<Lane, List<ObstacleWindow>>,
        sinceLastMoveSec: Float,
        lastDir: Int,
        /** No se permite iniciar movimientos antes de este instante (p. ej. hay uno pendiente). */
        blockedForSec: Float,
        /**
         * Instante más temprano en que un gesto ordenado AHORA hace que el vehículo empiece a moverse
         * (edad de la imagen + retardo del gesto). "Mover ya" significa mover en ese instante.
         */
        earliestSec: Float = 0f,
    ): PlanResult {
        val dt = config.planStepSec
        val n = ceil(config.horizonSec / dt).toInt()
        val t = max(1, ceil(config.laneChangeSec / dt).toInt())
        val minIntervalSteps = ceil(config.minMoveIntervalMs / 1000f / dt).toInt()
        val reverseSteps = ceil(config.reverseGuardMs / 1000f / dt).toInt()
        val cap = max(minIntervalSteps, reverseSteps) + 1
        val nowStep = ceil(earliestSec / dt).toInt().coerceAtLeast(0)
        val blockSteps = max(nowStep, ceil(blockedForSec / dt).toInt().coerceAtLeast(0))

        // Tablas de penalización y holgura por carril y paso.
        val pen = Array(3) { FloatArray(n + t + 1) }
        val clr = Array(3) { FloatArray(n + t + 1) { CLEAR_CAP } }
        val margin = config.safetyMarginSec
        for (lane in Lane.entries) {
            val ws = windows[lane].orEmpty()
            for (s in 0..n + t) {
                val time = (s + 0.5f) * dt
                var p = 0f
                var c = CLEAR_CAP
                for (w in ws) {
                    if (time >= w.arrive && time <= w.clear) {
                        p += 10f * w.weight * dt
                    } else if ((time >= w.arrive - margin && time < w.arrive) ||
                        (time > w.clear && time <= w.clear + margin)
                    ) {
                        p += 1f * w.weight * dt
                    }
                    if (w.weight >= 0.5f) {
                        val d = when {
                            time < w.arrive -> w.arrive - time
                            time > w.clear -> time - w.clear
                            else -> 0f
                        }
                        c = min(c, d)
                    }
                }
                pen[lane.index][s] = p
                clr[lane.index][s] = c
            }
        }

        var baseline = 0f
        for (s in 0 until n) baseline += pen[start.index][s]

        fun key(lane: Int, since: Int, dir: Int) = (lane * (cap + 1) + since) * 3 + (dir + 1)

        val layers = Array(n + 1) { HashMap<Int, Node>() }
        val initSince = min(cap, (sinceLastMoveSec / dt).toInt().coerceAtLeast(0))
        layers[0][key(start.index, initSince, lastDir)] = Node(0f, 0, CLEAR_CAP, 0, null, start.index)

        fun offer(layer: Int, k: Int, node: Node) {
            val cur = layers[layer][k]
            if (cur == null || node.cost < cur.cost) layers[layer][k] = node
        }

        fun sinceOf(k: Int): Int = (k / 3) % (cap + 1)
        fun dirOf(k: Int): Int = k % 3 - 1

        for (s in 0 until n) {
            for ((k, node) in layers[s].entries.toList()) {
                val lane = node.lane
                val since = sinceOf(k)
                val dir = dirOf(k)
                // Quedarse.
                offer(
                    s + 1,
                    key(lane, min(cap, since + 1), dir),
                    Node(
                        node.pen + pen[lane][s],
                        node.moves,
                        min(node.minClear, clr[lane][s]),
                        node.startMove,
                        node.last,
                        lane,
                        node.delaySum,
                    ),
                )
                // Cambiar de carril.
                if (s < blockSteps) continue
                for (d in intArrayOf(-1, 1)) {
                    val to = lane + d
                    if (to !in 0..2) continue
                    val required = if (dir != 0 && d == -dir) reverseSteps else minIntervalSteps
                    if (since < required) continue
                    val e = min(n, s + t)
                    var p = node.pen
                    var c = node.minClear
                    for (x in s until e) {
                        p += max(pen[lane][x], pen[to][x])
                        c = min(c, min(clr[lane][x], clr[to][x]))
                    }
                    val startMove = if (s == nowStep) d else node.startMove
                    offer(
                        e,
                        key(to, min(cap, e - s), d),
                        Node(p, node.moves + 1, c, startMove, Step(s, d, node.last), to, node.delaySum + s),
                    )
                }
            }
        }

        val best = layers[n].values.minByOrNull { it.cost }
            ?: return PlanResult(null, start, baseline, baseline, CLEAR_CAP, emptyList(), baseline >= config.minGain, "sin plan")

        val moves = ArrayList<PlannedMove>()
        var st = best.last
        while (st != null) {
            moves += PlannedMove(st.step * dt, if (st.dir < 0) MoveDirection.LEFT else MoveDirection.RIGHT)
            st = st.prev
        }
        moves.reverse()

        val deadEnd = best.pen >= config.minGain
        val finalLane = Lane.fromIndex(best.lane) ?: start

        // Decisión sobre el movimiento inmediato.
        var moveNow: MoveDirection? = null
        var note: String
        when {
            baseline < config.minGain -> note = "el carril actual es seguro"
            best.startMove == 0 -> note = if (deadEnd) "sin salida: mantener posición" else "esperar el momento adecuado"
            baseline - best.pen < config.minGain -> note = "mover no mejora el riesgo"
            else -> {
                moveNow = if (best.startMove < 0) MoveDirection.LEFT else MoveDirection.RIGHT
                note = if (deadEnd) "sin salida segura: elegir el menor riesgo" else "cambio necesario para evitar obstáculo"
            }
        }
        return PlanResult(moveNow, finalLane, best.pen, baseline, best.minClear, moves, deadEnd, note)
    }

    companion object {
        const val CLEAR_CAP = 1.5f
    }
}
