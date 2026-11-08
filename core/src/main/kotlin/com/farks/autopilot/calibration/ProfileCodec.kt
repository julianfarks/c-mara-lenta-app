package com.farks.autopilot.calibration

import com.farks.autopilot.models.Lane
import com.farks.autopilot.player.PlayerLook
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import java.util.Locale

/** Perfil guardado: geometría + modelo de color de la carretera (80 valores) aprendido en calibración. */
data class StoredProfile(val profile: GameProfile, val colorModel: FloatArray) {
    override fun equals(other: Any?) =
        other is StoredProfile && other.profile == profile && other.colorModel.contentEquals(colorModel)

    override fun hashCode() = profile.hashCode() * 31 + colorModel.contentHashCode()
}

/**
 * Formato de texto clave=valor, sin dependencias (se prueba en la JVM). Los nombres de paquete y de
 * aplicación se codifican en Base16 para que cualquier carácter sea seguro.
 */
object ProfileCodec {
    private const val HEADER = "farks-profile-v1"
    const val MODEL_SIZE = 80

    fun encode(s: StoredProfile): String {
        val p = s.profile
        require(s.colorModel.size == MODEL_SIZE) { "El modelo de color debe tener $MODEL_SIZE valores" }
        val r = p.road
        return buildString {
            appendLine(HEADER)
            appendLine("package=${hex(p.packageName)}")
            appendLine("name=${hex(p.displayName)}")
            appendLine("road=${f(r.nearLeft.x)},${f(r.nearLeft.y)},${f(r.nearRight.x)},${f(r.nearRight.y)}," +
                "${f(r.farLeft.x)},${f(r.farLeft.y)},${f(r.farRight.x)},${f(r.farRight.y)}")
            appendLine("player=${f(p.playerBox.left)},${f(p.playerBox.top)},${f(p.playerBox.right)},${f(p.playerBox.bottom)}")
            appendLine("lane=${p.playerLane.index}")
            p.playerLook?.let { look -> appendLine("playerlook=" + look.toFloats().joinToString(",") { f(it) }) }
            appendLine("model=" + s.colorModel.joinToString(",") { f(it) })
        }
    }

    /** Devuelve null si el texto no es un perfil válido (nunca lanza). */
    fun decode(text: String): StoredProfile? = try {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != HEADER) null else {
            val kv = lines.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
            val road = kv.getValue("road").split(',').map { it.toFloat() }
            val pl = kv.getValue("player").split(',').map { it.toFloat() }
            val model = kv.getValue("model").split(',').map { it.toFloat() }.toFloatArray()
            if (road.size != 8 || pl.size != 4 || model.size != MODEL_SIZE) null else {
                val geo = RoadGeometry(
                    PointN(road[0], road[1]), PointN(road[2], road[3]),
                    PointN(road[4], road[5]), PointN(road[6], road[7]),
                )
                val lane = Lane.fromIndex(kv.getValue("lane").toInt())
                val rawLook = kv["playerlook"]
                val look = rawLook?.let { PlayerLook.fromFloats(it.split(',').map { v -> v.toFloat() }) }
                if (lane == null || (rawLook != null && look == null)) null else StoredProfile(
                    GameProfile(unhex(kv.getValue("package")), unhex(kv.getValue("name")), geo,
                        RectN(pl[0], pl[1], pl[2], pl[3]), lane, look),
                    model,
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun f(v: Float) = String.format(Locale.US, "%.6f", v)
    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        .toString(Charsets.UTF_8)
}
