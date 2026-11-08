package com.farks.autopilot.engine

import com.farks.autopilot.automation.PlayerEvent
import com.farks.autopilot.automation.PlayerLaneTracker
import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.LaneAssigner
import com.farks.autopilot.decision.PathPlanner
import com.farks.autopilot.decision.PlanResult
import com.farks.autopilot.models.Action
import com.farks.autopilot.models.Decision
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.EngineEvent
import com.farks.autopilot.models.EngineOutput
import com.farks.autopilot.models.EngineStatus
import com.farks.autopilot.models.FrameObservation
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneAssessment
import com.farks.autopilot.models.LaneState
import com.farks.autopilot.models.LatencyStats
import com.farks.autopilot.models.LogLevel
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.models.MoveDirection
import com.farks.autopilot.models.ReactionRecord
import com.farks.autopilot.models.TrackView
import com.farks.autopilot.prediction.LanePredictor
import com.farks.autopilot.prediction.ObstacleWindow
import com.farks.autopilot.tracking.ObstacleTracker
import com.farks.autopilot.tracking.SelfVehicleFilter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Motor de piloto automático: puro (sin dependencias de Android) y determinista.
 * Recibe observaciones fotograma a fotograma y devuelve la decisión y, si procede,
 * una orden de movimiento. Un único hilo debe llamar a sus métodos.
 *
 * Reacción anticipada: el planificador no asume que el gesto actúa al instante. Cuenta con la edad
 * de la imagen, el retardo del gesto (aprendido midiendo cuándo se ve el cambio en pantalla) y la
 * duración del cambio de carril. Los obstáculos llevan una incertidumbre de llegada que se estrecha
 * con cada fotograma, de modo que se decide pronto sin esperar a la certeza total.
 */
