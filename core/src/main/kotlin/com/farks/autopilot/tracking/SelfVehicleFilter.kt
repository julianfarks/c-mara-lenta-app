package com.farks.autopilot.tracking

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.models.EngineConfig
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneDetection
import kotlin.math.abs

/**
 * Descarta detecciones que son parte del propio vehículo del jugador (alerón, escape, sombra o llamas
 * que sobresalen de la caja calibrada) y que el detector ve como un "obstáculo" pegado a la línea de
 * colisión. Se reconocen por una propiedad física: tocan la zona del jugador y su borde superior NO se
 * mueve, mientras que un obstáculo real que llega a la zona sigue avanzando por la pantalla.
 */
class SelfVehicleFilter(private val profile: GameProfile, private val config: EngineConfig) {

    private class Seen(val sinceNs: Long, val topAnchor: Float, var lastNs: Long, var ignored: Boolean)

    private val seen = HashMap<Lane, Seen>()

    /** Número de detecciones descartadas desde el inicio (para diagnóstico). */
    var droppedCount = 0
        private set

    fun reset() {
        seen.clear()
    }

    /**
     * @param watchLanes carriles donde puede estar el vehículo (el actual y, si hay uno en curso, el destino)
     * @param onFirstIgnore se llama una vez por carril cuando se empieza a ignorar
     */
    fun filter(
        tNs: Long,
        dets: List<LaneDetection>,
        watchLanes: Set<Lane>,
        onFirstIgnore: (Lane) -> Unit = {},
    ): List<LaneDetection> {
        val zoneY = profile.collisionY - 0.010f
        val touching = HashSet<Lane>()
        val out = ArrayList<LaneDetection>(dets.size)
        for (d in dets) {
            if (d.lane !in watchLanes || d.box.bottom < zoneY) {
                out += d
                continue
            }
            touching += d.lane
            val s = seen[d.lane]
            if (s == null || abs(d.box.top - s.topAnchor) > TOP_TOLERANCE) {
                // Se mueve (o es nuevo): un obstáculo real. Se vuelve a medir desde aquí.
                seen[d.lane] = Seen(tNs, d.box.top, tNs, false)
                out += d
            } else {
                s.lastNs = tNs
                if (!s.ignored && tNs - s.sinceNs >= (config.selfStaticSec * 1e9).toLong()) {
                    s.ignored = true
                    onFirstIgnore(d.lane)
                }
                if (s.ignored) droppedCount++ else out += d
            }
        }
        // Olvidar carriles que dejaron de tocar la zona.
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in touching && tNs - e.value.lastNs > 1_000_000_000L) it.remove()
        }
        return out
    }

    companion object {
        /** Movimiento mínimo del borde superior para considerarlo un obstáculo real. */
        const val TOP_TOLERANCE = 0.010f
    }
}
