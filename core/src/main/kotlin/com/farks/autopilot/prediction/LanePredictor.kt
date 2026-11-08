package com.farks.autopilot.prediction

import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneAssessment
import com.farks.autopilot.models.LaneState
import com.farks.autopilot.tracking.ObstacleTracker

/** Intervalo de tiempo (desde ahora) durante el cual un obstáculo bloquea un carril. */
data class ObstacleWindow(
    val trackId: Int,
    val arrive: Float,
    val clear: Float,
    /** 1 = confirmado; menor = todavía sin confirmar. */
    val weight: Float,
)

class LanePredictor(private val config: EngineConfig) {

    fun buildWindows(tracker: ObstacleTracker, nowNs: Long): Map<Lane, List<ObstacleWindow>> {
        val result = Lane.entries.associateWith { mutableListOf<ObstacleWindow>() }
        for (t in tracker.tracks) {
            val confirmed = tracker.isConfirmed(t)
            // Un obstáculo desaparecido sigue ocupando su sitio: se adelanta un poco por incertidumbre.
            val uncertainty = 0.05f * t.misses
            val nominal = tracker.ttcSec(t, nowNs) - uncertainty
            // Incertidumbre propia del tiempo de llegada: ancha al principio, estrecha con más fotogramas.
            val sigma = if (confirmed) config.uncertaintyK * tracker.ttcSigmaSec(t, nowNs) else 0f
            val arrive = nominal - sigma
            val clear = nominal + tracker.dwellSecFor(t) + sigma
            if (clear < -config.safetyMarginSec) continue
            val weight = if (confirmed) 1f else config.tentativeWeight
            result.getValue(t.lane) += ObstacleWindow(t.id, arrive, clear, weight)
        }
        return result
    }

    fun assess(
        windows: Map<Lane, List<ObstacleWindow>>,
        valid: Boolean,
    ): Map<Lane, LaneAssessment> = Lane.entries.associateWith { lane ->
        val ws = windows.getValue(lane).filter { it.clear > 0f }
        if (!valid) {
            return@associateWith LaneAssessment(lane, LaneState.UNKNOWN, null, 0f, ws.size)
        }
        val nearest = ws.minOfOrNull { it.arrive }
        val occupied = ws.any { it.arrive <= 0f && it.clear > 0f }
        val state = when {
            occupied -> LaneState.OCCUPIED
            nearest != null && nearest <= config.imminentSec -> LaneState.IMMINENT_DANGER
            nearest != null && nearest <= config.possibleSec -> LaneState.POSSIBLE_DANGER
            else -> LaneState.FREE
        }
        val margin = (nearest ?: config.horizonSec).coerceIn(0f, config.horizonSec)
        LaneAssessment(lane, state, nearest?.coerceAtLeast(0f), margin, ws.size)
    }

    /** Nivel global de peligro 0..1 para mostrar en la interfaz. */
    fun dangerLevel(assessments: Map<Lane, LaneAssessment>, playerLane: Lane): Float {
        val a = assessments.getValue(playerLane)
        return when (a.state) {
            LaneState.OCCUPIED -> 1f
            LaneState.IMMINENT_DANGER -> 0.85f
            LaneState.POSSIBLE_DANGER -> 0.5f
            LaneState.UNKNOWN -> 0f
            LaneState.FREE -> if (assessments.values.all { it.state != LaneState.FREE && it.state != LaneState.UNKNOWN }) 0.3f else 0f
        }
    }
}