class AutopilotEngine(
    val profile: GameProfile,
    val config: EngineConfig = EngineConfig(),
) {
    private val tracker = ObstacleTracker(profile, config)
    private val predictor = LanePredictor(config)
    private val planner = PathPlanner(config)
    private val player = PlayerLaneTracker(profile.playerLane, config)
    private val selfFilter = SelfVehicleFilter(profile, config)

    /** Si es false se analiza y se muestra el estado, pero nunca se emiten órdenes. */
    var armed: Boolean = false

    private var emergency = false
    private var captureLostReason: String? = null
    private var stale = false
    private var validFrames = 0
    private var lastFrameNs: Long? = null
    private var lastProcessedNs = 0L

    private var lastMoveNs: Long? = null
    private var lastDir = 0
    private var pendingCommand: MoveCommand? = null
    private var nextCommandId = 1L
    var gestureErrors = 0
        private set
    var movementErrors = 0
        private set

    // ---- Latencia aprendida ---------------------------------------------------------------------
    /** Retardo entre ordenar el gesto y que el vehículo empiece a moverse (s). */
    var actuationSec: Float = config.actuationDelaySec
        private set
    private var actuationSamples = 0
    /** Instante (nanoTime real) estimado en que termina físicamente el último cambio; hasta entonces el juego ignora gestos. */
    private var moveBusyUntilNs = 0L
    private var framePeriodSec = 0.033f
    private var frameAgeSec = 0f

    // ---- Línea de tiempo de reacciones ---------------------------------------------------------------
    private class Reaction(
        val cmd: MoveCommand,
        val triggerTrackId: Int?,
        val firstSeenNs: Long?,
        val confirmedNs: Long?,
        val frameAgeMs: Float,
        val decisionNs: Long,
        val ttcAtDecision: Float?,
        val arrivalAbsNs: Long?,
    ) {
        var sentNs: Long? = null
        var doneNs: Long? = null
        var changedNs: Long? = null
        var margin: Float? = null
        var outcome = "en curso"
        fun record() = ReactionRecord(
            cmd.id, cmd.direction, cmd.from, cmd.target, triggerTrackId, firstSeenNs, confirmedNs,
            frameAgeMs, decisionNs, sentNs, doneNs, changedNs, ttcAtDecision, margin, outcome,
        )
    }

    private val reactions = ArrayDeque<Reaction>()
    private fun reactionOf(id: Long) = reactions.lastOrNull { it.cmd.id == id }

    private val events = mutableListOf<EngineEvent>()
    private var last: EngineOutput? = null

    val lastOutput: EngineOutput? get() = last

    private fun log(level: LogLevel, msg: String) {
        events += EngineEvent(level, msg)
    }

    // ---- Ciclo de vida de la captura -------------------------------------------------
    fun onCaptureLost(tNs: Long, reason: String): EngineOutput {
        captureLostReason = reason
        tracker.reset()
        selfFilter.reset()
        validFrames = 0
        pendingCommand = null
        player.cancelPending()
        log(LogLevel.ERROR, "Captura interrumpida: $reason. Acciones suspendidas.")
        return buildOutput(tNs, noDecision("Captura interrumpida: $reason"), null, emptyMap(), valid = false)
    }

    fun onCaptureResumed() {
        if (captureLostReason != null) {
            captureLostReason = null
            validFrames = 0
            stale = false
        }
    }

    /** Vigilancia de fotogramas ausentes. Devuelve una salida solo cuando el estado cambia a STALE. */
    fun checkStale(nowNs: Long): EngineOutput? {
        val lf = lastFrameNs ?: return null
        if (captureLostReason != null || stale) return null
        if (nowNs - lf > config.staleMs * 1_000_000L) {
            stale = true
            validFrames = 0
            log(LogLevel.WARN, "Sin fotogramas durante más de ${config.staleMs} ms: carriles desconocidos.")
            if (nowNs - lf > config.staleMs * 3_000_000L) tracker.reset()
            return buildOutput(nowNs, noDecision("Sin imagen reciente"), null, emptyMap(), valid = false)
        }
        return null
    }

    fun emergencyStop(reason: String = "Parada de emergencia") {
        emergency = true
        armed = false
        pendingCommand = null
        player.cancelPending()
        reactions.lastOrNull { it.outcome == "en curso" }?.outcome = "parada de emergencia"
        events += EngineEvent(LogLevel.WARN, reason)
    }

    fun resetEmergency() {
        emergency = false
    }

    /** El servicio avisa cuando el sistema aceptó el gesto (instante del envío). */
    fun onGestureDispatched(commandId: Long, nowNs: Long) {
        reactionOf(commandId)?.sentNs = nowNs
    }

    fun onGestureResult(commandId: Long, success: Boolean, nowNs: Long = lastProcessedNs) {
        val r = reactionOf(commandId)
        if (r != null) {
            r.doneNs = nowNs
            if (r.sentNs == null) r.sentNs = r.decisionNs
        }
        val p = pendingCommand ?: return
        if (p.id != commandId) return
        if (!success) {
            gestureErrors++
            pendingCommand = null
            player.cancelPending()
            r?.outcome = "gesto cancelado por el sistema"
            events += EngineEvent(LogLevel.ERROR, "El sistema canceló el gesto #$commandId (${p.direction}).")
        }
    }

    // ---- Procesamiento de un fotograma ----------------------------------------------------
    fun onFrame(frame: FrameObservation): EngineOutput {
        val t = frame.timestampNs
        lastProcessedNs = max(frame.processedAtNs, t)

        if (captureLostReason != null) {
            return buildOutput(t, noDecision("Captura interrumpida"), null, emptyMap(), valid = false)
        }
        val lf = lastFrameNs
        if (lf != null) {
            val dt = (t - lf) / 1e9f
            if (dt in 0.005f..0.5f) framePeriodSec = 0.85f * framePeriodSec + 0.15f * dt
            if (t - lf > config.staleMs * 3_000_000L) {
                tracker.reset()
                selfFilter.reset()
                validFrames = 0
                log(LogLevel.WARN, "Hueco largo entre fotogramas: seguimiento reiniciado.")
            }
        }
        lastFrameNs = t
        if (stale) {
            stale = false
            validFrames = 0
        }
        validFrames++
        frameAgeSec = (lastProcessedNs - t) / 1e9f + (if (config.compensateLatency) config.captureLagSec else 0f)

        // Observación del jugador y resolución de movimientos pendientes.
        handlePlayerEvent(player.onObservation(frame.player, t), t)
        handlePlayerEvent(player.poll(t, confirmTimeoutNs()), t)
        val pending = player.pending
        if (pending == null) pendingCommand = null

        var laneDets = LaneAssigner.assign(profile, frame.detections, player.lane, config.minDetectionConfidence)
        if (config.selfFilter) {
            val watch = setOfNotNull(player.lane, player.pending?.target)
            laneDets = selfFilter.filter(t, laneDets, watch) { lane ->
                log(LogLevel.WARN, "Se ignora una detección inmóvil sobre su propio vehículo en $lane (parte del sprite fuera de la caja calibrada).")
            }
        }
        tracker.update(t, laneDets)

        val valid = validFrames >= config.confirmFrames
        val windows = predictor.buildWindows(tracker, t)
        val assessments = predictor.assess(windows, valid)

        if (!valid) {
            return buildOutput(t, noDecision("Calentando: ${validFrames}/${config.confirmFrames} fotogramas"), null, assessments, false)
        }

        val sinceMoveSec = lastMoveNs?.let { ((t - it) / 1e9).toFloat() } ?: 10f
        val blocked = if (pendingCommand != null) confirmTimeoutNs() / 1e9f else 0f
        val earliest = earliestStartSec()
        val plan = planner.plan(player.lane, windows, sinceMoveSec, lastDir, blocked, earliest)

        var decision = decide(plan, assessments)
        var command: MoveCommand? = null

        if (plan.moveNow != null) {
            val block = guard(t) ?: transitVeto(plan.moveNow, windows, earliest)
            if (block == null) {
                val dir = plan.moveNow
                val target = player.lane.shifted(dir.delta)
                if (target != null) {
                    command = MoveCommand(nextCommandId++, dir, player.lane, target)
                    lastMoveNs = t
                    lastDir = dir.delta
                    pendingCommand = command
                    player.onMoveIssued(t, command)
                    startReaction(command, windows, t, decision)
                    log(LogLevel.INFO, "Orden #${command.id}: ${dir.name} (${command.from} -> ${command.target}). ${decision.reason}")
                }
            } else {
                decision = decision.copy(action = Action.STAY, reason = decision.reason + " [movimiento bloqueado: $block]")
            }
        }

        return buildOutput(t, decision, command, assessments, true, plan)
    }

    /** Instante (s desde la captura) en que un gesto ordenado ahora haría mover el vehículo. */
    private fun earliestStartSec(): Float {
        if (!config.compensateLatency) return 0f
        val pipeline = frameAgeSec + actuationSec
        // Un gesto que llegue con el vehículo aún en movimiento se pierde: no puede surtir efecto antes.
        val busy = lastFrameNs?.let { ((moveBusyUntilNs - it) / 1e9f) + 0.02f } ?: 0f
        return max(pipeline, busy)
    }

    private fun confirmTimeoutNs(): Long {
        val dynamic = ((actuationSec + config.laneChangeSec) * 2000f + 300f).toLong()
        return max(config.moveConfirmTimeoutMs, dynamic) * 1_000_000L
    }

    /** Devuelve el motivo por el que no se puede emitir una orden ahora, o null si se puede. */
    private fun guard(t: Long): String? {
        if (emergency) return "parada de emergencia"
        if (!armed) return "piloto automático desactivado"
        if (pendingCommand != null) return "hay un movimiento pendiente"
        val lm = lastMoveNs
        if (lm != null && t - lm < config.minMoveIntervalMs * 1_000_000L) return "tiempo mínimo entre movimientos"
        return null
    }

    /**
     * Última barrera de seguridad: no se entra en un carril al que va a llegar un obstáculo confirmado
     * durante el cambio, salvo que quedarse sea todavía peor.
     */
    private fun transitVeto(dir: MoveDirection, windows: Map<Lane, List<ObstacleWindow>>, earliest: Float): String? {
        if (!config.transitVeto) return null
        val target = player.lane.shifted(dir.delta) ?: return "no hay carril en esa dirección"
        val lo = earliest - config.safetyMarginSec
        val hi = earliest + config.laneChangeSec + config.safetyMarginSec
        fun overlap(lane: Lane): Float = windows[lane].orEmpty()
            .filter { it.weight >= 0.5f }
            .sumOf { max(0f, min(hi, it.clear) - max(lo, it.arrive)).toDouble() }.toFloat()
        val tgt = overlap(target)
        val stay = overlap(player.lane)
        return if (tgt > 0.02f && tgt > stay) {
            log(LogLevel.WARN, "Veto: $target tendría un obstáculo durante el cambio (%.2fs de solape)".format(Locale.US, tgt))
            "$target ocupado durante el cambio"
        } else null
    }

    private fun startReaction(cmd: MoveCommand, windows: Map<Lane, List<ObstacleWindow>>, t: Long, d: Decision) {
        val trigger = windows[cmd.from].orEmpty().filter { it.weight >= 0.5f }.minByOrNull { it.arrive }
        val track = trigger?.let { w -> tracker.tracks.firstOrNull { it.id == w.trackId } }
        val arrival = trigger?.let { t + (it.arrive * 1e9f).toLong() }
        val r = Reaction(
            cmd, track?.id, track?.firstSeenNs, track?.confirmedNs,
            (lastProcessedNs - t) / 1e6f + (if (config.compensateLatency) config.captureLagSec * 1000f else 0f),
            lastProcessedNs, trigger?.arrive, arrival,
        )
        reactions.addLast(r)
        while (reactions.size > 12) reactions.removeFirst()
    }

    private fun handlePlayerEvent(e: PlayerEvent?, t: Long) {
        when (e) {
            null -> Unit
            is PlayerEvent.MoveConfirmed -> {
                pendingCommand = null
                val r = reactionOf(e.cmd.id)
                if (r != null) {
                    r.changedNs = t
                    r.margin = r.arrivalAbsNs?.let { (it - t) / 1e9f }
                    r.outcome = "cambio confirmado"
                    learnLatency(r, e.firstSeenNs ?: t)
                }
                log(LogLevel.INFO, "Cambio confirmado visualmente: ${e.cmd.from} -> ${e.cmd.target}")
            }
            is PlayerEvent.MoveFailed -> {
                movementErrors++
                pendingCommand = null
                reactionOf(e.cmd.id)?.outcome = "no se produjo el cambio"
                log(LogLevel.ERROR, "El cambio a ${e.cmd.target} no se produjo (el jugador sigue en ${e.cmd.from}).")
            }
            is PlayerEvent.MoveAssumed -> {
                pendingCommand = null
                reactionOf(e.cmd.id)?.outcome = "sin confirmación visual"
                log(LogLevel.WARN, "Sin confirmación visual del cambio a ${e.cmd.target}; se asume realizado.")
            }
            is PlayerEvent.ExternalChange -> {
                pendingCommand = null
                log(LogLevel.INFO, "El jugador pasó de ${e.from} a ${e.to}.")
            }
        }
    }

    /**
     * La visión cambia de carril a mitad del trayecto, así que el instante en que se ve el cambio menos
     * media duración del cambio es cuándo empezó a moverse el vehículo; la diferencia con la orden es
     * el retardo del gesto (gesto + respuesta del juego).
     */
    private fun learnLatency(r: Reaction, firstSeenNewLaneNs: Long) {
        moveBusyUntilNs = firstSeenNewLaneNs + (config.laneChangeSec * 0.5e9f).toLong()
        if (!config.learnLatency) return
        val startedNs = firstSeenNewLaneNs - (config.laneChangeSec * 0.5e9f).toLong()
        val measured = ((startedNs - r.decisionNs) / 1e9f).coerceIn(0.02f, 0.8f)
        actuationSec = if (actuationSamples == 0) 0.5f * actuationSec + 0.5f * measured
        else 0.7f * actuationSec + 0.3f * measured
        actuationSamples++
    }

    private fun decide(plan: PlanResult, lanes: Map<Lane, LaneAssessment>): Decision {
        val cur = lanes.getValue(player.lane)
        val ttc = cur.nearestArrivalSec?.let { String.format(Locale.US, "%.2fs", it) } ?: "-"
        return if (plan.moveNow != null) {
            val dir = plan.moveNow
            val target = player.lane.shifted(dir.delta)
            val tState = target?.let { lanes.getValue(it).state }
            val action = if (dir == MoveDirection.LEFT) Action.LEFT else Action.RIGHT
            Decision(
                action, target,
                "Carril ${player.lane} ${cur.state} (llega en $ttc); ir a $target (${tState}); ${plan.note}",
                plan.penalty, plan.deadEnd,
            )
        } else {
            Decision(
                Action.STAY, null,
                "Mantener ${player.lane} (${cur.state}, llega en $ttc): ${plan.note}",
                plan.penalty, plan.deadEnd,
            )
        }
    }

    private fun noDecision(reason: String) = Decision(Action.STAY, null, reason, 0f, false)

    private fun buildOutput(
        t: Long,
        decision: Decision,
        command: MoveCommand?,
        assessments: Map<Lane, LaneAssessment>,
        valid: Boolean,
        plan: PlanResult? = null,
    ): EngineOutput {
        val status = when {
            emergency -> EngineStatus.EMERGENCY_STOP
            captureLostReason != null -> EngineStatus.SUSPENDED_NO_CAPTURE
            stale -> EngineStatus.STALE
            !valid -> EngineStatus.WARMING_UP
            !armed -> EngineStatus.DISABLED
            else -> EngineStatus.RUNNING
        }
        val lanes = if (assessments.isEmpty()) {
            Lane.entries.associateWith { LaneAssessment(it, LaneState.UNKNOWN, null, 0f, 0) }
        } else assessments
        val views = tracker.tracks.map {
            TrackView(
                it.id, it.lane, it.box, tracker.isConfirmed(it), it.misses > 0,
                tracker.ttcSec(it, t), tracker.speedOf(it),
                it.firstSeenNs, it.confirmedNs, tracker.ttcSigmaSec(it, t),
            )
        }
        val recommended = when {
            !valid -> null
            decision.targetLane != null -> decision.targetLane
            plan != null && plan.moves.isNotEmpty() && plan.baselinePenalty >= config.minGain -> plan.finalLane
            else -> player.lane
        }
        val out = EngineOutput(
            timestampNs = t,
            status = status,
            armed = armed,
            playerLane = player.lane,
            playerLaneAssumed = player.assumed,
            recommendedLane = recommended,
            lanes = lanes,
            tracks = views,
            decision = decision,
            command = command,
            events = events.toList().also { events.clear() },
            dangerLevel = if (valid) predictor.dangerLevel(lanes, player.lane) else 0f,
            latency = LatencyStats(frameAgeSec * 1000f, actuationSec * 1000f, actuationSamples),
            reactions = reactions.map { it.record() },
        )
        last = out
        return out
    }
}
