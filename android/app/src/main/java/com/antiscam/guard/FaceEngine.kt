package com.antiscam.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device face pipeline: ML Kit finds faces, MobileFaceNet (TFLite) turns each face into an
 * L2-normalised embedding. Nothing leaves the phone.
 *
 * Not thread-safe: always call from [executor] (shared by the app screen and the background service).
 */
class FaceEngine private constructor(ctx: Context) {

    companion object {
        @Volatile private var instance: FaceEngine? = null
        fun get(ctx: Context): FaceEngine =
            instance ?: synchronized(this) { instance ?: FaceEngine(ctx.applicationContext).also { instance = it } }
    }

    /** Single worker thread for all detection + embedding work. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )

    private val interpreter: Interpreter
    private val inputSize: Int
    private val embeddingSize: Int

    init {
        val fd = ctx.assets.openFd("mobilefacenet.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
        val shape = interpreter.getInputTensor(0).shape() // [batch, h, w, 3]
        if (shape[0] != 1) {
            interpreter.resizeInput(0, intArrayOf(1, shape[1], shape[2], shape[3]))
            interpreter.allocateTensors()
        }
        inputSize = shape[1]
        embeddingSize = interpreter.getOutputTensor(0).shape().last()
        // self-check: one inference on a blank face so a broken model fails loudly at start-up
        val probe = embed(Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888))
        Log.i("AntiScamGuard", "Face model OK: $modelInfo, probe norm=%.3f".format(probe.sumOf { (it * it).toDouble() }))
    }

    val modelInfo: String get() = "MobileFaceNet ${inputSize}px → $embeddingSize-d"

    class FoundFace(val box: Rect, val embedding: FloatArray, val areaFraction: Float)

    /** Faces in [bitmap], biggest first. [bitmap] must be a software (ARGB_8888) bitmap. */
    fun findFaces(bitmap: Bitmap, maxFaces: Int = 3): List<FoundFace> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val total = (bitmap.width * bitmap.height).toFloat()
        return faces
            .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
            .take(maxFaces)
            .mapNotNull { f ->
                val r = squareInside(f.boundingBox, bitmap.width, bitmap.height) ?: return@mapNotNull null
                val crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
                FoundFace(f.boundingBox, embed(crop), r.width() * r.height() / total)
            }
    }

    private fun embed(face: Bitmap): FloatArray {
        val s = inputSize
        val scaled = Bitmap.createScaledBitmap(face, s, s, true)
        val px = IntArray(s * s)
        scaled.getPixels(px, 0, s, 0, 0, s, s)
        val input = ByteBuffer.allocateDirect(4 * s * s * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
            input.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        input.rewind()
        val out = Array(1) { FloatArray(embeddingSize) }
        interpreter.run(input, out)
        return normalise(out[0])
    }

    /** Square crop around the face, always fully inside the w×h bitmap (faces at the edge used to overflow by 1 px). */
    internal fun squareInside(b: Rect, w: Int, h: Int): Rect? =
        cropSquare(b.centerX(), b.centerY(), b.width(), b.height(), w, h)?.let { (l, t, side) -> Rect(l, t, l + side, t + side) }

    private fun normalise(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }
}

/**
 * Pure helper (unit-tested): square of side min(max(faceW, faceH), min(w, h)) centred on the face and clamped so it is
 * always fully inside the w×h bitmap. Returns (left, top, side), or null if the face is too small to judge.
 */
internal fun cropSquare(cx: Int, cy: Int, faceW: Int, faceH: Int, w: Int, h: Int): Triple<Int, Int, Int>? {
    val side = min(max(faceW, faceH), min(w, h))
    if (side < 24) return null
    val left = (cx - side / 2).coerceIn(0, w - side)
    val top = (cy - side / 2).coerceIn(0, h - side)
    return Triple(left, top, side)
}
