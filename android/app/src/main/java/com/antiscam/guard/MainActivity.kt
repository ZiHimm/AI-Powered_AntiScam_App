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
import android.os.PowerManager
import android.graphics.drawable.GradientDrawable
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
        if (n > 0) FaceShare.sendMyFace()   // if it was my own face, the family's phones get it (signed)
        renderPeople()
    }

    private fun takeSelfies(name: String, isOwner: Boolean) = selfies.launch(
        Intent(this, EnrolActivity::class.java)
            .putExtra(EnrolActivity.EXTRA_NAME, name)
            .putExtra(EnrolActivity.EXTRA_OWNER, isOwner))

    private val scanInvite = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("invite")?.let { Invite.parse(it) }?.let { confirmJoin(it) }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyLink.init(this)
        if (!FamilyLink.setupDone) {   // first run (or an invite link on a new phone): the setup wizard
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_INVITE, intent?.data?.toString()))
            finish(); return
        }
        setContentView(R.layout.activity_main)
        MemberHub.init(this); FamilyLink.listeners.add(MemberHub)
        intent?.data?.toString()?.let { Invite.parse(it) }?.let { main.post { confirmJoin(it) } }   // opened from an invite link
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
        find<Button>(R.id.btn_settings).setOnClickListener { showAdvanced(find<View>(R.id.advanced).visibility != View.VISIBLE) }
        find<Button>(R.id.btn_battery).setOnClickListener { requestBatteryExemption() }

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
            if (FamilyLink.version != familyVersion) { familyVersion = FamilyLink.version; renderFamily(); renderHome() }
            if (LiveLog.version != logVersion) {
                renderHome()
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
        val batteryOk = isBatteryExempt()
        find<TextView>(R.id.status_battery).text =
            if (batteryOk) "✅ Battery saver won't stop protection" else "⚠️ Battery saver may stop protection in the background"
        find<Button>(R.id.btn_battery).visibility = if (batteryOk) View.GONE else View.VISIBLE
        renderPeople()
        renderFamily()
        renderHome()
    }

    // ------------------------------------------------------------------ one app: who am I in the family?

    /** First launch (and "Change my name / setup"): name → member id, senior citizen?, check my calls? */
    private var seniorBox: CheckBox? = null

    private fun askWhoAmI() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply { hint = "Your name in the family (e.g. Mum, Dad, Aisha)"; setText(FamilyLink.myRole?.let { FamilyLink.myName } ?: "") }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Mum", "Dad", "Son", "Daughter").forEach { n ->
            chips.addView(Button(this).apply { text = n; isAllCaps = false; setOnClickListener { name.setText(n); if (n == "Mum") seniorBox?.isChecked = true } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val phone = EditText(this).apply { hint = "My phone number (optional, so family can call me)"; inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(FamilyLink.myPhone) }
        val senior = CheckBox(this).apply { text = "I'm a senior citizen: alert my guardians if a scammer targets me"; isChecked = FamilyLink.senior }
        seniorBox = senior
        val protect = CheckBox(this).apply { text = "Check my incoming video calls on this phone"; isChecked = FamilyLink.protect }
        senior.setOnCheckedChangeListener { _, c -> if (c) protect.isChecked = true }   // seniors are protected by default
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(chips); addView(phone); addView(senior); addView(protect)
            addView(TextView(context).apply { text = "Use the same names your family uses (Mum, Son…) so everyone's phone matches."; textSize = 13f; setPadding(0, pad / 2, 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("Who are you in the family?").setView(form).setCancelable(FamilyLink.myRole != null)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val id = FamilyLink.idForName(n)
                if (id.isEmpty()) { toast("Enter your name"); main.post { askWhoAmI() }; return@setPositiveButton }
                val changed = FamilyLink.myRole != id
                FamilyLink.myName = n; FamilyLink.myRole = id
                FamilyLink.senior = senior.isChecked; FamilyLink.protect = protect.isChecked; FamilyLink.myPhone = phone.text.toString()
                if (FamilyLink.enabled && (changed || !FamilyLink.connected)) { FamilyService.start(this); FamilyLink.restart() }
                refresh()
                if (!FamilyLink.enabled) main.post { askFamilySetup() }
            }.show()
    }

    // ------------------------------------------------------------------ create / join a family

    private fun askFamilySetup() {
        AlertDialog.Builder(this).setTitle("Your family")
            .setItems(arrayOf("👑  Create a new family (I'll invite the others)", "📷  Join my family (scan their QR code)", "Later")) { _, i ->
                when (i) { 0 -> askCreateFamily(); 1 -> scanInvite.launch(Intent(this, ScanActivity::class.java)) }
            }.show()
    }

    private fun askCreateFamily() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val fname = EditText(this).apply { hint = "Family name, e.g. Tan family"; setText(FamilyLink.familyName) }
        val base = EditText(this).apply {
            hint = "Relay address, e.g. https://….trycloudflare.com"; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(FamilyLink.relay.takeIf { it.isNotBlank() }?.let { Invite.baseOf(it) } ?: "")
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(fname); addView(base) }
        AlertDialog.Builder(this).setTitle("👑 Create a family").setView(form)
            .setMessage("A new private family room is made with a random code. You'll get a QR code to invite the others.")
            .setPositiveButton("Create") { _, _ ->
                val b = base.text.toString().trim()
                if (b.isEmpty()) { toast("Enter the relay address (ask whoever runs the relay)"); return@setPositiveButton }
                FamilyLink.createFamily(b, fname.text.toString().trim().ifBlank { "${FamilyLink.myName}'s family" })
                FamilyService.start(this); FamilyLink.restart()
                find<EditText>(R.id.relay_addr).setText(FamilyLink.relay); find<Switch>(R.id.family_on).isChecked = true
                refresh()
                startActivity(Intent(this, InviteActivity::class.java))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmJoin(inv: Invite) {
        AlertDialog.Builder(this).setTitle("Join ${inv.familyName.ifBlank { "this family" }}?")
            .setMessage("Invited by ${inv.inviterName.ifBlank { "a family member" }}.\nTheir phone's key ${inv.inviterKey} will be trusted.\n\n" +
                "This replaces any family this phone was in before.")
            .setPositiveButton("Join") { _, _ ->
                FamilyLink.joinFamily(inv)
                // connect only once this phone has a name, so the family never sees a nameless member
                if (FamilyLink.myRole != null) { FamilyService.start(this); FamilyLink.restart() }
                find<EditText>(R.id.relay_addr).setText(FamilyLink.relay); find<Switch>(R.id.family_on).isChecked = true
                refresh()
                if (FamilyLink.myRole == null) askWhoAmI() else toast("Joined ${inv.familyName}")
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { Invite.parse(it) }?.let { confirmJoin(it) }
    }

    // ------------------------------------------------------------------ home: the big status card

    private class Fix(val label: String, val action: () -> Unit)

    private fun renderHome() {
        val serviceOn = isServiceEnabled()
        val family = FaceStore.load(this).filter { !it.isOwner }
        val open = MemberHub.openQuestions
        find<Button>(R.id.btn_inbox).apply {
            text = if (open > 0) "📬 $open question${if (open > 1) "s" else ""} for you: tap to answer" else "📬 Family questions & alerts"
            setOnClickListener { startActivity(Intent(this@MainActivity, FamilyActivity::class.java)) }
        }
        // first problem wins: (level, title, detail, fix)
        val (level, detail, fix) = when {
            !FamilyLink.protect -> Triple(if (FamilyLink.connected) 0 else 1,
                "My calls aren't checked on this phone. I'll be asked when someone who looks like me calls the family.",
                if (FamilyLink.enabled) null else Fix("Connect to my family") { startActivity(Intent(this, SetupActivity::class.java)) })
            !serviceOn -> Triple(2, "Protection is switched off.", Fix("Turn on protection") { openAccessibilitySettings() })
            Build.VERSION.SDK_INT < 30 -> Triple(2, "This phone needs Android 11 or newer for automatic checks.", null)
            !Notifier.canPost(this) -> Triple(1, "Warnings can't be shown on this phone.", Fix("Allow warnings") { find<Button>(R.id.btn_notif).performClick() })
            family.isEmpty() -> Triple(1, "No family faces added yet.", Fix("Add family faces") { showAdvanced(true); toast("Ask your family to add their own face in their app (it arrives here automatically), or add someone under 🛡️ My protection") })
            !isBatteryExempt() -> Triple(1, "Battery saver may switch protection off.", Fix("Keep protection on") { requestBatteryExemption() })
            FamilyLink.enabled && !FamilyLink.connected -> Triple(1, "Can't reach the family phones right now. Warnings still work.", null)
            else -> Triple(0, "Video calls on WhatsApp, Instagram, Messenger and Telegram are checked.", null)
        }
        val (icon, title, color) = when (level) {
            0 -> Triple("🛡️", if (FamilyLink.protect) "Protected" else "Family alerts on", 0xFF166534.toInt())
            1 -> Triple("🛡️", "Partly protected", 0xFF92400E.toInt())
            else -> Triple("⚠️", "Not protected", 0xFF991B1B.toInt())
        }
        find<LinearLayout>(R.id.home_card).background = GradientDrawable().apply {
            cornerRadius = 28 * resources.displayMetrics.density; setColor(color)
        }
        find<TextView>(R.id.home_icon).text = icon
        find<TextView>(R.id.home_title).text = title
        find<TextView>(R.id.home_detail).text = detail
        val familyLine = when {
            FamilyLink.enabled && FamilyLink.members.isNotEmpty() -> FamilyLink.others().joinToString("    ") { m ->
                "${FamilyLink.nameOf(m.id)} ${if (m.online) "🟢" else "⚪"}" }
            family.isNotEmpty() -> "Family: ${family.joinToString(", ") { it.name.replaceFirstChar { c -> c.uppercase() } }}"
            else -> ""
        }
        find<TextView>(R.id.home_family).visibility = View.GONE   // the family card below shows everyone
        renderFamilyCard()
        find<TextView>(R.id.home_last).text = LiveLog.callLine ?: "No video calls checked yet."
        find<Button>(R.id.btn_fix).apply {
            visibility = if (fix == null) View.GONE else View.VISIBLE
            text = fix?.label ?: ""
            // solid white button with the card's colour as text: readable on red/amber for older eyes
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { fix?.action?.invoke() }
        }
    }

    /** Home: "My family" card. Everyone, online status, senior / face / guardian tags, and Invite. */
    private fun renderFamilyCard() {
        val box = find<LinearLayout>(R.id.family_card)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        box.background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(0xFF161B22.toInt()) }
        fun label(t: String, sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(c); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun pill(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER; setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(color) }; setOnClickListener { onClick() }
        }
        fun row(name: String, sub: String, online: Boolean?) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, px(8))
            val hue = floatArrayOf((name.lowercase().hashCode() and 0x7fffffff) % 360f, 0.55f, 0.55f)
            addView(TextView(context).apply {
                text = name.take(1).uppercase(); textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(android.graphics.Color.HSVToColor(hue)) }
            }, LinearLayout.LayoutParams(px(46), px(46)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0)
                addView(label(name + when (online) { true -> "  🟢"; false -> "  ⚪"; null -> "" }, 19f, 0xFFE6EDF3.toInt(), bold = true))
                if (sub.isNotEmpty()) addView(label(sub, 14f, 0xFF9DA7B3.toInt()))
            })
        }
        val code = FamilyLink.familyCode
        if (code == null && FamilyLink.members.isEmpty()) {
            box.addView(label("👪 Your family", 20f, 0xFFE6EDF3.toInt(), bold = true))
            box.addView(label("You're not connected to your family yet.", 16f, 0xFF9DA7B3.toInt()).apply { setPadding(0, px(4), 0, px(10)) })
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("👑 Create", 0xFF2563EB.toInt()) { askCreateFamily() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(8) })
                addView(pill("📷 Join", 0xFF16A34A.toInt()) { scanInvite.launch(Intent(this@MainActivity, ScanActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            return
        }
        val faces = FaceStore.load(this)
        box.addView(label("👪 ${FamilyLink.familyName.ifBlank { "My family" }}", 20f, 0xFFE6EDF3.toInt(), bold = true))
        val meTags = listOfNotNull(if (FamilyLink.senior) "senior" else null,
            if (FamilyLink.protect) "calls checked" else null, if (faces.any { it.isOwner }) "face ✓" else "add your face")
        box.addView(row("${FamilyLink.myName} (me)", meTags.joinToString(" · "), null))
        for (m in FamilyLink.others()) {
            val n = FamilyLink.nameOf(m.id)
            val tags = listOfNotNull(
                if (m.senior) "senior" else null,
                if (faces.any { it.memberId == m.id || it.name.equals(n, true) }) "face ✓" else "no face yet",
                if (FamilyLink.isGuardian(m.id) && FamilyLink.senior) "my guardian" else null,
                if (m.senior && m.guardians.contains(FamilyLink.me)) "I'm their guardian" else null)
            box.addView(row(n, tags.joinToString(" · "), m.online))
        }
        if (FamilyLink.members.isEmpty()) box.addView(label("Just you so far.", 16f, 0xFF9DA7B3.toInt()).apply { setPadding(0, px(4), 0, 0) })
        if (code != null) box.addView(pill("➕ Invite family", 0xFF2563EB.toInt()) {
            startActivity(Intent(this, InviteActivity::class.java))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
    }

    private fun showAdvanced(show: Boolean) {
        find<View>(R.id.advanced).visibility = if (show) View.VISIBLE else View.GONE
        find<Button>(R.id.btn_settings).text = if (show) "Hide settings" else "⚙ Settings & setup"
    }

    // ------------------------------------------------------------------ battery saver

    private fun isBatteryExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) // some phones hide the direct prompt
        }
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
        find<Switch>(R.id.elder_on).isChecked = FamilyLink.senior
        find<Switch>(R.id.protect_on).isChecked = FamilyLink.protect
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
            if (checked && FamilyLink.myRole == null) return@setOnCheckedChangeListener   // connect once the phone has a name
            if (checked) { FamilyService.start(this); FamilyLink.restart() } else { FamilyService.stop(this); FamilyLink.stop() }
        }
        find<Switch>(R.id.elder_on).setOnCheckedChangeListener { _, c -> FamilyLink.senior = c; renderHome() }
        find<Switch>(R.id.protect_on).setOnCheckedChangeListener { _, c -> FamilyLink.protect = c; renderHome() }
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
            val target = FamilyLink.others().firstOrNull { it.online }
            when {
                svc == null -> toast("Turn on the call watcher first")
                !FamilyLink.connected -> toast("Connect to the family relay first")
                target == null -> toast("No other family phone is online")
                else -> svc.simulateCall(target.id)
            }
        }
        find<Button>(R.id.btn_change_role).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        find<Button>(R.id.btn_create_family).setOnClickListener { askCreateFamily() }
        find<Button>(R.id.btn_join_family).setOnClickListener { scanInvite.launch(Intent(this, ScanActivity::class.java)) }
        find<Button>(R.id.btn_invite).setOnClickListener {
            if (FamilyLink.familyCode == null) toast("Create or join a family first") else startActivity(Intent(this, InviteActivity::class.java))
        }
        find<Button>(R.id.btn_forget_keys).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Forget the family's paired keys? Do this if someone reinstalled the app or changed phone.")
                .setPositiveButton("Forget") { _, _ -> FamilyLink.forgetPins() }
                .setNegativeButton("Cancel", null).show()
        }
        if (FamilyLink.enabled) FamilyService.start(this)   // the background service owns the connection on every phone
    }

    private fun renderFamily() {
        val me = FamilyLink.myRole
        find<TextView>(R.id.family_line).text = when {
            FamilyLink.familyCode == null -> "Not in a family yet: create one, or join with a QR code"
            else -> "👪 ${FamilyLink.familyName.ifBlank { "My family" }}${if (FamilyLink.isAdmin) " · I created it" else ""}"
        }
        find<TextView>(R.id.me_line).text = if (me == null) "Not set up yet" else
            "I'm ${FamilyLink.myName} (id \"$me\")${if (FamilyLink.senior) " · senior citizen" else ""}"
        val faces = FaceStore.load(this)
        val lines = mutableListOf(FamilyLink.status, "This phone: key ${DeviceKey.fingerprint}" +
            if (faces.none { it.isOwner }) " · ⚠️ add YOUR face (🛡️ My protection → + Add a face, tick “This is me”) so the family's phones know you" else " · my face ✓")
        for (m in FamilyLink.others()) {
            val name = FamilyLink.nameOf(m.id)
            val online = if (m.online) "🟢" else "⚪"
            val pin = FamilyLink.pinOf(m.id)?.let { "key $it" } ?: "not paired yet"
            val warn = if (m.id in FamilyLink.keyMismatch) " ⚠️ KEY MISMATCH" else ""
            val face = when {
                faces.any { it.memberId == m.id } -> " · face shared ✓"
                faces.any { it.name.equals(name, true) } -> " · face enrolled here ✓"
                else -> " · no face yet"
            }
            val extra = (if (m.senior) " · senior" else "") + (if (!m.v2 && m.online) " · older app" else "")
            lines += "$online $name: $pin$warn$face$extra"
        }
        if (FamilyLink.members.isEmpty()) lines += "No other family phones seen yet."
        find<TextView>(R.id.family_status).text = lines.joinToString("\n")
        // my guardians: one checkbox per member
        val box = find<LinearLayout>(R.id.guardians_box)
        box.removeAllViews()
        for (m in FamilyLink.others()) box.addView(CheckBox(this).apply {
            text = FamilyLink.nameOf(m.id); isChecked = FamilyLink.isGuardian(m.id); setTextColor(0xFFC9D1D9.toInt())
            setOnCheckedChangeListener { _, c -> FamilyLink.setGuardian(m.id, c) }
        })
        if (FamilyLink.members.isEmpty()) box.addView(TextView(this).apply { text = "(appear here when they connect)"; setTextColor(0xFF8B949E.toInt()) })
        find<Button>(R.id.btn_sim_son).text = FamilyLink.others().firstOrNull { it.online }
            ?.let { "Test: a call that looks like ${FamilyLink.nameOf(it.id)}" } ?: "Test: a call that looks like… (no one online)"
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
                        .setPositiveButton("Remove") { _, _ -> FaceStore.remove(this@MainActivity, p.name); if (p.isOwner) FaceShare.sendMyFace(); renderPeople() }
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
                        if (p.isOwner) main.post { FaceShare.sendMyFace() }
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
