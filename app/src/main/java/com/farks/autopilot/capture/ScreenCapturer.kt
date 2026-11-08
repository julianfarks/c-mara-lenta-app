package com.farks.autopilot.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Captura la pantalla con MediaProjection a resolución reducida (el análisis no necesita más) y
 * entrega el último fotograma como Mat RGBA. Si el sistema detiene la proyección (usuario, bloqueo,
 * otra captura) avisa con [onStopped]; nunca se reinicia sola, porque en Android 14+ el permiso es
 * de un solo uso.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val targetWidth: Int,
    private val onStopped: () -> Unit,
) {
    val screenW: Int
    val screenH: Int
    val width: Int
    val height: Int

    private val thread = HandlerThread("farks-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader
    private var display: VirtualDisplay? = null

    @Volatile private var latest: Frame? = null
    @Volatile var framesReceived: Long = 0
        private set
    private var bytes = ByteArray(0)

    class Frame(val mat: Mat, val timestampNs: Long, private val owner: Mat) {
        fun release() {
            mat.release()
            if (owner !== mat) owner.release()
        }
    }

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.currentWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
        width = targetWidth.coerceAtMost(screenW)
        height = (screenH.toLong() * width / screenW).toInt()
        val dpi = context.resources.displayMetrics.densityDpi

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onImage(r) }, handler)

        // Android 14+: el callback debe registrarse ANTES de crear la pantalla virtual.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                onStopped()
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "farks-capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
    }

    private fun onImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val needed = rowStride * height
            if (bytes.size != needed) bytes = ByteArray(needed)
            buf.rewind()
            buf.get(bytes, 0, minOf(needed, buf.remaining()))
            val full = Mat(height, rowStride / 4, CvType.CV_8UC4)
            full.put(0, 0, bytes)
            val view = if (rowStride / 4 == width) full else full.submat(0, height, 0, width)
            val frame = Frame(view, System.nanoTime(), full)
            synchronized(this) {
                val old = latest
                latest = frame
                old?.release()
            }
            framesReceived++
        } finally {
            image.close()
        }
    }

    /** Devuelve y consume el último fotograma (el llamador libera el Mat), o null si no hay uno nuevo. */
    @Synchronized
    fun takeLatest(): Frame? {
        val f = latest
        latest = null
        return f
    }

    @Synchronized
    fun release() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader.close() } catch (_: Exception) {}
        try { thread.quitSafely() } catch (_: Exception) {}
    }

    fun stop() {
        release()
        try { projection.stop() } catch (_: Exception) {}
    }
}
