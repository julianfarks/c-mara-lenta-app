package com.farks.autopilot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.farks.autopilot.automation.GestureAccessibilityService
import com.farks.autopilot.calibration.ProfileStore
import com.farks.autopilot.capture.ScreenCapturer
import com.farks.autopilot.detection.LiteRtClassifier
import com.farks.autopilot.diagnostics.AutopilotState
import com.farks.autopilot.diagnostics.Snapshot
import com.farks.autopilot.engine.AutopilotEngine
import com.farks.autopilot.models.EngineStatus
import com.farks.autopilot.models.FrameObservation
import com.farks.autopilot.models.LogLevel
import com.farks.autopilot.models.MoveCommand
import com.farks.autopilot.overlay.OverlayController
import com.farks.autopilot.settings.AppSettings
import com.farks.autopilot.ui.CalibrationActivity
import com.farks.autopilot.ui.MainActivity
import com.farks.autopilot.vision.OpenCvObstacleDetector
import com.farks.autopilot.vision.RoadColorModel
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils

/**
 * Servicio en primer plano (tipo mediaProjection). Dos modos:
 *  - AUTOPILOT: captura -> detección -> motor -> gestos, solo dentro del juego elegido.
 *  - CALIBRATION: muestra un botón flotante y guarda una captura de referencia.
 */
class AutopilotService : Service() {

    private lateinit var settings: AppSettings
    private lateinit var store: ProfileStore
    private lateinit var overlay: OverlayController
    private val main = Handler(Looper.getMainLooper())
    private var analysisThread: HandlerThread? = null
    private var analysis: Handler? = null

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var engine: AutopilotEngine? = null
    private var detector: OpenCvObstacleDetector? = null
    private var classifier: LiteRtClassifier? = null
    private var pkg: String = ""
    private var mode: String = MODE_AUTOPILOT

    @Volatile private var active = false
    private var pausedNotForeground = false
    private var avgMs = 20f
    private var windowStartNs = 0L
    private var windowFrames = 0
    private var fps = 0f
    private var frames = 0L
    private var sent = 0
    private var failed = 0
    private var lastBitmapNs = 0L
    private var overlayWarned = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        store = ProfileStore(this)
        overlay = OverlayController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> shutdown(null)
            ACTION_EMERGENCY -> analysis?.post { engine?.emergencyStop("Parada de emergencia (usuario)") }
            ACTION_REARM -> analysis?.post { engine?.resetEmergency() }
            else -> shutdown(null)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ arranque

    private fun start(intent: Intent) {
        if (active) return
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_AUTOPILOT
        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { shutdown("Falta el juego seleccionado"); return }

        // El servicio DEBE estar en primer plano antes de obtener la proyección.
        startForegroundCompat()

        if (!OpenCVLoader.initLocal()) { shutdown("No se pudo cargar OpenCV"); return }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { shutdown("No se concedió el permiso de captura de pantalla"); return }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try { mpm.getMediaProjection(resultCode, data) } catch (e: Exception) {
            shutdown("No se pudo iniciar la captura: ${e.message}"); return
        }
        projection = proj

        analysisThread = HandlerThread("farks-analysis").also { it.start() }
        analysis = Handler(analysisThread!!.looper)

        try {
            capturer = ScreenCapturer(this, proj, 540) {
                analysis?.post { onCaptureStopped() }
            }
        } catch (e: Exception) {
            shutdown("No se pudo crear la pantalla virtual: ${e.message}"); return
        }
        active = true

        if (mode == MODE_CALIBRATION) {
            overlay.showCalibrationButton { analysis?.post { captureReference() } }
            return
        }

        val stored = store.load(pkg)
        if (stored == null) { shutdown("El juego no está calibrado. Abra «Calibrar» primero."); return }
        val model = RoadColorModel.fromFloatArray(stored.colorModel)
        classifier = LiteRtClassifier.load(this)
        detector = try {
            OpenCvObstacleDetector(stored.profile, model, classifier)
        } catch (e: IllegalArgumentException) {
            shutdown("Calibración inválida: ${e.message}"); return
        }
        engine = AutopilotEngine(stored.profile, settings.engineConfig())
        GestureAccessibilityService.allowedPackage = pkg

        AutopilotState.log.add(System.currentTimeMillis(), LogLevel.INFO,
            "Piloto iniciado en $pkg (clasificador LiteRT: ${if (classifier != null) "sí" else "no, solo color/forma"})")
        if (!GestureAccessibilityService.isConnected) {
            AutopilotState.log.add(System.currentTimeMillis(), LogLevel.WARN,
                "El servicio de accesibilidad no está activo: se analiza pero NO se enviarán gestos.")
        }
        if (settings.showOverlay) {
            overlay.showAutopilot(
                onEmergency = { analysis?.post { engine?.emergencyStop("Parada de emergencia (botón)") } },
                onRearm = { analysis?.post { engine?.resetEmergency() } },
            )
        }
        windowStartNs = System.nanoTime()
        // Análisis dirigido por eventos: cada fotograma nuevo dispara el análisis (sin esperar a un temporizador),
        // limitado a maxAnalysisFps. Un vigilante comprueba fotogramas caducados si la captura se queda sin imágenes.
        capturer?.onFrameAvailable = { requestAnalysis() }
        analysis?.postDelayed({ watchdog() }, 100)
    }

