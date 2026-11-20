package com.antiscam.guard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** "Join family": scan the inviter's QR code (on-device ML Kit). Returns the invite link as `data`. */
class ScanActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var hint: TextView
    @Volatile private var done = false
    private val worker = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else hint.text = "Camera permission is needed to scan the invite."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        hint = TextView(this).apply {
            text = "Point the camera at the family invite QR code"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setBackgroundColor(0xCC0D1117.toInt())
            setPadding(32, 32, 32, 48)
        }
        setContentView(FrameLayout(this).apply {
            addView(preview)
            addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { img ->
                val media = img.image
                if (done || media == null) { img.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> Invite.parse(v) != null } }
                        if (raw != null && !done) {
                            done = true
                            setResult(RESULT_OK, Intent().putExtra("invite", raw)); finish()
                        } else if (codes.isNotEmpty()) runOnUiThread { hint.text = "That QR code isn't an AntiScam Guard family invite" }
                    }
                    .addOnCompleteListener { img.close() }
            }
            try { provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis) }
            catch (t: Throwable) { hint.text = "Couldn't open the camera: ${t.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() { scanner.close(); worker.shutdown(); super.onDestroy() }
}
