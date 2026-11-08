package com.farks.autopilot.models

import kotlin.math.max
import kotlin.math.min

/** Los tres carriles del juego. */
enum class Lane(val index: Int) {
    LEFT(0), CENTER(1), RIGHT(2);

    fun shifted(delta: Int): Lane? = entries.getOrNull(index + delta)

    companion object {
        fun fromIndex(i: Int): Lane? = entries.getOrNull(i)
    }
}

enum class LaneState { FREE, OCCUPIED, POSSIBLE_DANGER, IMMINENT_DANGER, UNKNOWN }

enum class MoveDirection(val delta: Int) { LEFT(-1), RIGHT(1) }

enum class Action { STAY, LEFT, RIGHT }

/** Punto normalizado (0..1 respecto al ancho/alto de la pantalla). */
data class PointN(val x: Float, val y: Float)

/** Rectángulo normalizado (0..1). */
data class RectN(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersectionArea(o: RectN): Float {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    fun iou(o: RectN): Float {
        val inter = intersectionArea(o)
        val union = width * height + o.width * o.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** Una detección cruda del módulo de visión (puede cubrir varios carriles). */
data class Detection(
    val box: RectN,
    val confidence: Float,
    val label: String = "obstacle",
)

/** Detección ya asignada a un carril concreto. */
data class LaneDetection(val lane: Lane, val box: RectN, val confidence: Float)

/** Dónde ve la visión al automóvil del jugador (lane == null: no localizado). */
data class PlayerObservation(val lane: Lane?, val confidence: Float, val diag: PlayerDiag? = null)

/** Puntuaciones (0..1) de un carril como candidato a contener al jugador; para el diagnóstico visual. */
data class LaneScore(
    val lane: Lane,
    /** Ventana (normalizada) donde se buscó el vehículo en este carril. */
    val window: RectN,
    /** Caja de lo que no es asfalto dentro de la ventana, o null si no había nada. */
    val fgBox: RectN?,
    val fgPixels: Int,
    val area: Float,
    val shape: Float,
    val palette: Float,
    val position: Float,
    val total: Float,
)

/** Por qué se aceptó o rechazó cada carril al localizar el vehículo del jugador. */
data class PlayerDiag(
    val lanes: List<LaneScore>,
    val accepted: Lane?,
    val reason: String,
    val usedContinuity: Boolean,
)

/** Todo lo que el motor recibe por cada fotograma analizado. */
data class FrameObservation(
    /** Instante en que se capturó la imagen. */
    val timestampNs: Long,
    val detections: List<Detection>,
    val player: PlayerObservation? = null,
    /** Instante en que el análisis terminó y se decide (>= timestampNs). La diferencia es la edad de la imagen. */
    val processedAtNs: Long = timestampNs,
)

data class LaneAssessment(
    val lane: Lane,
    val state: LaneState,
    /** Segundos hasta que llega el primer obstáculo (null si no hay ninguno previsto). */
    val nearestArrivalSec: Float?,
    /** Margen de seguridad disponible en este carril, en segundos. */
    val marginSec: Float,
    val obstacleCount: Int,
)

data class Decision(
    val action: Action,
    val targetLane: Lane?,
    val reason: String,
    /** Riesgo estimado del plan elegido (0 = sin riesgo). */
    val risk: Float,
    val deadEnd: Boolean,
)

data class MoveCommand(
    val id: Long,
    val direction: MoveDirection,
    val from: Lane,
    val target: Lane,
)

enum class EngineStatus { DISABLED, WARMING_UP, RUNNING, SUSPENDED_NO_CAPTURE, STALE, EMERGENCY_STOP }

data class TrackView(
    val id: Int,
    val lane: Lane,
    val box: RectN,
    val confirmed: Boolean,
    val coasting: Boolean,
    val ttcSec: Float,
    val speed: Float,
    /** Cuándo se vio por primera vez (nanoTime de captura). */
    val firstSeenNs: Long = 0L,
    /** Cuándo alcanzó la confianza para actuar sobre él; null si aún no. */
    val confirmedNs: Long? = null,
    /** Incertidumbre (1 σ) del TTC, en segundos. */
    val ttcSigmaSec: Float = 0f,
)

/** Línea de tiempo de una reacción: del primer avistamiento a la confirmación visual del cambio. */
data class ReactionRecord(
    val commandId: Long,
    val direction: MoveDirection,
    val from: Lane,
    val target: Lane,
    val triggerTrackId: Int?,
    val firstSeenNs: Long?,
    val confirmedNs: Long?,
    /** Edad de la imagen que motivó la decisión. */
    val frameAgeMs: Float,
    val decisionNs: Long,
    val gestureSentNs: Long?,
    val gestureDoneNs: Long?,
    /** Captura del fotograma en que la visión vio completado el cambio (hacia mitad del trayecto). */
    val laneChangedNs: Long?,
    val ttcAtDecisionSec: Float?,
    /** Tiempo que quedaba para que el obstáculo llegara cuando se vio el cambio; negativo = tarde. */
    val marginAtChangeSec: Float?,
    val outcome: String,
) {
    private fun ms(a: Long?, b: Long?): Float? = if (a != null && b != null) (b - a) / 1e6f else null
    val detectToConfirmMs get() = ms(firstSeenNs, confirmedNs)
    val detectToDecisionMs get() = ms(firstSeenNs, decisionNs)
    val decisionToSentMs get() = ms(decisionNs, gestureSentNs)
    val sentToDoneMs get() = ms(gestureSentNs, gestureDoneNs)
    val decisionToChangeMs get() = ms(decisionNs, laneChangedNs)
    /** Tiempo total de reacción: primer avistamiento -> cambio de carril visto. */
    val totalReactionMs get() = ms(firstSeenNs, laneChangedNs)
}

/** Estimación de latencias que el motor usa para planificar. */
data class LatencyStats(
    val frameAgeMs: Float = 0f,
    /** Retardo estimado entre ordenar el gesto y que el vehículo empiece a moverse. */
    val actuationMs: Float = 0f,
    val actuationSamples: Int = 0,
)

enum class LogLevel { INFO, WARN, ERROR }

data class EngineEvent(val level: LogLevel, val message: String)

data class EngineOutput(
    val timestampNs: Long,
    val status: EngineStatus,
    val armed: Boolean,
    val playerLane: Lane,
    val playerLaneAssumed: Boolean,
    val recommendedLane: Lane?,
    val lanes: Map<Lane, LaneAssessment>,
    val tracks: List<TrackView>,
    val decision: Decision,
    val command: MoveCommand?,
    val events: List<EngineEvent>,
    val dangerLevel: Float,
    val latency: LatencyStats = LatencyStats(),
    val reactions: List<ReactionRecord> = emptyList(),
)
