package com.farks.autopilot.player

import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.LaneScore
import com.farks.autopilot.models.PlayerDiag
import com.farks.autopilot.models.PlayerObservation
import com.farks.autopilot.models.RectN
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Imagen de trabajo del detector: Lab de 8 bits (L, a, b intercalados) y máscara de "primer plano"
 * (píxeles que se desvían del modelo de la carretera vacía, != 0). Cubre x0n..x1n, topN..bottomN
 * (coordenadas normalizadas de la captura). El aprendizaje y la localización usan LA MISMA rejilla,
 * por lo que no puede haber desajuste de coordenadas entre calibración y ejecución.
 */
class WorkGrid(
    val lab: ByteArray, val fg: ByteArray, val ww: Int, val wh: Int,
    val x0n: Float, val x1n: Float, val topN: Float, val bottomN: Float,
) {
    fun col(xn: Float): Int = ((xn - x0n) / (x1n - x0n) * ww).roundToInt()
    fun row(yn: Float): Int = ((yn - topN) / (bottomN - topN) * wh).roundToInt()
    fun xOf(c: Float): Float = x0n + c / ww * (x1n - x0n)
    fun yOf(r: Float): Float = topN + r / wh * (bottomN - topN)
}

/**
 * Apariencia del vehículo del jugador, aprendida de los píxeles que NO son asfalto dentro de su zona.
 * No se usa «el color medio de la caja» (en un coche multicolor o con parabrisas oscuro acaba siendo
 * el color del asfalto y no distingue nada): se guardan una paleta de colores dominantes del coche,
 * cuánta área ocupa, su anchura/altura y dónde está su centro respecto al carril.
 */
class PlayerLook(
    /** Tripletas L,a,b (escala 8 bits de OpenCV) de los colores dominantes del vehículo. */
    val palette: FloatArray,
    /** Fracción de la ventana del carril que ocupa el vehículo (píxeles de primer plano). */
    val areaFrac: Float,
    /** Anchura y altura del vehículo respecto a la caja marcada (puede ser >1 si la caja se quedó corta). */
    val widthRel: Float,
    val heightRel: Float,
    /** Desplazamiento horizontal del centro del vehículo respecto al centro del carril, en anchos de caja. */
    val offX: Float,
) {
    fun toFloats(): List<Float> = listOf(areaFrac, widthRel, heightRel, offX, (palette.size / 3).toFloat()) + palette.toList()

    companion object {
        fun fromFloats(v: List<Float>): PlayerLook? {
            if (v.size < 5) return null
            val n = v[4].toInt()
            if (n < 1 || n > 16 || v.size != 5 + 3 * n) return null
            return PlayerLook(FloatArray(3 * n) { v[5 + it] }, v[0], v[1], v[2], v[3])
        }
    }
}

data class PlayerLearnResult(
    val look: PlayerLook?,
    /** Texto para el usuario: por qué se aprendió o por qué no. */
    val message: String,
    /** Cuánto se sale el vehículo real de la caja marcada (fracción de su altura/anchura); positivo = falta caja. */
    val boxMissingBottom: Float = 0f,
    val boxMissingSide: Float = 0f,
    val fgBox: RectN? = null,
)

internal object Geometry {
    const val WIN_HALF = 0.75f

    fun window(road: RoadGeometry, box: RectN, zoneBottom: Float, lane: Lane): RectN {
        val cx = road.laneCenterX(lane, box.bottom)
        return RectN(cx - WIN_HALF * box.width, box.top, cx + WIN_HALF * box.width, zoneBottom)
    }
}

/** Medidas del primer plano dentro de la ventana de un carril. */
private class Measure(
    val n: Int, val winPx: Int,
    val xl: Float, val xr: Float, val yt: Float, val yb: Float, val cx: Float,
    val lab: FloatArray?, // medias para aprendizaje no necesarias aquí
    val palettePx: IntArray, // índices (c, r) de píxeles de primer plano para paleta
)

