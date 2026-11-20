package com.antiscam.guard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * "Look at the camera": takes [TARGET] face samples from the front camera, a moment apart.
 * Frames are only used to compute embeddings; no photo is saved.
 */
class EnrolActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_ADDED = "added"
        private const val TARGET = 3
        private const val GAP_MS = 700L
    }

    private lateinit var engine: FaceEngine
    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var name: String
    private var owner = false

    @Volatile private var count = 0
    @Volatile private var lastShot = 0L
    @Volatile private var finished = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { status.text = "Camera permission is needed to take selfies."; }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_NAME) ?: run { finish(); return }
        owner = intent.getBooleanExtra(EXTRA_OWNER, false)
        engine = FaceEngine.get(this)

        val dp = resources.displayMetrics.density
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        status = TextView(this).apply {
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            text = "Starting camera…"
        }
        val cancel = TextView(this).apply {
            text = "Cancel"; textSize = 18f; setTextColor(0xFF93C5FD.toInt()); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
            setOnClickListener { done() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC0D1117.toInt())
            addView(TextView(this@EnrolActivity).apply {
                text = "Adding $name${if (owner) " (me)" else ""}"
                textSize = 15f; setTextColor(0xFF8B949E.toInt()); gravity = Gravity.CENTER
                setPadding(0, (12 * dp).toInt(), 0, 0)
            })
            addView(status); addView(cancel)
        }
        setContentView(FrameLayout(this).apply {
            addView(previewView)
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        status.text = "Look at the camera"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(engine.executor) { analyse(it) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                status.text = "Couldn't open the front camera: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the engine thread. */
    private fun analyse(img: ImageProxy) {
        val t = SystemClock.elapsedRealtime()
        if (finished || t - lastShot < GAP_MS) { img.close(); return }
        val bmp = try { upright(img) } finally { img.close() }
        val face = engine.findFaces(bmp, maxFaces = 1).firstOrNull()
        val msg = when {
            face == null -> "Look at the camera"
            face.areaFraction < 0.04f -> "Move a little closer"
            else -> {
                FaceStore.addSample(this, name, owner, face.embedding)
                lastShot = t
                count++
                if (count >= TARGET) "✓ Done! $count face samples saved" else "✓ Got $count of $TARGET. Turn your head slightly"
            }
        }
        runOnUiThread {
            status.text = msg
            if (count >= TARGET && !finished) {
                finished = true
                LiveLog.add("Added $count face samples for $name from the camera")
                status.postDelayed({ done() }, 700)
            }
        }
    }

    private fun upright(img: ImageProxy): Bitmap {
        val raw = img.toBitmap()
        val deg = img.imageInfo.rotationDegrees
        if (deg == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }

    private fun done() {
        finished = true
        setResult(RESULT_OK, intent.putExtra(EXTRA_ADDED, count))
        finish()
    }
}
