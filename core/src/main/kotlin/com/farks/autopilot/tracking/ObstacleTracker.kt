package com.farks.autopilot.tracking

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneDetection
import com.farks.autopilot.models.RectN
import kotlin.math.abs
import kotlin.math.max

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

    /** Desviación típica (1 σ) de la velocidad estimada. */
    var speedSigma: Float = Float.MAX_VALUE

    /** Instante en que alcanzó la confianza para actuar sobre él. */
    var confirmedNs: Long? = null

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
    val profile: GameProfile,
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

    fun isConfirmed(t: Track): Boolean = t.confirmedNs != null || t.hits >= config.confirmFrames

    private val collisionDepth: Float get() = geo.depthProxy(profile.collisionY)

    /** Velocidad usada cuando todavía no hay medición: cruzar la carretera en fallbackCrossSec. */
    fun fallbackSpeed(): Float {
        val span = geo.depthProxy(geo.farY) - collisionDepth
        return (span / config.fallbackCrossSec).coerceAtLeast(0.05f)
    }

    fun speedOf(t: Track): Float = t.speed ?: priorSpeed()

    /** Velocidad habitual del juego (medida en otros obstáculos) o, si no hay, la supuesta. */
    fun priorSpeed(): Float = worldSpeed ?: fallbackSpeed()

    /** Incertidumbre relativa de la velocidad de un obstáculo (σ/velocidad). */
    private fun priorSigma(): Float = priorSpeed() * (if (worldSpeed != null) 0.25f else 0.6f)

    fun speedSigmaOf(t: Track): Float = if (t.speed != null) t.speedSigma else priorSigma()

    /** Incertidumbre (1 σ, s) del tiempo hasta colisión: crece con el TTC y baja al medir más. */
    fun ttcSigmaSec(t: Track, nowNs: Long): Float {
        val ttc = ttcSec(t, nowNs).coerceAtLeast(0f)
        val rel = (speedSigmaOf(t) / speedOf(t)).coerceAtMost(1f)
        return (ttc * rel).coerceAtMost(config.maxUncertaintySec)
    }

    /**
     * Tiempo que el obstáculo ocupa la zona del jugador: desde que su borde inferior alcanza el
     * borde superior del vehículo hasta que su borde superior sale por debajo del vehículo.
     * Depende de la velocidad y del tamaño aparente (un coche lento o largo bloquea más tiempo).
     */
    fun dwellSecFor(t: Track): Float {
        if (!config.adaptiveDwell) return config.dwellSec
        val v = speedOf(t)
        if (v < 0.05f) return config.dwellSec
        val hc = max(t.box.height, 0.9f * t.box.width)
        val lwNow = geo.laneWidth(t.box.bottom.coerceIn(geo.farY, geo.nearY)).coerceAtLeast(0.01f)
        val pb = profile.playerBox.bottom
        val yClear = pb + hc * geo.laneWidth(pb.coerceIn(geo.farY, geo.nearY)) / lwNow
        val span = geo.depthProxy(profile.collisionY) - geo.depthProxy(yClear.coerceAtMost(geo.nearY + 0.5f))
        return (span / v).coerceIn(config.minDwellSec, config.maxDwellSec)
    }

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
                    if (config.confirmFrames <= 1) t.confirmedNs = tNs
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
        // Una detección que toca la zona del jugador está recortada por abajo: su borde inferior ya no
        // indica la profundidad real. Se mantiene vivo el seguimiento extrapolando y no se mide con ella.
        val atZone = config.speedPrior && d.box.bottom >= profile.collisionY - 0.008f
        val extrapolated = depthNow(t, tNs)
        t.box = d.box
        t.hits++
        t.consecutiveHits++
        t.misses = 0
        if (t.confirmedNs == null && t.hits >= config.confirmFrames) t.confirmedNs = tNs
        t.lastSeenNs = tNs
        if (atZone) {
            t.lastDepth = extrapolated
            return
        }
        t.lastDepth = geo.depthProxy(d.box.bottom)
        val ts = (tNs - t.firstSeenNs) / 1e9
        t.samples.addLast(doubleArrayOf(ts, t.lastDepth.toDouble()))
        while (t.samples.size > 12 || (t.samples.size > 2 && ts - t.samples.first()[0] > 0.6)) {
            t.samples.removeFirst()
        }
        if (config.speedPrior) fitSpeedBayes(t) else fitSpeed(t)
        if (config.fastConfirm && t.confirmedNs == null && t.hits >= 2 && approachesConsistently(t)) t.confirmedNs = tNs
    }

    /**
     * ¿Se mueve como un obstáculo real? Avanza hacia el jugador a una velocidad cercana a la habitual
     * del juego y no salta de posición. Un parpadeo de la detección (reflejo, sombra) casi nunca lo cumple.
     */
    private fun approachesConsistently(t: Track): Boolean {
        val s = t.samples
        if (s.size < 2) return false
        val a = s[s.size - 2]
        val b = s[s.size - 1]
        val dt = b[0] - a[0]
        if (dt < 0.01) return false
        val v = ((a[1] - b[1]) / dt).toFloat()
        val prior = priorSpeed()
        return v >= config.fastConfirmMinRel * prior && v <= config.fastConfirmMaxRel * prior
    }

    /**
     * Velocidad = media ponderada por precisión entre la pendiente medida (regresión de la profundidad
     * en el tiempo) y la velocidad habitual del juego. Con pocas muestras manda la costumbre; con
     * muchas manda la medición. Así el TTC es útil desde el primer fotograma sin fiarse de ruido.
     */
    private fun fitSpeedBayes(t: Track) {
        val s = t.samples
        if (s.size < 2) return
        val n = s.size
        val mt = s.sumOf { it[0] } / n
        val md = s.sumOf { it[1] } / n
        var cov = 0.0
        var varT = 0.0
        for (p in s) {
            cov += (p[0] - mt) * (p[1] - md)
            varT += (p[0] - mt) * (p[0] - mt)
        }
        if (varT < 1e-6) return
        val fit = (-(cov / varT)).toFloat()
        // Ruido de la profundidad: du = u² · dy
        val u = md.toFloat()
        val sigmaU = (config.depthNoise * u * u).toDouble()
        val varFit = (sigmaU * sigmaU / varT).coerceAtLeast(1e-6)
        val prior = priorSpeed()
        val sp = priorSigma().toDouble()
        val varPrior = sp * sp
        val prec = 1.0 / varFit + 1.0 / varPrior
        var post = ((fit / varFit) + (prior / varPrior)) / prec
        if (post < 0.05) post = 0.05
        t.speed = post.toFloat()
        t.speedSigma = kotlin.math.sqrt(1.0 / prec).toFloat()
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
            t.speedSigma = 0.1f * t.speed!!
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