private fun measure(g: WorkGrid, win: RectN): Measure {
    val c0 = g.col(win.left).coerceIn(0, g.ww - 1)
    val c1 = g.col(win.right).coerceIn(c0 + 1, g.ww)
    val r0 = g.row(win.top).coerceIn(0, g.wh - 1)
    val r1 = g.row(win.bottom).coerceIn(r0 + 1, g.wh)
    val colH = IntArray(c1 - c0)
    val rowH = IntArray(r1 - r0)
    var n = 0
    var sumC = 0L
    val px = ArrayList<Int>()
    for (r in r0 until r1) for (c in c0 until c1) {
        if (g.fg[r * g.ww + c].toInt() != 0) {
            n++; colH[c - c0]++; rowH[r - r0]++; sumC += c
            px += r * g.ww + c
        }
    }
    val winPx = (c1 - c0) * (r1 - r0)
    if (n == 0) return Measure(0, winPx, 0f, 0f, 0f, 0f, 0f, null, IntArray(0))
    fun pct(h: IntArray, q: Float, from: Boolean): Int {
        val target = max(1, (n * q).toInt())
        var acc = 0
        if (from) { for (i in h.indices) { acc += h[i]; if (acc >= target) return i } }
        else { for (i in h.indices.reversed()) { acc += h[i]; if (acc >= target) return i } }
        return if (from) 0 else h.size - 1
    }
    // Extremos robustos (2 % por cada lado): un parabrisas oscuro o un reflejo no deforman la caja.
    val xl = g.xOf((c0 + pct(colH, 0.02f, true)).toFloat())
    val xr = g.xOf((c0 + pct(colH, 0.02f, false) + 1).toFloat())
    val yt = g.yOf((r0 + pct(rowH, 0.02f, true)).toFloat())
    val yb = g.yOf((r0 + pct(rowH, 0.02f, false) + 1).toFloat())
    val cx = g.xOf(sumC.toFloat() / n + 0.5f)
    return Measure(n, winPx, xl, xr, yt, yb, cx, null, px.toIntArray())
}

object PlayerLookLearner {
    /**
     * Aprende la apariencia del vehículo en el carril [lane] (el calibrado). El primer plano debe haberse
     * calculado con un modelo de carretera que EXCLUYE la zona del vehículo.
     */
    fun learn(g: WorkGrid, road: RoadGeometry, box: RectN, zoneBottom: Float, lane: Lane): PlayerLearnResult {
        val win = Geometry.window(road, box, zoneBottom, lane)
        val m = measure(g, win)
        val frac = if (m.winPx > 0) m.n.toFloat() / m.winPx else 0f
        if (frac < 0.03f || m.n < 25) {
            return PlayerLearnResult(null,
                "No se distingue su vehículo del asfalto (solo %.1f%% de píxeles distintos en su zona): ".format(frac * 100) +
                    "compruebe que la caja (toques 5 y 6) está sobre el automóvil y que la captura no tiene el coche del mismo color que la carretera.")
        }
        // Paleta: colores cuantizados dominantes del primer plano.
        val bins = HashMap<Int, IntArray>() // clave -> [n, sumL, sumA, sumB]
        for (idx in m.palettePx) {
            val o = idx * 3
            val l = g.lab[o].toInt() and 0xFF; val a = g.lab[o + 1].toInt() and 0xFF; val b = g.lab[o + 2].toInt() and 0xFF
            val key = (l / 16) * 10000 + (a / 12) * 100 + (b / 12)
            val e = bins.getOrPut(key) { IntArray(4) }
            e[0]++; e[1] += l; e[2] += a; e[3] += b
        }
        val top = bins.values.filter { it[0] >= 0.03f * m.n }.sortedByDescending { it[0] }.take(8)
        if (top.isEmpty()) return PlayerLearnResult(null, "No se pudo extraer una paleta estable del vehículo.")
        val pal = FloatArray(top.size * 3)
        top.forEachIndexed { i, e ->
            pal[i * 3] = e[1].toFloat() / e[0]; pal[i * 3 + 1] = e[2].toFloat() / e[0]; pal[i * 3 + 2] = e[3].toFloat() / e[0]
        }
        val cxLane = road.laneCenterX(lane, box.bottom)
        val look = PlayerLook(
            palette = pal, areaFrac = frac,
            widthRel = (m.xr - m.xl) / box.width, heightRel = (m.yb - m.yt) / box.height,
            offX = (m.cx - cxLane) / box.width,
        )
        val fgBox = RectN(m.xl, m.yt, m.xr, m.yb)
        val missBottom = ((m.yb - box.bottom) / box.height).coerceAtLeast(0f)
        val missSide = max(((box.left - m.xl) / box.width), ((m.xr - box.right) / box.width)).coerceAtLeast(0f)
        var msg = "Vehículo aprendido: %d colores dominantes, ocupa %.0f%% de su zona.".format(top.size, frac * 100)
        if (missBottom > 0.08f || missSide > 0.08f) {
            msg += " ⚠ La caja marcada no cubre todo el vehículo (le faltan %.0f%% por abajo y %.0f%% por los lados); se usa la zona ampliada.".format(
                missBottom * 100, missSide * 100)
        }
        return PlayerLearnResult(look, msg, missBottom, missSide, fgBox)
    }
}

