package com.farks.autopilot.ui

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.farks.autopilot.settings.AppSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var s: AppSettings
    private val saves = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = AppSettings(this)
        val col = Ui.column(this)
        col.addView(Ui.title(this, "Ajustes"))
        col.addView(Ui.text(this, "Los valores se aplican la próxima vez que pulse INICIAR.", 13f, Ui.MUTED))

        fun number(label: String, hint: String, value: String, decimal: Boolean, apply: (String) -> Unit) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, label, 14f))
            card.addView(Ui.text(this, hint, 12f, Ui.MUTED))
            val et = EditText(this).apply {
                setText(value); setTextColor(Ui.TEXT)
                inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            }
            card.addView(et)
            col.addView(card)
            saves += { apply(et.text.toString().replace(',', '.')) }
        }

        number("Distancia del deslizamiento", "Fracción del ancho de pantalla (0.05–0.9). Ajuste hasta que el juego cambie de carril una vez por gesto.",
            s.swipeFraction.toString(), true) { it.toFloatOrNull()?.let { v -> s.swipeFraction = v } }
        number("Duración del deslizamiento (ms)", "30–400. Más corto = más rápido, pero algunos juegos lo ignoran.",
            s.swipeDurationMs.toString(), false) { it.toIntOrNull()?.let { v -> s.swipeDurationMs = v } }
        number("Tiempo mínimo entre movimientos (ms)", "150–2000. Evita movimientos en ráfaga.",
            s.minMoveIntervalMs.toString(), false) { it.toIntOrNull()?.let { v -> s.minMoveIntervalMs = v } }
        number("Duración del cambio de carril (s)", "Tiempo que tarda su vehículo en cambiar de carril (0.05–0.8). Medirlo en Diagnóstico.",
            s.laneChangeSec.toString(), true) { it.toFloatOrNull()?.let { v -> s.laneChangeSec = v } }
        number("Margen de seguridad (s)", "0.05–0.6. Mayor = más prudente, más movimientos.",
            s.safetyMarginSec.toString(), true) { it.toFloatOrNull()?.let { v -> s.safetyMarginSec = v } }
        number("Análisis máximos por segundo", "15–30. El sistema baja solo hasta 15 si el teléfono no da más.",
            s.maxAnalysisFps.toString(), false) { it.toIntOrNull()?.let { v -> s.maxAnalysisFps = v } }

        val overlay = CheckBox(this).apply {
            text = "Mostrar panel flotante (incluye el botón de parada)"; setTextColor(Ui.TEXT); isChecked = s.showOverlay
        }
        col.addView(overlay)
        saves += { s.showOverlay = overlay.isChecked }

        val scale = CheckBox(this).apply {
            text = "Distancia por tamaño del coche (recomendado en carreteras curvas; desactivar si el juego tiene perspectiva recta)"
            setTextColor(Ui.TEXT); isChecked = s.scaleDepth
        }
        col.addView(scale)
        saves += { s.scaleDepth = scale.isChecked }

        col.addView(Ui.button(this, "Guardar", Ui.GREEN) {
            saves.forEach { it() }
            finish()
        })
        setContentView(Ui.scroll(this, col as LinearLayout))
    }
}
