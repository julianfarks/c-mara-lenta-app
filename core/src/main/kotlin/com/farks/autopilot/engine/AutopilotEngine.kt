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
import com.farks.autopilot.models.LogLevel
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.models.MoveDirection
import com.farks.autopilot.models.TrackView
import com.farks.autopilot.prediction.LanePredictor
import com.farks.autopilot.tracking.ObstacleTracker
import java.util.Locale

/**
 * Motor de piloto automático: puro (sin dependencias de Android) y determinista.
 * Recibe observaciones fotograma a fotograma y devuelve la decisión y, si procede,
 * una orden de movimiento. Un único hilo debe llamar a sus métodos.
 */
class AutopilotEngine(
    val profile: GameProfile,
    val config: EngineConfig = EngineConfig(),
) {
    private val tracker = ObstacleTracker(profile, config)
    private val predictor = LanePredictor(config)
    private val planner = PathPlanner(config)
    private val player = PlayerLaneTracker(profile.playerLane, config)

    /** Si es false se analiza y se muestra el estado, pero nunca se emiten órdenes. */
    var armed: Boolean = false

    private var emergency = false
    private var captureLostReason: String? = null
    private var stale = false
    private var validFrames = 0
    private var lastFrameNs: Long? = null

    private var lastMoveNs: Long? = null
    private var lastDir = 0
    private var pendingCommand: MoveCommand? = null
    private var nextCommandId = 1L
    var gestureErrors = 0
        private set
    var movementErrors = 0
        private set

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
        events += EngineEvent(LogLevel.WARN, reason)
    }

    fun resetEmergency() {
        emergency = false
    }

    fun onGestureResult(commandId: Long, success: Boolean) {
        val p = pendingCommand ?: return
        if (p.id != commandId) return
        if (!success) {
            gestureErrors++
            pendingCommand = null
            player.cancelPending()
            events += EngineEvent(LogLevel.ERROR, "El sistema canceló el gesto #$commandId (${p.direction}).")
        }
    }

    // ---- Procesamiento de un fotograma ----------------------------------------------------

    fun onFrame(frame: FrameObservation): EngineOutput {
        val t = frame.timestampNs

        if (captureLostReason != null) {
            return buildOutput(t, noDecision("Captura interrumpida"), null, emptyMap(), valid = false)
        }
        val lf = lastFrameNs
        if (lf != null && t - lf > config.staleMs * 3_000_000L) {
            tracker.reset()
            validFrames = 0
            log(LogLevel.WARN, "Hueco largo entre fotogramas: seguimiento reiniciado.")
        }
        lastFrameNs = t
        if (stale) {
            stale = false
            validFrames = 0
        }
        validFrames++

        // Observación del jugador y resolución de movimientos pendientes.
        handlePlayerEvent(player.onObservation(frame.player))
        handlePlayerEvent(player.poll(t))
        val pending = player.pending
        if (pending == null) pendingCommand = null

        val laneDets = LaneAssigner.assign(profile, frame.detections, player.lane, config.minDetectionConfidence)
        tracker.update(t, laneDets)

        val valid = validFrames >= config.confirmFrames
        val windows = predictor.buildWindows(tracker, t)
        val assessments = predictor.assess(windows, valid)

        if (!valid) {
            return buildOutput(t, noDecision("Calentando: ${validFrames}/${config.confirmFrames} fotogramas"), null, assessments, false)
        }

        val sinceMoveSec = lastMoveNs?.let { ((t - it) / 1e9).toFloat() } ?: 10f
        val blocked = if (pendingCommand != null) config.moveConfirmTimeoutMs / 1000f else 0f
        val plan = planner.plan(player.lane, windows, sinceMoveSec, lastDir, blocked)

        var decision = decide(plan, assessments)
        var command: MoveCommand? = null

        if (plan.moveNow != null) {
            val block = guard(t)
            if (block == null) {
                val dir = plan.moveNow
                val target = player.lane.shifted(dir.delta)
                if (target != null) {
                    command = MoveCommand(nextCommandId++, dir, player.lane, target)
                    lastMoveNs = t
                    lastDir = dir.delta
                    pendingCommand = command
                    player.onMoveIssued(t, command)
                    log(LogLevel.INFO, "Orden #${command.id}: ${dir.name} (${command.from} -> ${command.target}). ${decision.reason}")
                }
            } else {
                decision = decision.copy(action = Action.STAY, reason = decision.reason + " [movimiento bloqueado: $block]")
            }
        }

        return buildOutput(t, decision, command, assessments, true, plan)
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

    private fun handlePlayerEvent(e: PlayerEvent?) {
        when (e) {
            null -> Unit
            is PlayerEvent.MoveConfirmed -> {
                pendingCommand = null
                log(LogLevel.INFO, "Cambio confirmado visualmente: ${e.cmd.from} -> ${e.cmd.target}")
            }
            is PlayerEvent.MoveFailed -> {
                movementErrors++
                pendingCommand = null
                log(LogLevel.ERROR, "El cambio a ${e.cmd.target} no se produjo (el jugador sigue en ${e.cmd.from}).")
            }
            is PlayerEvent.MoveAssumed -> {
                pendingCommand = null
                log(LogLevel.WARN, "Sin confirmación visual del cambio a ${e.cmd.target}; se asume realizado.")
            }
            is PlayerEvent.ExternalChange -> {
                pendingCommand = null
                log(LogLevel.INFO, "El jugador pasó de ${e.from} a ${e.to}.")
            }
        }
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
        )
        last = out
        return out
    }
}