/**
 * Localiza el carril del vehículo del jugador. Para cada carril mide, sobre los píxeles que no son asfalto
 * dentro de su ventana, CUATRO cosas respecto a lo aprendido: área, forma (ancho y alto), paleta de colores
 * y posición del centro. Un coche de otro color o de otro tamaño en un carril vecino no coincide en
 * paleta/forma; un coche parcial (cambio de carril en curso) no coincide en área/posición.
 * Además aplica continuidad temporal: no abandona el carril anterior por una diferencia mínima.
 *
 * No es seguro entre hilos (mantiene el carril anterior).
 */
class PlayerLocator(
    private val road: RoadGeometry,
    private val box: RectN,
    private val zoneBottom: Float,
    private val look: PlayerLook,
    private val minScore: Float = 0.50f,
) {
    private companion object { const val MIN_PALETTE = 0.45f }

    private var lastLane: Lane? = null
    private var framesSinceSeen = 1000

    fun reset() { lastLane = null; framesSinceSeen = 1000 }

    private fun sim(q: Float, sigma: Float): Float {
        if (q <= 0f || q.isNaN() || q.isInfinite()) return 0f
        val l = ln(q)
        return exp(-0.5f * (l / sigma) * (l / sigma))
    }

    private fun paletteScore(g: WorkGrid, m: Measure): Float {
        if (m.n == 0) return 0f
        var ok = 0
        val p = look.palette
        for (idx in m.palettePx) {
            val o = idx * 3
            val l = g.lab[o].toInt() and 0xFF; val a = g.lab[o + 1].toInt() and 0xFF; val b = g.lab[o + 2].toInt() and 0xFF
            var i = 0
            while (i < p.size) {
                val da = a - p[i + 1]; val db = b - p[i + 2]
                // Tolerante a sombras, brillos y compresión: ±36 de luminosidad y 30 de crominancia (escala 8 bits).
                if (abs(l - p[i]) <= 36f && da * da + db * db <= 900f) { ok++; break }
                i += 3
            }
        }
        return ok.toFloat() / m.n
    }

    fun locate(g: WorkGrid): PlayerObservation {
        val scores = ArrayList<LaneScore>(3)
        for (lane in Lane.entries) {
            val win = Geometry.window(road, box, zoneBottom, lane)
            val m = measure(g, win)
            val frac = if (m.winPx > 0) m.n.toFloat() / m.winPx else 0f
            if (m.n < 12 || frac < 0.2f * look.areaFrac) {
                scores += LaneScore(lane, win, null, m.n, 0f, 0f, 0f, 0f, 0f)
                continue
            }
            val area = sim(frac / look.areaFrac, 0.5f)
            val wRel = (m.xr - m.xl) / box.width
            val hRel = (m.yb - m.yt) / box.height
            val shape = (sim(wRel / look.widthRel, 0.35f) + sim(hRel / look.heightRel, 0.35f)) / 2f
            val dx = (m.cx - road.laneCenterX(lane, box.bottom)) / box.width - look.offX
            val pos = exp(-0.5f * (dx / 0.2f) * (dx / 0.2f))
            val pal = paletteScore(g, m)
            // Media geométrica ponderada: basta que una de las pruebas falle claramente para rechazar.
            val total = (area.pow(0.2f) * shape.pow(0.2f) * pal.pow(0.5f) * pos.pow(0.2f)).let { if (it.isNaN()) 0f else it }
            scores += LaneScore(lane, win, RectN(m.xl, m.yt, m.xr, m.yb), m.n, area, shape, pal, pos, total)
        }
        val ranked = scores.sortedByDescending { it.total }
        val best = ranked[0]
        val second = ranked[1]
        var accepted: Lane? = null
        var continuity = false
        val last = lastLane
        val lastScore = last?.let { l -> scores.first { it.lane == l } }
        // El color debe coincidir al menos en parte: un coche de otro color y mismo tamaño en un carril no es el jugador.
        if (best.total >= minScore && best.palette >= MIN_PALETTE && (best.total - second.total >= 0.15f || second.total <= 0.6f * best.total)) {
            accepted = best.lane
        } else if (last != null && lastScore != null && framesSinceSeen < 20 && lastScore.total >= 0.45f &&
            lastScore.total >= best.total - 0.12f
        ) {
            accepted = last; continuity = true
        }
        // Histéresis: si ya estaba en un carril y el nuevo gana por poco, se mantiene el anterior.
        if (accepted != null && last != null && accepted != last && lastScore != null && framesSinceSeen < 20 &&
            lastScore.total >= 0.45f && best.total - lastScore.total < 0.10f
        ) { accepted = last; continuity = true }

        if (accepted != null) { lastLane = accepted; framesSinceSeen = 0 } else framesSinceSeen++
        val reason = explain(accepted, continuity, ranked)
        val conf = accepted?.let { l -> scores.first { it.lane == l }.total } ?: 0f
        return PlayerObservation(accepted, conf, PlayerDiag(scores, accepted, reason, continuity))
    }

    private fun explain(accepted: Lane?, continuity: Boolean, ranked: List<LaneScore>): String {
        val b = ranked[0]
        if (accepted != null) {
            return if (continuity) "Carril $accepted por continuidad temporal (puntuación del mejor %.2f).".format(b.total)
            else "Carril $accepted aceptado (puntuación %.2f: área %.2f, forma %.2f, color %.2f, posición %.2f).".format(
                b.total, b.area, b.shape, b.palette, b.position)
        }
        if (ranked.all { it.area < 0.05f }) return "Rechazado: no hay píxeles distintos del asfalto en la zona del jugador (¿captura sin vehículo o caja mal colocada?)."
        val comps = listOf("área" to b.area, "forma" to b.shape, "color" to b.palette, "posición" to b.position)
        val worst = comps.minBy { it.second }
        val why = when (worst.first) {
            "área" -> "el tamaño de lo detectado no coincide con el vehículo aprendido"
            "forma" -> "ancho/alto distintos al vehículo aprendido"
            "color" -> "los colores no coinciden con la paleta del vehículo"
            else -> "el centro está desplazado respecto al carril (¿cambio de carril en curso?)"
        }
        val second = ranked[1]
        if (b.total >= minScore && b.palette < MIN_PALETTE)
            return "Rechazado: %s tiene forma y tamaño del vehículo pero solo %.0f%% de su color coincide (mínimo %.0f%%): parece otro coche.".format(
                b.lane, b.palette * 100, MIN_PALETTE * 100)
        return if (b.total >= minScore) "Rechazado por ambigüedad: ${b.lane} %.2f y ${second.lane} %.2f son demasiado parecidos.".format(b.total, second.total)
        else "Rechazado: mejor candidato ${b.lane} con %.2f (<%.2f); falla %s (%.2f): %s.".format(b.total, minScore, worst.first, worst.second, why)
    }
}
