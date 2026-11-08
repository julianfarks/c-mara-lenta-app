package com.farks.autopilot.automation

import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.models.PlayerObservation

sealed class PlayerEvent {
    /** La visión confirmó que el jugador llegó al carril ordenado. */
    data class MoveConfirmed(val cmd: MoveCommand) : PlayerEvent()

    /** Pasó el tiempo y el jugador sigue en el carril de origen: el gesto no tuvo efecto. */
    data class MoveFailed(val cmd: MoveCommand) : PlayerEvent()

    /** Sin observación visual del jugador: se asume que el gesto funcionó. */
    data class MoveAssumed(val cmd: MoveCommand) : PlayerEvent()

    /** El jugador cambió de carril sin que se ordenara (o a otro carril distinto). */
    data class ExternalChange(val from: Lane, val to: Lane) : PlayerEvent()
}

/**
 * Mantiene el carril actual del jugador combinando la observación visual
 * (con histéresis) y el resultado esperado de los gestos ordenados.
 */
class PlayerLaneTracker(initial: Lane, private val config: EngineConfig) {
    var lane: Lane = initial
        private set

    /** true si el carril actual se dedujo de un gesto y la visión aún no lo confirma. */
    var assumed: Boolean = false
        private set

    var pending: MoveCommand? = null
        private set
    private var pendingIssuedNs = 0L
    private var sawObservationSincePending = false

    private var candidate: Lane? = null
    private var candidateCount = 0

    fun reset(initial: Lane) {
        lane = initial
        assumed = false
        pending = null
        candidate = null
        candidateCount = 0
    }

    fun onMoveIssued(tNs: Long, cmd: MoveCommand) {
        pending = cmd
        pendingIssuedNs = tNs
        sawObservationSincePending = false
        candidate = null
        candidateCount = 0
    }

    /** Se cancela el seguimiento del movimiento pendiente (p. ej. el sistema canceló el gesto). */
    fun cancelPending() {
        pending = null
    }

    fun onObservation(obs: PlayerObservation?): PlayerEvent? {
        val seen = obs?.lane
        if (obs == null || seen == null || obs.confidence < 0.5f) return null
        sawObservationSincePending = true

        if (seen == lane) {
            assumed = false
            candidate = null
            candidateCount = 0
            return null
        }
        if (seen == candidate) candidateCount++ else {
            candidate = seen
            candidateCount = 1
        }
        if (candidateCount < config.playerConfirmFrames) return null

        val previous = lane
        lane = seen
        assumed = false
        candidate = null
        candidateCount = 0
        val p = pending
        return if (p != null && seen == p.target) {
            pending = null
            PlayerEvent.MoveConfirmed(p)
        } else {
            if (p != null && seen != p.from) pending = null
            PlayerEvent.ExternalChange(previous, seen)
        }
    }

    /** Debe llamarse en cada fotograma para resolver movimientos que superaron el tiempo máximo. */
    fun poll(tNs: Long): PlayerEvent? {
        val p = pending ?: return null
        if (tNs - pendingIssuedNs < config.moveConfirmTimeoutMs * 1_000_000L) return null
        pending = null
        return if (sawObservationSincePending) {
            PlayerEvent.MoveFailed(p)
        } else {
            lane = p.target
            assumed = true
            PlayerEvent.MoveAssumed(p)
        }
    }
}
