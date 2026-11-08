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
    val minMoveIntervalMs: Long = 200,
    /** Tiempo mínimo antes de poder cambiar de dirección (izquierda<->derecha). */
    val reverseGuardMs: Long = 600,
    /** Tiempo máximo para ver que el cambio de carril se produjo. */
    val moveConfirmTimeoutMs: Long = 700,
    /** Duración del cambio de carril (el jugador ocupa ambos carriles mientras dura). */
    val laneChangeSec: Float = 0.20f,
    val safetyMarginSec: Float = 0.12f,
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

    // ---- Reacción anticipada y compensación de latencia -----------------------------------
    /** Retardo inicial supuesto entre ordenar el gesto y que el vehículo empiece a moverse; se aprende en uso. */
    val actuationDelaySec: Float = 0.10f,
    /** Retardo de captura anterior a la marca de tiempo del fotograma (composición, buffers). */
    val captureLagSec: Float = 0.03f,
    /** El planificador cuenta con la edad de la imagen y el retardo del gesto antes de que el vehículo se mueva. */
    val compensateLatency: Boolean = true,
    /** Aprende el retardo real midiendo cuánto tarda el cambio en verse en pantalla. */
    val learnLatency: Boolean = true,
    /** Tiempo que un obstáculo ocupa el carril según su velocidad y tamaño, en vez de un valor fijo. */
    val adaptiveDwell: Boolean = true,
    val minDwellSec: Float = 0.25f,
    val maxDwellSec: Float = 1.2f,
    /** Velocidad por inferencia bayesiana: mezcla la medición con la velocidad habitual del juego. */
    val speedPrior: Boolean = true,
    /** Ruido típico de la posición vertical medida (normalizado; ≈1,5 px en 1200). */
    val depthNoise: Float = 0.0012f,
    /** Cuántas desviaciones típicas del TTC se ensancha la ventana de un obstáculo (0 = sin incertidumbre). */
    val uncertaintyK: Float = 0.5f,
    val maxUncertaintySec: Float = 0.4f,
    /** Veto duro: no se entra en un carril con un obstáculo confirmado llegando durante el cambio. */
    val transitVeto: Boolean = true,
    /** Ignora detecciones inmóviles sobre el propio vehículo (partes del sprite fuera de la caja calibrada). */
    val selfFilter: Boolean = true,
    val selfStaticSec: Float = 0.15f,
    /** Un cambio de carril del jugador que no se ordenó debe persistir este tiempo para aceptarse. */
    val externalChangeMs: Long = 350,
    /** El carril ORDENADO se acepta con una sola observación fiable (el esperado no necesita histéresis). */
    val confirmExpectedInOneFrame: Boolean = true,
    /** Confirma con 2 fotogramas si la trayectoria es coherente con un obstáculo que se acerca. */
    val fastConfirm: Boolean = false,
    /** Rango de velocidad aceptado para la confirmación rápida, relativo a la velocidad habitual del juego. */
    val fastConfirmMinRel: Float = 0.5f,
    val fastConfirmMaxRel: Float = 2.5f,

    // ---- Profundidad por escala del coche (juegos con carretera curva / perspectiva no lineal) -------------
    /**
     * Mide la distancia con el ANCHO aparente del coche (1/ancho crece linealmente con el tiempo a velocidad constante,
     * comprobado en una grabación real de Lane Rush) en vez de con la posición vertical, que en carreteras curvas
     * o con horizonte tapado no es lineal y subestima la velocidad de aproximación de los coches lejanos.
     */
    val scaleDepth: Boolean = false,
    /** Ancho del coche del obstáculo en el momento de contacto, relativo al ancho del vehículo del jugador. */
    val collisionWidthRatio: Float = 0.7f,
    /** Ruido típico del ancho medido (normalizado a la anchura de pantalla). */
    val scaleWidthNoise: Float = 0.003f,
    /** El juego acelera con el tiempo: la velocidad habitual se actualiza con los obstáculos recientes y se extrapola su tendencia. */
    val adaptWorldSpeed: Boolean = true,
) {
    companion object {
        /** Comportamiento anterior a la optimización de reacción (para comparar en pruebas). */
        fun legacy(base: EngineConfig = EngineConfig()) = base.copy(
            compensateLatency = false, learnLatency = false, adaptiveDwell = false, speedPrior = false,
            uncertaintyK = 0f, safetyMarginSec = 0.18f, transitVeto = false, selfFilter = false, externalChangeMs = 0,
            confirmExpectedInOneFrame = false, minMoveIntervalMs = 350, fastConfirm = false, scaleDepth = false, adaptWorldSpeed = false,
        )
    }
}
