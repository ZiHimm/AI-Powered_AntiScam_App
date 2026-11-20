package com.antiscam.guard

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: FaceEngine
    private var logVersion = -1L

    /** What the photo picker result is for. */
    private sealed interface Pick {
        data class Enrol(val name: String, val isOwner: Boolean) : Pick
        data object Test : Pick
    }
    private var pending: Pick? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val p = pending; pending = null
        if (uri != null && p != null) handlePhoto(uri, p)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = FaceEngine.get(this)
        Notifier.ensureChannels(this)

        find<Button>(R.id.btn_service).setOnClickListener { openAccessibilitySettings() }
        find<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        find<Button>(R.id.btn_add_person).setOnClickListener { askForPerson() }
        find<Button>(R.id.btn_test).setOnClickListener { pick(Pick.Test) }

        val bar = find<SeekBar>(R.id.threshold)
        bar.progress = ((FaceStore.threshold(this) - 0.6f) * 100).toInt().coerceIn(0, 80)
        showThreshold()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) { FaceStore.setThreshold(this@MainActivity, 0.6f + v / 100f); showThreshold() }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        main.post(pollLog)
    }

    override fun onPause() {
        main.removeCallbacks(pollLog)
        super.onPause()
    }

    private val pollLog = object : Runnable {
        override fun run() {
            if (LiveLog.version != logVersion) {
                logVersion = LiveLog.version
                find<TextView>(R.id.log).text = LiveLog.text().ifEmpty { "Nothing yet." }
                refreshServiceStatus()
            }
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ status

    private fun refresh() {
        refreshServiceStatus()
        val notifOk = Notifier.canPost(this)
        find<TextView>(R.id.status_notif).text =
            if (notifOk) "✅ Notifications allowed" else "❌ Notifications are off. Warnings can't be shown."
        find<Button>(R.id.btn_notif).visibility = if (notifOk) View.GONE else View.VISIBLE
        find<TextView>(R.id.status_android).text =
            if (Build.VERSION.SDK_INT >= 30) "✅ Android ${Build.VERSION.RELEASE}: automatic face checks supported"
            else "⚠️ Android ${Build.VERSION.RELEASE}: automatic face checks need Android 11 or newer"
        renderPeople()
    }

    private fun refreshServiceStatus() {
        val on = isServiceEnabled()
        find<TextView>(R.id.status_service).text =
            if (on) "✅ Call watcher is on"
            else "❌ Call watcher is off. Open Accessibility settings → Installed apps → AntiScam Guard → turn on.\n" +
                "If the switch is greyed out (Android 13+): App info → ⋮ → Allow restricted settings, then try again."
        find<Button>(R.id.btn_service).text = if (on) "Accessibility settings" else "Turn on call watcher"
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, CallWatcherService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        // entries may be "pkg/.Cls" (short) or "pkg/pkg.Cls" (full); unflatten handles both
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        if (!isServiceEnabled()) Toast.makeText(this,
            "Find “AntiScam Guard” under Installed apps / Downloaded apps", Toast.LENGTH_LONG).show()
    }

    private fun showThreshold() {
        find<TextView>(R.id.threshold_label).text =
            "Match strictness: %.2f (lower = stricter). Same person usually < 0.9, different people > 1.1.".format(
                FaceStore.threshold(this))
    }

    // ------------------------------------------------------------------ people

    private fun renderPeople() {
        val box = find<LinearLayout>(R.id.people)
        box.removeAllViews()
        val people = FaceStore.load(this)
        if (people.isEmpty()) {
            box.addView(TextView(this).apply { text = "No one enrolled yet."; setTextColor(0xFF8B949E.toInt()); textSize = 15f })
        }
        for (p in people) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
            row.addView(TextView(this).apply {
                text = "${if (p.isOwner) "🙋 ${p.name} (me)" else "👤 ${p.name}"} · ${p.embeddings.size} photo(s)"
                setTextColor(0xFFE6EDF3.toInt()); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "+ Photo"; setOnClickListener { pick(Pick.Enrol(p.name, p.isOwner)) }
            })
            row.addView(Button(this).apply {
                text = "✕"; setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Remove ${p.name} and their face data?")
                        .setPositiveButton("Remove") { _, _ -> FaceStore.remove(this@MainActivity, p.name); renderPeople() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            box.addView(row)
        }
    }

    private fun askForPerson() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply { hint = "Name as saved in contacts, e.g. Son or Aisha" }
        val owner = CheckBox(this).apply { text = "This is me (the phone's owner)" }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(owner)
        }
        AlertDialog.Builder(this)
            .setTitle("Add person")
            .setView(form)
            .setPositiveButton("Choose photo") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) toast("Enter a name first") else pick(Pick.Enrol(n, owner.isChecked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ photos

    private fun pick(p: Pick) {
        pending = p
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun handlePhoto(uri: Uri, p: Pick) {
        val out = find<TextView>(R.id.test_result)
        if (p is Pick.Test) out.text = "Checking…"
        engine.executor.execute {
            val msg = try {
                val bmp = loadBitmap(uri)
                val faces = engine.findFaces(bmp)
                when {
                    faces.isEmpty() -> "No face found in that photo. Try a clearer, front-facing one."
                    p is Pick.Enrol -> {
                        FaceStore.addSample(this, p.name, p.isOwner, faces.first().embedding)
                        "Added a face sample for ${p.name}" + if (faces.size > 1) " (used the biggest face)" else ""
                    }
                    else -> {
                        val people = FaceStore.load(this)
                        val th = FaceStore.threshold(this)
                        faces.mapIndexed { i, f ->
                            val m = FaceStore.best(people, f.embedding)
                            val who = if (m.person != null && m.distance <= th) "✅ ${m.person.name}" else "❓ not in family"
                            "Face ${i + 1}: $who (closest: ${m.person?.name ?: "-"}, distance %.2f)".format(m.distance)
                        }.joinToString("\n")
                    }
                }
            } catch (t: Throwable) {
                "Couldn't read that photo: ${t.message}"
            }
            main.post {
                if (p is Pick.Test) out.text = msg else { toast(msg); renderPeople() }
                LiveLog.add(msg.lineSequence().first())
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val maxSide = 1280
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // EXIF rotation is applied automatically
                val s = max(info.size.width, info.size.height)
                if (s > maxSide) dec.setTargetSampleSize((s + maxSide - 1) / maxSide)
            }.copy(Bitmap.Config.ARGB_8888, false)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / maxSide)
        }
        return contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("unsupported image")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun <T : View> find(id: Int): T = findViewById(id)
}
