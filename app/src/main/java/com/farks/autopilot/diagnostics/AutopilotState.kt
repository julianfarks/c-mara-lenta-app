package com.farks.autopilot.diagnostics

import android.graphics.Bitmap
import com.farks.autopilot.models.Detection
import com.farks.autopilot.models.EngineOutput
import com.farks.autopilot.vision.DetectorResult

/** Estado público que el servicio publica y que la interfaz, la superposición y el diagnóstico leen. */
data class Snapshot(
    val running: Boolean = false,
    val packageName: String? = null,
    val output: EngineOutput? = null,
    val rawDetections: List<Detection> = emptyList(),
    val detector: DetectorResult? = null,
    val analysisFps: Float = 0f,
    val processingMs: Float = 0f,
    val captureFps: Float = 0f,
    val gameInForeground: Boolean = false,
    val lastError: String? = null,
    val gesturesSent: Int = 0,
    val gesturesFailed: Int = 0,
    val framesAnalyzed: Long = 0,
)

object AutopilotState {
    @Volatile var snapshot: Snapshot = Snapshot()
    @Volatile var lastFrame: Bitmap? = null

    /** El diagnóstico lo pone a true mientras está visible para que el servicio genere fotogramas. */
    @Volatile var wantFrames: Boolean = false
    val log = DecisionLog(500)

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) { listeners += l }
    fun removeListener(l: (Snapshot) -> Unit) { listeners -= l }

    fun publish(s: Snapshot) {
        snapshot = s
        for (l in listeners) l(s)
    }
}
