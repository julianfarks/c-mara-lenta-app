package com.farks.autopilot.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.farks.autopilot.models.PlayerDiag
import java.util.Locale

/**
 * Dibuja el diagnóstico de localización del jugador: por cada carril, su ventana de búsqueda (verde = aceptado,
 * rojo = rechazado), la caja de lo que no es asfalto y las cuatro puntuaciones (A=área, F=forma, C=color, P=posición).
 * Así se ve QUÉ condición provoca el rechazo.
 */
object PlayerDiagDraw {
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.MAGENTA }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }

    fun draw(c: Canvas, d: PlayerDiag, sw: Float, sh: Float) {
        for (l in d.lanes) {
            val ok = d.accepted == l.lane
            box.color = if (ok) Color.rgb(43, 217, 124) else Color.rgb(229, 72, 77)
            c.drawRect(l.window.left * sw, l.window.top * sh, l.window.right * sw, l.window.bottom * sh, box)
            l.fgBox?.let { c.drawRect(it.left * sw, it.top * sh, it.right * sw, it.bottom * sh, fg) }
            val t = String.format(Locale.US, "%s %.2f", l.lane.name.take(1), l.total)
            val t2 = String.format(Locale.US, "A%.1f F%.1f C%.1f P%.1f", l.area, l.shape, l.palette, l.position)
            c.drawText(t, l.window.left * sw + 4f, l.window.bottom * sh - 30f, txt)
            c.drawText(t2, l.window.left * sw + 4f, l.window.bottom * sh - 6f, txt)
        }
    }
}
