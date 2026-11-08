package com.farks.autopilot.calibration

import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneDetection
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometría de la carretera en coordenadas normalizadas: un trapecio definido por
 * sus cuatro esquinas (cerca = borde inferior, lejos = borde superior).
 * Los tres carriles se obtienen dividiendo el ancho en tres partes iguales.
 */
data class RoadGeometry(
    val nearLeft: PointN,
    val nearRight: PointN,
    val farLeft: PointN,
    val farRight: PointN,
) {
    init {
        require(nearLeft.y > farLeft.y + 0.02f && nearRight.y > farRight.y + 0.02f) {
            "El borde cercano de la carretera debe estar claramente por debajo del lejano"
        }
        require(nearRight.x > nearLeft.x && farRight.x > farLeft.x) {
            "El borde derecho debe estar a la derecha del izquierdo"
        }
    }

    val nearY: Float get() = (nearLeft.y + nearRight.y) / 2f
    val farY: Float get() = (farLeft.y + farRight.y) / 2f

    private val bL = (farLeft.x - nearLeft.x) / (farLeft.y - nearLeft.y)
    private val aL = nearLeft.x - bL * nearLeft.y
    private val bR = (farRight.x - nearRight.x) / (farRight.y - nearRight.y)
    private val aR = nearRight.x - bR * nearRight.y

    fun leftX(y: Float): Float = aL + bL * y
    fun rightX(y: Float): Float = aR + bR * y

    fun laneWidth(y: Float): Float = (rightX(y) - leftX(y)) / 3f

    fun laneLeftX(lane: Lane, y: Float): Float = leftX(y) + laneWidth(y) * lane.index
    fun laneRightX(lane: Lane, y: Float): Float = leftX(y) + laneWidth(y) * (lane.index + 1)
    fun laneCenterX(lane: Lane, y: Float): Float = leftX(y) + laneWidth(y) * (lane.index + 0.5f)

    /** Línea del horizonte (punto de fuga) si los bordes convergen; null si la vista es casi paralela. */
    val horizonY: Float? = run {
        val d = bL - bR
        if (abs(d) < 1e-3f) null else {
            val y = (aR - aL) / d
            if (y < farY - 0.02f && y > -3f) y else null
        }
    }

    /**
     * Proxy de profundidad: crece con la distancia. Para un objeto que se acerca a
     * velocidad constante en el mundo, esta magnitud disminuye linealmente con el tiempo
     * (vista en perspectiva). Sin horizonte válido se usa una aproximación lineal en y.
     */
    fun depthProxy(y: Float): Float {
        val h = horizonY
        return if (h != null) 1f / max(y - h, 0.004f) else LINEAR_C - y
    }

    fun yFromDepth(depth: Float): Float {
        val h = horizonY
        return if (h != null) h + 1f / max(depth, 1e-4f) else LINEAR_C - depth
    }

    /** Carril al que pertenece el punto (x, y) o null si cae fuera de la carretera. */
    fun laneAt(x: Float, y: Float): Lane? {
        if (y < farY - 0.05f || y > nearY + 0.2f) return null
        val l = leftX(y)
        val w = laneWidth(y)
        if (x < l || x > l + 3f * w) return null
        val i = ((x - l) / w).toInt().coerceIn(0, 2)
        return Lane.fromIndex(i)
    }

    /** Carriles que cubre una caja (por solape en su borde inferior). */
    fun lanesCovered(box: RectN, minOverlap: Float = 0.3f): List<Lane> {
        val y = box.bottom.coerceIn(farY, nearY)
        val w = laneWidth(y)
        if (w <= 0f) return emptyList()
        val result = Lane.entries.filter { lane ->
            val overlap = min(box.right, laneRightX(lane, y)) - max(box.left, laneLeftX(lane, y))
            overlap / w >= minOverlap
        }
        if (result.isNotEmpty()) return result
        return listOfNotNull(laneAt(box.centerX, y))
    }

    companion object {
        const val LINEAR_C = 1.5f
    }
}

/** Perfil de calibración de un videojuego. */
data class GameProfile(
    val packageName: String,
    val displayName: String,
    val road: RoadGeometry,
    /** Caja del automóvil del jugador en el carril donde se calibró. */
    val playerBox: RectN,
    val playerLane: Lane,
    /** Apariencia aprendida del vehículo del jugador (paleta, área, forma, posición); null si no se pudo aprender. */
    val playerLook: com.farks.autopilot.player.PlayerLook? = null,
) {
    /** Hasta dónde se analiza por debajo de la caja: el coche suele sobresalir (parachoques, ruedas). */
    val playerZoneBottom: Float get() = maxOf(playerBox.bottom, minOf(0.995f, playerBox.bottom + 0.4f * playerBox.height))

    /** Zona completa del vehículo (caja ampliada); se excluye al aprender el color de la carretera. */
    val playerZone: RectN get() = RectN(
        playerBox.left - 0.15f * playerBox.width, playerBox.top - 0.01f,
        playerBox.right + 0.15f * playerBox.width, playerZoneBottom,
    )

    /** Línea de colisión: el borde superior del automóvil del jugador. */
    val collisionY: Float get() = playerBox.top

    /** Caja equivalente del jugador en otro carril (mismo ancho y altura). */
    fun playerBoxAt(lane: Lane): RectN {
        val cx = road.laneCenterX(lane, playerBox.bottom)
        val half = playerBox.width / 2f
        return RectN(cx - half, playerBox.top, cx + half, playerBox.bottom)
    }
}

/** Convierte detecciones crudas en detecciones por carril. */
object LaneAssigner {
    fun assign(
        profile: GameProfile,
        detections: List<Detection>,
        currentPlayerLane: Lane,
        minConfidence: Float,
    ): List<LaneDetection> {
        val geo = profile.road
        val playerBox = profile.playerBoxAt(currentPlayerLane)
        val out = ArrayList<LaneDetection>()
        for (d in detections) {
            if (d.confidence < minConfidence) continue
            val b = d.box
            if (b.width <= 0f || b.height <= 0f) continue
            if (b.bottom < geo.farY - 0.02f) continue
            // El propio automóvil del jugador no es un obstáculo.
            if (b.iou(playerBox) > 0.6f) continue
            for (lane in geo.lanesCovered(b)) {
                val y = b.bottom.coerceIn(geo.farY, geo.nearY)
                val clipped = RectN(
                    max(b.left, geo.laneLeftX(lane, y)),
                    b.top,
                    min(b.right, geo.laneRightX(lane, y)),
                    b.bottom,
                )
                if (clipped.width > 0f) out += LaneDetection(lane, clipped, d.confidence)
            }
        }
        return out
    }
}
