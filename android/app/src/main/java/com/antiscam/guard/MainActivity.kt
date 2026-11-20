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
import android.widget.Switch
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
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
    private var familyVersion = -1L

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
    private val selfies = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val n = r.data?.getIntExtra(EnrolActivity.EXTRA_ADDED, 0) ?: 0
        if (n > 0) toast("Added $n face sample(s)")
        renderPeople()
    }

    private fun takeSelfies(name: String, isOwner: Boolean) = selfies.launch(
        Intent(this, EnrolActivity::class.java)
            .putExtra(EnrolActivity.EXTRA_NAME, name)
            .putExtra(EnrolActivity.EXTRA_OWNER, isOwner))

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
        find<Button>(R.id.btn_preview).setOnClickListener {
            val svc = CallWatcherService.running
            if (svc == null) toast("Turn on the call watcher first")
            else svc.previewWarning(FaceStore.load(this).firstOrNull { !it.isOwner }?.name ?: "Son")
        }

        setupFamily()

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
            if (FamilyLink.version != familyVersion) { familyVersion = FamilyLink.version; renderFamily() }
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
        renderFamily()
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
            ("Match strictness: %.2f (lower = stricter). Family phones are only asked below %.2f, " +
                "with 2 of 3 frames agreeing. Same person usually < 0.9, different people > 1.0.").format(
                FaceStore.threshold(this), FaceStore.threshold(this) - FaceStore.CONFIDENT_GAP)
    }

    // ------------------------------------------------------------------ family relay

    private fun setupFamily() {
        FamilyLink.init(this)
        val addr = find<EditText>(R.id.relay_addr)
        val on = find<Switch>(R.id.family_on)
        addr.setText(FamilyLink.relay)
        on.isChecked = FamilyLink.enabled
        find<Switch>(R.id.elder_on).isChecked = FamilyLink.elderMode
        find<CheckBox>(R.id.g_son).isChecked = FamilyLink.isGuardian("son")
        find<CheckBox>(R.id.g_daughter).isChecked = FamilyLink.isGuardian("daughter")
        find<EditText>(R.id.secret_q).setText(FamilyLink.secretQuestion)

        fun saveAddr() {
            val v = addr.text.toString().trim()
            if (v != FamilyLink.relay) { FamilyLink.relay = v; if (FamilyLink.enabled) FamilyLink.restart() }
        }
        addr.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) saveAddr(); false }
        addr.setOnFocusChangeListener { _, has -> if (!has) saveAddr() }
        on.setOnCheckedChangeListener { _, checked ->
            saveAddr()
            if (checked && FamilyLink.relay.isBlank()) { toast("Enter the relay address shown on the laptop first"); on.isChecked = false; return@setOnCheckedChangeListener }
            FamilyLink.enabled = checked
            if (checked) FamilyLink.restart() else FamilyLink.stop()
        }
        find<Switch>(R.id.elder_on).setOnCheckedChangeListener { _, c -> FamilyLink.elderMode = c }
        find<CheckBox>(R.id.g_son).setOnCheckedChangeListener { _, c -> FamilyLink.setGuardian("son", c) }
        find<CheckBox>(R.id.g_daughter).setOnCheckedChangeListener { _, c -> FamilyLink.setGuardian("daughter", c) }
        find<EditText>(R.id.secret_q).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { e?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { FamilyLink.secretQuestion = it } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val reply = find<SeekBar>(R.id.reply_s)
        fun showReply() { find<TextView>(R.id.reply_label).text = "Time to answer on their phone: ${FamilyLink.replySeconds}s" }
        reply.progress = FamilyLink.replySeconds - 10
        showReply()
        reply.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) { FamilyLink.replySeconds = 10 + v; showReply() } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        find<Button>(R.id.btn_sim_son).setOnClickListener {
            val svc = CallWatcherService.running
            when {
                svc == null -> toast("Turn on the call watcher first")
                !FamilyLink.connected -> toast("Connect to the family relay first")
                else -> svc.simulateCall("son")
            }
        }
        find<Button>(R.id.btn_forget_keys).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Forget Son's and Daughter's paired keys? Do this if one of them reinstalled the family app.")
                .setPositiveButton("Forget") { _, _ -> FamilyLink.forgetPins() }
                .setNegativeButton("Cancel", null).show()
        }
        if (FamilyLink.enabled && !FamilyLink.connected) FamilyLink.start()
    }

    private fun renderFamily() {
        val lines = mutableListOf(FamilyLink.status, "This phone (Mum): key ${DeviceKey.fingerprint}")
        for ((role, name) in FamilyLink.MEMBERS) {
            val online = if (FamilyLink.isOnline(role)) "🟢 online" else "⚪ offline"
            val pin = FamilyLink.pinOf(role)?.let { "key $it" } ?: "not paired yet"
            val warn = if (role in FamilyLink.keyMismatch) " ⚠️ KEY MISMATCH" else ""
            val face = if (FaceStore.load(this).any { it.name.equals(name, true) }) "" else " · no face enrolled as \"$name\""
            lines += "$name: $online · $pin$warn$face"
        }
        find<TextView>(R.id.family_status).text = lines.joinToString("\n")
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
                text = "📷"; setOnClickListener { takeSelfies(p.name, p.isOwner) }
            })
            row.addView(Button(this).apply {
                text = "🖼"; setOnClickListener { pick(Pick.Enrol(p.name, p.isOwner)) }
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
            .setPositiveButton("📷 Take selfies") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) toast("Enter a name first") else takeSelfies(n, owner.isChecked)
            }
            .setNeutralButton("Choose photo") { _, _ ->
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
