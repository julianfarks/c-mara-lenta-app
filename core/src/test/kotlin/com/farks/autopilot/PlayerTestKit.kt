package com.farks.autopilot

import com.farks.autopilot.calibration.GameProfile
import com.farks.autopilot.calibration.RoadGeometry
import com.farks.autopilot.models.Lane
import com.farks.autopilot.models.PointN
import com.farks.autopilot.models.RectN
import com.farks.autopilot.player.WorkGrid
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Imagen RGB de 8 bits. */
class Img(val w: Int, val h: Int, val rgb: ByteArray) {
    fun copy() = Img(w, h, rgb.copyOf())
    fun px(x: Int, y: Int, c: Int) = rgb[(y * w + x) * 3 + c].toInt() and 0xFF
    fun set(x: Int, y: Int, r: Int, g: Int, b: Int) {
        val o = (y * w + x) * 3
        rgb[o] = r.toByte(); rgb[o + 1] = g.toByte(); rgb[o + 2] = b.toByte()
    }
}

class Sprite(val w: Int, val h: Int, val rgba: ByteArray) {
    /** Posición del sprite en la captura de referencia (px). */
    var x0 = 0; var y0 = 0
}

/**
 * Herramientas de prueba del vehículo del jugador. Réplica en JVM (sin OpenCV) del tratamiento del detector:
 * reducción por área -> Lab 8 bits (misma convención que OpenCV) -> modelo de carretera por franjas (mediana + MAD)
 * -> máscara de desviación. El mismo escenario se ejecuta con OpenCV real en el módulo :vision (PlayerLocalizationTest).
 */
object PlayerTestKit {
    // Calibración tal y como la marcó el usuario en su captura (coordenadas normalizadas de la imagen).
    val road = RoadGeometry(
        nearLeft = PointN(0.0088f, 0.9048f), nearRight = PointN(1.0f, 0.9048f),
        farLeft = PointN(0.261f, 0.4875f), farRight = PointN(0.7134f, 0.4905f),
    )
    val box = RectN(0.3503f, 0.6449f, 0.6526f, 0.7834f)
    fun profile(lane: Lane = Lane.CENTER, b: RectN = box) = GameProfile("test", "Test", road, b, lane)