    private val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastStartNs = 0L

    private fun requestAnalysis() {
        if (!active || !scheduled.compareAndSet(false, true)) return
        val h = analysis ?: run { scheduled.set(false); return }
        val minGapNs = (1_000_000_000L / settings.maxAnalysisFps.coerceIn(5, 30))
        val wait = (lastStartNs + minGapNs - System.nanoTime()) / 1_000_000L
        h.postDelayed({ tick() }, wait.coerceAtLeast(0))
    }

    private fun watchdog() {
        if (!active) return
        val eng = engine
        val h = analysis ?: return
        if (eng != null && capturer?.let { System.nanoTime() - lastStartNs > 250_000_000L } == true) {
            eng.checkStale(System.nanoTime())?.let { publish(it, null, null) }
            requestAnalysis() // si hay un fotograma pendiente, procesarlo
        }
        h.postDelayed({ watchdog() }, 100)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FarKs AutoPilot", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutopilotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("FarKs AutoPilot")
            .setContentText(if (mode == MODE_CALIBRATION) "Calibración: captura de referencia" else "Analizando la pantalla del juego")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ------------------------------------------------------------------ calibración

    private fun captureReference() {
        val cap = capturer ?: return
        var frame = cap.takeLatest()
        var tries = 0
        while (frame == null && tries < 30) { Thread.sleep(50); frame = cap.takeLatest(); tries++ }
        if (frame == null) {
            AutopilotState.log.add(System.currentTimeMillis(), LogLevel.ERROR, "No llegó ninguna imagen para la referencia.")
            shutdown("No se recibió imagen de la pantalla. Inténtelo de nuevo.")
            return
        }
        try {
            val bmp = Bitmap.createBitmap(frame.mat.cols(), frame.mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame.mat, bmp)
            store.referenceFile(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } finally {
            frame.release()
        }
        main.post {
            overlay.remove()
            // Permitido desde segundo plano mientras la app tiene el permiso de superposición.
            startActivity(
                Intent(this, CalibrationActivity::class.java)
                    .putExtra(CalibrationActivity.EXTRA_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            shutdown(null)
        }
    }

    // ------------------------------------------------------------------ bucle de análisis

    private fun tick() {
        scheduled.set(false)
        if (!active) return
        val eng = engine ?: return
        val det = detector ?: return
        val cap = capturer ?: return

        val frame = cap.takeLatest() ?: return
        val t0 = System.nanoTime()
        lastStartNs = t0
        try {
            val fg = GestureAccessibilityService.foregroundPackage == pkg
            if (!fg) {
                if (!pausedNotForeground) {
                    pausedNotForeground = true
                    publish(eng.onCaptureLost(frame.timestampNs, "El juego no está en primer plano"), null, null)
                }
                return
            }
            if (pausedNotForeground) { pausedNotForeground = false; eng.onCaptureResumed() }

            eng.armed = GestureAccessibilityService.isConnected
            val res = det.detect(frame.mat)
            // processedAtNs: el motor descuenta la edad real de la imagen (captura -> fin de detección) al decidir.
            val out = eng.onFrame(FrameObservation(frame.timestampNs, res.detections, res.player, System.nanoTime()))

            out.command?.let { execute(it, eng, cap) }
            publish(out, res, frame)
        } catch (e: Exception) {
            AutopilotState.log.add(System.currentTimeMillis(), LogLevel.ERROR, "Error de análisis: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            frame.release()
        }
        val ms = (System.nanoTime() - t0) / 1e6f
        avgMs = avgMs * 0.9f + ms * 0.1f
        // Si ya llegó otro fotograma mientras se analizaba, encadenar sin esperar.
        if (cap.hasPending()) requestAnalysis()
    }

    private fun execute(cmd: MoveCommand, eng: AutopilotEngine, cap: ScreenCapturer) {
        val svc = GestureAccessibilityService.instance
        val profile = eng.profile
        val accepted = svc?.swipe(
            cmd.direction, cap.screenW, cap.screenH,
            profile.playerBox.centerY, settings.swipeFraction, settings.swipeDurationMs,
        ) { ok -> val tDone = System.nanoTime(); analysis?.post { eng.onGestureResult(cmd.id, ok, tDone); if (!ok) failed++ } } ?: false
        if (accepted) { sent++; eng.onGestureDispatched(cmd.id, System.nanoTime()) } else {
            failed++
            eng.onGestureResult(cmd.id, false)
            AutopilotState.log.add(System.currentTimeMillis(), LogLevel.ERROR,
                "Gesto #${cmd.id} rechazado (¿accesibilidad desactivada o el juego no está al frente?)")
        }
    }

    private fun publish(out: com.farks.autopilot.models.EngineOutput, res: com.farks.autopilot.vision.DetectorResult?, frame: ScreenCapturer.Frame?) {
        val now = System.currentTimeMillis()
        for (e in out.events) AutopilotState.log.add(now, e.level, e.message)
        if (out.command != null || out.decision.action != com.farks.autopilot.models.Action.STAY) {
            AutopilotState.log.add(now, LogLevel.INFO, "Decisión: ${out.decision.action} — ${out.decision.reason}")
        }

        frames++
        windowFrames++
        val tn = System.nanoTime()
        if (tn - windowStartNs >= 1_000_000_000L) {
            fps = windowFrames * 1e9f / (tn - windowStartNs)
            windowFrames = 0
            windowStartNs = tn
        }

        // Fotograma anotado para el diagnóstico (máx. 4/s, solo si alguien lo está mirando).
        if (frame != null && AutopilotState.wantFrames && tn - lastBitmapNs > 250_000_000L) {
            lastBitmapNs = tn
            try {
                val bmp = Bitmap.createBitmap(frame.mat.cols(), frame.mat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(frame.mat, bmp)
                val old = AutopilotState.lastFrame
                AutopilotState.lastFrame = bmp
                old?.recycle()
            } catch (_: Exception) {}
        }

        if (!overlayWarned && settings.showOverlay) {
            val cap = capturer
            val prof = engine?.profile
            if (cap != null && prof != null) {
                val bottom = overlay.bottomNormalized(cap.screenH)
                if (bottom > prof.road.farY - 0.005f) {
                    overlayWarned = true
                    AutopilotState.log.add(now, LogLevel.WARN,
                        "La superposición llega al ${"%.0f".format(bottom * 100)}% de la altura y podría tapar la carretera " +
                            "(inicia en ${"%.0f".format(prof.road.farY * 100)}%). Desactívela en Ajustes si hay falsos obstáculos.")
                }
            }
        }

        val snap = Snapshot(
            running = true, packageName = pkg, output = out,
            rawDetections = res?.detections ?: emptyList(), detector = res,
            analysisFps = fps, processingMs = avgMs,
            gameInForeground = !pausedNotForeground,
            gesturesSent = sent, gesturesFailed = failed, framesAnalyzed = frames,
            lastError = null,
        )
        AutopilotState.publish(snap)
        overlay.update(snap)
    }

    // ------------------------------------------------------------------ parada

    private fun onCaptureStopped() {
        if (!active) return
        val eng = engine
        if (eng != null) publish(eng.onCaptureLost(System.nanoTime(), "El sistema detuvo la captura de pantalla"), null, null)
        AutopilotState.log.add(System.currentTimeMillis(), LogLevel.ERROR, "La captura de pantalla se detuvo; acciones suspendidas.")
        shutdown("La captura de pantalla se detuvo. Pulse INICIAR para volver a conceder el permiso.")
    }

    private fun shutdown(error: String?) {
        active = false
        GestureAccessibilityService.allowedPackage = null
        analysis?.removeCallbacksAndMessages(null)
        try { capturer?.stop() } catch (_: Exception) {}
        capturer = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        overlay.removeAsync()
        classifier?.close(); classifier = null
        analysisThread?.quitSafely(); analysisThread = null
        AutopilotState.publish(Snapshot(running = false, packageName = pkg.ifEmpty { null }, lastError = error))
        if (error != null) AutopilotState.log.add(System.currentTimeMillis(), LogLevel.ERROR, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active) shutdown(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.farks.autopilot.START"
        const val ACTION_STOP = "com.farks.autopilot.STOP"
        const val ACTION_EMERGENCY = "com.farks.autopilot.EMERGENCY"
        const val ACTION_REARM = "com.farks.autopilot.REARM"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE = "mode"
        const val MODE_AUTOPILOT = "autopilot"
        const val MODE_CALIBRATION = "calibration"
        private const val CHANNEL = "farks_run"
        private const val NOTIF_ID = 42

        fun stop(context: Context) {
            context.startService(Intent(context, AutopilotService::class.java).setAction(ACTION_STOP))
        }
    }
}
