package com.farks.autopilot.tracking

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneDetection
import com.farks.autopilot.models.RectN
import kotlin.math.abs

/** Un obstáculo seguido a lo largo de varios fotogramas. */
class Track(
    val id: Int,
    val lane: Lane,
    tNs: Long,
    var box: RectN,
    depth: Float,
) {
    var hits = 1
    var consecutiveHits = 1
    var misses = 0
    val firstSeenNs = tNs
    var lastSeenNs = tNs
    var lastDepth = depth

    /** Velocidad aparente de aproximación en unidades de profundidad por segundo. */
    var speed: Float? = null

    /** Muestras (segundos desde el primer avistamiento, profundidad). */
    internal val samples = ArrayDeque<DoubleArray>()
}

/**
 * Seguimiento temporal de obstáculos por carril.
 *
 * - Asocia detecciones de fotogramas consecutivos usando la posición predicha.
 * - Estima la velocidad aparente con regresión lineal sobre la profundidad (perspectiva).
 * - Mantiene un obstáculo "a ciegas" (coasting) unos milisegundos si desaparece.
 */
class ObstacleTracker(
    private val profile: GameProfile,
    private val config: EngineConfig,
) {
    private val geo = profile.road
    private var nextId = 1
    private val list = mutableListOf<Track>()

    val tracks: List<Track> get() = list

    /** Velocidad de avance del mundo (mediana de los obstáculos medidos). */
    var worldSpeed: Float? = null
        private set

    fun reset() {
        list.clear()
        worldSpeed = null
    }

    fun isConfirmed(t: Track): Boolean = t.hits >= config.confirmFrames

    private val collisionDepth: Float get() = geo.depthProxy(profile.collisionY)

    /** Velocidad usada cuando todavía no hay medición: cruzar la carretera en fallbackCrossSec. */
    fun fallbackSpeed(): Float {
        val span = geo.depthProxy(geo.farY) - collisionDepth
        return (span / config.fallbackCrossSec).coerceAtLeast(0.05f)
    }

    fun speedOf(t: Track): Float = t.speed ?: worldSpeed ?: fallbackSpeed()

    fun depthNow(t: Track, nowNs: Long): Float {
        val dt = ((nowNs - t.lastSeenNs) / 1e9).toFloat().coerceAtLeast(0f)
        return t.lastDepth - speedOf(t) * dt
    }

    fun predictedBottom(t: Track, nowNs: Long): Float = geo.yFromDepth(depthNow(t, nowNs))

    /** Segundos hasta que el borde cercano del obstáculo alcanza la línea de colisión (<=0: ya llegó). */
    fun ttcSec(t: Track, nowNs: Long): Float =
        (depthNow(t, nowNs) - collisionDepth) / speedOf(t)

    fun update(tNs: Long, detections: List<LaneDetection>) {
        val maxCoastNs = config.maxCoastMs * 1_000_000L

        for (lane in Lane.entries) {
            val laneTracks = list.filter { it.lane == lane }
            val laneDets = detections.filter { it.lane == lane }
            val usedDet = BooleanArray(laneDets.size)
            val usedTrack = BooleanArray(laneTracks.size)

            data class Pair(val ti: Int, val di: Int, val cost: Float)

            val pairs = ArrayList<Pair>()
            for ((ti, t) in laneTracks.withIndex()) {
                val pred = predictedBottom(t, tNs)
                val expectedStep = abs(pred - t.box.bottom)
                val gate = maxOf(0.08f, 0.05f + 1.5f * expectedStep)
                for ((di, d) in laneDets.withIndex()) {
                    val dy = abs(d.box.bottom - pred)
                    if (dy > gate) continue
                    val dx = abs(d.box.centerX - t.box.centerX) / geo.laneWidth(d.box.bottom).coerceAtLeast(0.01f)
                    pairs += Pair(ti, di, dy + 0.25f * dx)
                }
            }
            pairs.sortBy { it.cost }
            for (p in pairs) {
                if (usedTrack[p.ti] || usedDet[p.di]) continue
                usedTrack[p.ti] = true
                usedDet[p.di] = true
                applyMatch(laneTracks[p.ti], laneDets[p.di], tNs)
            }
            for ((ti, t) in laneTracks.withIndex()) {
                if (!usedTrack[ti]) {
                    t.misses++
                    t.consecutiveHits = 0
                }
            }
            for ((di, d) in laneDets.withIndex()) {
                if (!usedDet[di]) {
                    val t = Track(nextId++, lane, tNs, d.box, geo.depthProxy(d.box.bottom))
                    t.samples.addLast(doubleArrayOf(0.0, t.lastDepth.toDouble()))
                    list += t
                }
            }
        }

        // Retirar obstáculos perdidos o que ya pasaron al jugador.
        val passedY = profile.playerBox.bottom + 0.10f
        list.removeAll { t ->
            val lost = tNs - t.lastSeenNs > maxCoastNs
            val passed = predictedBottom(t, tNs) > passedY
            lost || passed
        }
        updateWorldSpeed()
    }

    private fun applyMatch(t: Track, d: LaneDetection, tNs: Long) {
        t.box = d.box
        t.hits++
        t.consecutiveHits++
        t.misses = 0
        t.lastSeenNs = tNs
        t.lastDepth = geo.depthProxy(d.box.bottom)
        val ts = (tNs - t.firstSeenNs) / 1e9
        t.samples.addLast(doubleArrayOf(ts, t.lastDepth.toDouble()))
        while (t.samples.size > 12 || (t.samples.size > 2 && ts - t.samples.first()[0] > 0.6)) {
            t.samples.removeFirst()
        }
        fitSpeed(t)
    }

    private fun fitSpeed(t: Track) {
        val s = t.samples
        if (s.size < 3) return
        val span = s.last()[0] - s.first()[0]
        if (span < 0.09) return
        val n = s.size
        val mt = s.sumOf { it[0] } / n
        val md = s.sumOf { it[1] } / n
        var cov = 0.0
        var varT = 0.0
        for (p in s) {
            cov += (p[0] - mt) * (p[1] - md)
            varT += (p[0] - mt) * (p[0] - mt)
        }
        if (varT < 1e-9) return
        val slope = cov / varT
        val fit = (-slope).toFloat()
        if (fit > 0.05f) {
            val prev = t.speed
            t.speed = if (prev == null) fit else 0.6f * prev + 0.4f * fit
        }
    }

    private fun updateWorldSpeed() {
        val speeds = list.filter { it.hits >= 5 && it.speed != null }.map { it.speed!! }.sorted()
        if (speeds.isEmpty()) return
        val median = speeds[speeds.size / 2]
        val prev = worldSpeed
        worldSpeed = if (prev == null) median else 0.7f * prev + 0.3f * median
    }
}
