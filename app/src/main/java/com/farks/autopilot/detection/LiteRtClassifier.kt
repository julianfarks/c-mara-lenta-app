package com.farks.autopilot.detection

import android.content.Context
import com.farks.autopilot.models.RectN
import com.farks.autopilot.vision.CandidateClassifier
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Clasificador de candidatos con LiteRT (TensorFlow Lite) 100 % local.
 *
 * ESTE PROYECTO NO INCLUYE UN MODELO ENTRENADO. Si el archivo assets/models/obstacle_classifier.tflite
 * no existe, [load] devuelve null y el detector funciona solo con el análisis de color (ver
 * app/src/main/assets/models/README.md para el contrato del modelo).
 *
 * Contrato: entrada float32 [1, 64, 64, 3] RGB en [0,1]; salida float32 [1,1] = P(obstáculo).
 */
class LiteRtClassifier private constructor(private val interpreter: Interpreter) : CandidateClassifier {
    private val input: ByteBuffer = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(1) }

    override fun score(frame: Mat, box: RectN): Float {
        val x0 = (box.left * frame.cols()).toInt().coerceIn(0, frame.cols() - 2)
        val y0 = (box.top * frame.rows()).toInt().coerceIn(0, frame.rows() - 2)
        val x1 = (box.right * frame.cols()).toInt().coerceIn(x0 + 1, frame.cols())
        val y1 = (box.bottom * frame.rows()).toInt().coerceIn(y0 + 1, frame.rows())
        val crop = frame.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val small = Mat()
        Imgproc.resize(crop, small, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        crop.release()
        val rgb = Mat()
        if (small.channels() == 4) Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB) else small.copyTo(rgb)
        small.release()
        val px = ByteArray(SIZE * SIZE * 3)
        rgb.get(0, 0, px)
        rgb.release()
        input.rewind()
        for (b in px) input.putFloat((b.toInt() and 0xFF) / 255f)
        input.rewind()
        interpreter.run(input, output)
        return output[0][0].coerceIn(0f, 1f)
    }

    fun close() = interpreter.close()

    companion object {
        private const val SIZE = 64
        const val ASSET = "models/obstacle_classifier.tflite"

        /** @return el clasificador, o null si no hay modelo (comportamiento esperado por defecto). */
        fun load(context: Context): LiteRtClassifier? = try {
            val afd = context.assets.openFd(ASSET)
            val buf = afd.createInputStream().channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength,
            )
            LiteRtClassifier(Interpreter(buf, Interpreter.Options().setNumThreads(2)))
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
