package com.farks.autopilot.models

/**
 * Parámetros del motor de decisión. Todos los tiempos están en segundos o milisegundos
 * según el nombre. Los valores por defecto son un punto de partida razonable,
 * pero deben ajustarse con el modo diagnóstico en cada juego.
 */
data class EngineConfig(
    /** Fotogramas consecutivos necesarios para confirmar un obstáculo / empezar a actuar. */
    val confirmFrames: Int = 3,
    /** Tiempo máximo que un obstáculo se sigue "a ciegas" si desaparece de la imagen. */
    val maxCoastMs: Long = 400,
    /** Sin fotogramas durante este tiempo => el estado de los carriles pasa a DESCONOCIDO. */
    val staleMs: Long = 500,
    val minMoveIntervalMs: Long = 350,
    /** Tiempo mínimo antes de poder cambiar de dirección (izquierda<->derecha). */
    val reverseGuardMs: Long = 600,
    /** Tiempo máximo para ver que el cambio de carril se produjo. */
    val moveConfirmTimeoutMs: Long = 700,
    /** Duración del cambio de carril (el jugador ocupa ambos carriles mientras dura). */
    val laneChangeSec: Float = 0.20f,
    val safetyMarginSec: Float = 0.18f,
    /** Tiempo que un obstáculo bloquea el carril desde que llega a la línea de colisión. */
    val dwellSec: Float = 0.55f,
    val horizonSec: Float = 1.8f,
    val imminentSec: Float = 0.45f,
    val possibleSec: Float = 1.0f,
    /** Tiempo supuesto para cruzar la carretera cuando todavía no hay velocidad medida. */
    val fallbackCrossSec: Float = 1.6f,
    val planStepSec: Float = 0.05f,
    /** Observaciones consecutivas del jugador para aceptar que cambió de carril. */
    val playerConfirmFrames: Int = 2,
    /** Mejora mínima de riesgo para justificar un movimiento (evita movimientos innecesarios). */
    val minGain: Float = 0.5f,
    /** Peso de un obstáculo aún sin confirmar (1 solo fotograma) en la planificación. */
    val tentativeWeight: Float = 0.05f,
    val minDetectionConfidence: Float = 0.35f,
)
