package com.farks.autopilot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.farks.autopilot.models.MoveDirection

/**
 * Emite deslizamientos laterales con dispatchGesture. Solo actúa si la aplicación en primer plano
 * es la elegida por el usuario ([allowedPackage]); en cualquier otro caso rechaza el gesto.
 * No lee el contenido de la pantalla (canRetrieveWindowContent = false).
 */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Teclados, ventanas del sistema y nuestra propia superposición no cuentan como cambio de app.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            foregroundPackage = pkg
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * @return false si el gesto no se envió (juego no en primer plano, servicio sin permiso...).
     * [onDone] se llama con true si el sistema completó el gesto y false si lo canceló.
     */
    fun swipe(
        direction: MoveDirection,
        screenW: Int,
        screenH: Int,
        yFraction: Float,
        distanceFraction: Float,
        durationMs: Int,
        onDone: (Boolean) -> Unit,
    ): Boolean {
        val allowed = allowedPackage ?: return false
        if (foregroundPackage != allowed) return false
        val cx = screenW / 2f
        val dx = screenW * distanceFraction * direction.delta
        val y = (screenH * yFraction).coerceIn(1f, screenH - 1f)
        val path = Path().apply {
            moveTo(cx, y)
            lineTo((cx + dx).coerceIn(1f, screenW - 1f), y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = onDone(true)
            override fun onCancelled(g: GestureDescription?) = onDone(false)
        }, null)
    }

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
            private set

        /** Único paquete donde se permiten gestos. null = ninguno. */
        @Volatile var allowedPackage: String? = null

        @Volatile var foregroundPackage: String? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