    fun load(name: String): Img {
        val bi = ImageIO.read(PlayerTestKit::class.java.getResourceAsStream("/player/$name") ?: File("core/src/test/resources/player/$name").inputStream())
        val out = ByteArray(bi.width * bi.height * 3)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = bi.getRGB(x, y)
            val o = (y * bi.width + x) * 3
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte()
        }
        return Img(bi.width, bi.height, out)
    }

    fun loadSprite(): Sprite {
        val bi = ImageIO.read(PlayerTestKit::class.java.getResourceAsStream("/player/car_sprite.png") ?: File("core/src/test/resources/player/car_sprite.png").inputStream())
        val out = ByteArray(bi.width * bi.height * 4)
        for (y in 0 until bi.height) for (x in 0 until bi.width) {
            val p = bi.getRGB(x, y)
            val o = (y * bi.width + x) * 4
            out[o] = (p shr 16).toByte(); out[o + 1] = (p shr 8).toByte(); out[o + 2] = p.toByte(); out[o + 3] = (p ushr 24).toByte()
        }
        return Sprite(bi.width, bi.height, out).also { it.x0 = 182; it.y0 = 565 } // posición medida al extraerlo
    }

    /** Coloca el sprite (desplazado dx,dy px; escalado; opcionalmente recoloreado). */
    fun place(base: Img, sp: Sprite, dx: Int = 0, dy: Int = 0, scale: Float = 1f, recolor: ((Int, Int, Int) -> IntArray)? = null): Img {
        val out = base.copy()
        // El escalado se hace respecto al borde inferior-centro del sprite (el coche «crece» hacia arriba).
        val cx = sp.x0 + sp.w / 2f
        val by = (sp.y0 + sp.h).toFloat()
        val tw = (sp.w * scale).roundToInt(); val th = (sp.h * scale).roundToInt()
        val tx0 = (cx - tw / 2f).roundToInt() + dx
        val ty0 = (by - th).roundToInt() + dy
        for (y in 0 until th) for (x in 0 until tw) {
            val sx = (x / scale).toInt().coerceIn(0, sp.w - 1); val sy = (y / scale).toInt().coerceIn(0, sp.h - 1)
            val o = (sy * sp.w + sx) * 4
            val a = sp.rgba[o + 3].toInt() and 0xFF
            if (a < 128) continue
            val px = tx0 + x; val py = ty0 + y
            if (px < 0 || py < 0 || px >= out.w || py >= out.h) continue
            var r = sp.rgba[o].toInt() and 0xFF; var g = sp.rgba[o + 1].toInt() and 0xFF; var b = sp.rgba[o + 2].toInt() and 0xFF
            if (recolor != null) { val c = recolor(r, g, b); r = c[0]; g = c[1]; b = c[2] }
            out.set(px, py, r, g, b)
        }
        return out
    }

    /** Desplazamiento horizontal (px) entre los centros de dos carriles a la altura del borde inferior de la caja. */
    fun laneShiftPx(w: Int, from: Lane, to: Lane): Int =
        ((road.laneCenterX(to, box.bottom) - road.laneCenterX(from, box.bottom)) * w).roundToInt()

    fun noise(img: Img, sigma: Float, seed: Long): Img {
        val r = Random(seed)
        val o = img.copy()
        for (i in o.rgb.indices) o.rgb[i] = ((o.rgb[i].toInt() and 0xFF) + (r.nextGaussian() * sigma).roundToInt()).coerceIn(0, 255).toByte()
        return o
    }

    // ---------------------------------------------------------------- Lab (convención 8 bits de OpenCV)
    private fun lin(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun f(t: Double): Double = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
    fun lab8(r: Int, g: Int, b: Int, out: ByteArray, o: Int) {
        val rl = lin(r / 255.0); val gl = lin(g / 255.0); val bl = lin(b / 255.0)
        val x = (0.412453 * rl + 0.357580 * gl + 0.180423 * bl) / 0.950456
        val y = 0.212671 * rl + 0.715160 * gl + 0.072169 * bl
        val z = (0.019334 * rl + 0.119193 * gl + 0.950227 * bl) / 1.088754
        val fy = f(y)
        val l = if (y > 0.008856) 116.0 * fy - 16.0 else 903.3 * y
        val a = 500.0 * (f(x) - fy); val bb = 200.0 * (fy - f(z))
        out[o] = (l * 255.0 / 100.0).roundToInt().coerceIn(0, 255).toByte()
        out[o + 1] = (a + 128.0).roundToInt().coerceIn(0, 255).toByte()
        out[o + 2] = (bb + 128.0).roundToInt().coerceIn(0, 255).toByte()
    }

    /** Reduce por área el recorte [x0,x1)×[y0,y1) a dw×dh y lo pasa a Lab. */
    fun labOf(img: Img, x0: Int, y0: Int, x1: Int, y1: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh * 3)
        val sw = (x1 - x0).toFloat() / dw; val sh = (y1 - y0).toFloat() / dh
        for (j in 0 until dh) for (i in 0 until dw) {
            val sx0 = x0 + (i * sw).toInt(); val sx1 = max(sx0 + 1, (x0 + (i + 1) * sw).toInt())
            val sy0 = y0 + (j * sh).toInt(); val sy1 = max(sy0 + 1, (y0 + (j + 1) * sh).toInt())
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in sy0 until minOf(sy1, img.h)) for (x in sx0 until minOf(sx1, img.w)) {
                r += img.px(x, y, 0); g += img.px(x, y, 1); b += img.px(x, y, 2); n++
            }
            if (n == 0) n = 1
            lab8((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), out, (j * dw + i) * 3)
        }
        return out
    }

    // ---------------------------------------------------------------- modelo de carretera (réplica de RoadColorModel)
    class Band(val l: Float, val a: Float, val b: Float, val tolL: Float, val tolC: Float)
    private const val NB = 16

    fun learnRoad(ref: Img, road: RoadGeometry, exclude: List<RectN>): List<Band> {
        val lw = 270
        val lh = (ref.h * lw.toDouble() / ref.w).roundToInt()
        val lab = labOf(ref, 0, 0, ref.w, ref.h, lw, lh)
        val hist = Array(NB) { Array(3) { IntArray(256) } }
        val count = IntArray(NB)
        val y0 = road.farY; val y1 = road.nearY
        for (py in 0 until lh) {
            val yn = (py + 0.5f) / lh
            if (yn < y0 || yn > y1) continue
            val band = (((yn - y0) / (y1 - y0)) * NB).toInt().coerceIn(0, NB - 1)
            val lane = road.laneWidth(yn)
            val xMin = road.leftX(yn) + 0.10f * lane; val xMax = road.rightX(yn) - 0.10f * lane
            for (px in 0 until lw) {
                val xn = (px + 0.5f) / lw
                if (xn < xMin || xn > xMax) continue
                if (exclude.any { xn >= it.left && xn <= it.right && yn >= it.top && yn <= it.bottom }) continue
                val o = (py * lw + px) * 3
                for (c in 0 until 3) hist[band][c][lab[o + c].toInt() and 0xFF]++
                count[band]++
            }
        }
        fun med(h: IntArray, tot: Int): Int { var acc = 0; for (v in h.indices) { acc += h[v]; if (acc >= (tot + 1) / 2) return v }; return 255 }
        val stats = ArrayList<Band>()
        for (b in 0 until NB) {
            if (count[b] < 50) { stats += stats.lastOrNull() ?: Band(110f, 128f, 128f, 24f, 14f); continue }
            val m = FloatArray(3); val mad = FloatArray(3)
            for (c in 0 until 3) {
                val md = med(hist[b][c], count[b]); m[c] = md.toFloat()
                val dev = IntArray(256)
                for (v in 0 until 256) dev[abs(v - md)] += hist[b][c][v]
                mad[c] = med(dev, count[b]) * 1.4826f
            }
            stats += Band(m[0], m[1], m[2], max(24f, 4f * mad[0]), max(14f, 4f * hypot(mad[1], mad[2])))
        }
        return stats
    }

    /** Construye la rejilla de trabajo igual que OpenCvObstacleDetector.prepare. */
    fun grid(img: Img, prof: GameProfile, bands: List<Band>): WorkGrid {
        val r = prof.road
        val cropTop = r.farY + 0.002f
        val bottom = prof.playerZoneBottom
        val x0n = (r.leftX(bottom) - 0.02f).coerceAtLeast(0f)
        val x1n = (r.rightX(bottom) + 0.02f).coerceAtMost(1f)
        val farFrac = (r.laneWidth(cropTop) / (x1n - x0n)).coerceAtLeast(0.02f)
        val ww = (((18 / farFrac).toInt().coerceIn(160, 320) + 7) / 8) * 8
        val fx0 = (x0n * img.w).toInt(); val fx1 = (x1n * img.w).toInt()
        val fy0 = (cropTop * img.h).toInt(); val fy1 = (bottom * img.h).toInt()
        val wh = max(16, ((fy1 - fy0).toDouble() * ww / (fx1 - fx0)).roundToInt())
        val data = labOf(img, fx0, fy0, fx1, fy1, ww, wh)
        val ph = wh / (bottom - cropTop)
        val centers = FloatArray(NB) { r.farY + (it + 0.5f) / NB * (r.nearY - r.farY) }
        val mask = ByteArray(ww * wh)
        for (row in 0 until wh) {
            val yn = cropTop + (row + 0.5f) / ph
            val e = interp(bands, centers, yn)
            for (c in 0 until ww) {
                val o = (row * ww + c) * 3
                val dl = abs((data[o].toInt() and 0xFF) - e[0])
                val da = (data[o + 1].toInt() and 0xFF) - e[1]; val db = (data[o + 2].toInt() and 0xFF) - e[2]
                if (dl > e[3] || da * da + db * db > e[4] * e[4]) mask[row * ww + c] = -1
            }
        }
        return WorkGrid(data, mask, ww, wh, x0n, x1n, cropTop, bottom)
    }

    private fun interp(b: List<Band>, c: FloatArray, y: Float): FloatArray {
        fun arr(s: Band) = floatArrayOf(s.l, s.a, s.b, s.tolL, s.tolC)
        if (y <= c[0]) return arr(b[0]); if (y >= c[c.size - 1]) return arr(b[b.size - 1])
        var i = 0
        while (i < c.size - 2 && y > c[i + 1]) i++
        val t = (y - c[i]) / (c[i + 1] - c[i]); val a = arr(b[i]); val z = arr(b[i + 1])
        return FloatArray(5) { a[it] + (z[it] - a[it]) * t }
    }
}
