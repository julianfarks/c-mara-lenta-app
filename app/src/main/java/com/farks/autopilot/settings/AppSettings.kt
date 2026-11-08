package com.farks.autopilot.settings

import android.content.Context
import com.farks.autopilot.models.EngineConfig

/** Ajustes del usuario (SharedPreferences). Todo es local; la app no usa red. */
class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("farks_settings", Context.MODE_PRIVATE)

    var selectedPackage: String?
        get() = sp.getString("pkg", null)
        set(v) = sp.edit().putString("pkg", v).apply()

    /** Distancia del deslizamiento como fracción del ancho de pantalla. */
    var swipeFraction: Float
        get() = sp.getFloat("swipeFraction", 0.30f)
        set(v) = sp.edit().putFloat("swipeFraction", v.coerceIn(0.05f, 0.9f)).apply()

    var swipeDurationMs: Int
        get() = sp.getInt("swipeDurationMs", 90)
        set(v) = sp.edit().putInt("swipeDurationMs", v.coerceIn(30, 400)).apply()

    var minMoveIntervalMs: Int
        get() = sp.getInt("minMoveIntervalMs", 350)
        set(v) = sp.edit().putInt("minMoveIntervalMs", v.coerceIn(150, 2000)).apply()

    var safetyMarginSec: Float
        get() = sp.getFloat("safetyMarginSec", 0.18f)
        set(v) = sp.edit().putFloat("safetyMarginSec", v.coerceIn(0.05f, 0.6f)).apply()

    var laneChangeSec: Float
        get() = sp.getFloat("laneChangeSec", 0.20f)
        set(v) = sp.edit().putFloat("laneChangeSec", v.coerceIn(0.05f, 0.8f)).apply()

    var maxAnalysisFps: Int
        get() = sp.getInt("maxFps", 30)
        set(v) = sp.edit().putInt("maxFps", v.coerceIn(15, 30)).apply()

    var showOverlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    fun engineConfig() = EngineConfig(
        minMoveIntervalMs = minMoveIntervalMs.toLong(),
        safetyMarginSec = safetyMarginSec,
        laneChangeSec = laneChangeSec,
    )
}
