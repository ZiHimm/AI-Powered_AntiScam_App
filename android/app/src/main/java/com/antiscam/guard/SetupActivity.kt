package com.antiscam.guard

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * First-run setup, one simple screen at a time (big text, one obvious button):
 *   Welcome → About you → Your family (create / join) → [Invite] → Your face → Turn on protection → Done
 * Opened from MainActivity when the phone isn't set up yet (also when an invite link is opened on a new phone).
 */
class SetupActivity : AppCompatActivity() {

    companion object { const val EXTRA_INVITE = "invite" }

    private enum class Step { WELCOME, ABOUT, FAMILY, CREATE, INVITE, FACE, PROTECT, DONE }
    private var step = Step.WELCOME
    private var pendingInvite: Invite? = null

    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    // colours (same palette as the rest of the app)
    private val bg = 0xFF0D1117.toInt(); private val fg = 0xFFE6EDF3.toInt(); private val muted = 0xFF9DA7B3.toInt()
    private val blue = 0xFF2563EB.toInt(); private val green = 0xFF16A34A.toInt(); private val card = 0xFF161B22.toInt()

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("invite")?.let { Invite.parse(it) }?.let { pendingInvite = it; show(Step.FAMILY) }
    }
    private var sharedWith = -1   // phones my face was just sent to (-1: not taken in this session)
    private val selfies = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if ((r.data?.getIntExtra(EnrolActivity.EXTRA_ADDED, 0) ?: 0) > 0) sharedWith = FaceShare.sendMyFace()
        show(Step.FACE)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { show(step) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyLink.init(this); MemberHub.init(this); Notifier.ensureChannels(this)
        // a brand-new phone: record "not finished" explicitly, so naming yourself in step 1 doesn't count as done
        if (FamilyLink.myRole == null) FamilyLink.setupDone = false
        pendingInvite = intent.getStringExtra(EXTRA_INVITE)?.let { Invite.parse(it) }
        // resumed after the app was closed (a big camera step can do that on real phones): reconnect to the family, else
        // the face taken next would have nowhere to go
        if (FamilyLink.familyCode != null && FamilyLink.enabled && !FamilyLink.connected) connect()
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(28), px(24), px(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(body) })
        show(resumeStep())
    }

    /** Pick up where an interrupted setup left off (a call came in, the app was closed…). */
    private fun resumeStep(): Step {
        if (FamilyLink.myRole == null) return Step.WELCOME
        if (pendingInvite != null) return Step.FAMILY
        val saved = getSharedPreferences("setup", MODE_PRIVATE).getString("step", null)
            ?.let { runCatching { Step.valueOf(it) }.getOrNull() }
        return when (saved) {
            null, Step.WELCOME, Step.ABOUT -> if (FamilyLink.familyCode == null) Step.FAMILY else Step.FACE
            Step.CREATE, Step.INVITE -> Step.FAMILY
            else -> saved
        }
    }

    override fun onResume() { super.onResume(); if (step == Step.PROTECT || step == Step.FACE) show(step) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (step) {
            Step.WELCOME, Step.DONE -> super.onBackPressed()
            Step.ABOUT -> show(Step.WELCOME)
            Step.FAMILY -> show(Step.ABOUT)
            Step.CREATE -> show(Step.FAMILY)
            Step.INVITE, Step.FACE -> show(Step.FAMILY)
            Step.PROTECT -> show(Step.FACE)
        }
    }

    // ------------------------------------------------------------------ steps

    private fun show(s: Step) {
        step = s
        getSharedPreferences("setup", MODE_PRIVATE).edit().putString("step", s.name).apply()
        body.removeAllViews()
        when (s) {
            Step.WELCOME -> welcome()
            Step.ABOUT -> about()
            Step.FAMILY -> family()
            Step.CREATE -> create()
            Step.INVITE -> invite()
            Step.FACE -> face()
            Step.PROTECT -> protect()
            Step.DONE -> done()
        }
    }

    private fun welcome() {
        body.gravity = Gravity.CENTER_HORIZONTAL
        add(text("🛡️", 84f, fg, center = true, top = 40))
        add(text("AntiScam Guard", 34f, fg, bold = true, center = true, top = 8))
        add(text("Scammers can fake a family member's face on a video call. We don't guess whether the video is fake: we ask the real person, on their own phone.",
            19f, muted, center = true, top = 16))
        add(text("Faces and data stay on your family's phones.", 16f, muted, center = true, top = 12))
        if (pendingInvite != null) add(text("📩 You've been invited to ${pendingInvite!!.familyName.ifBlank { "a family" }}.", 18f, 0xFF93C5FD.toInt(), center = true, top = 16))
        add(button("Get started", blue, top = 40) { show(Step.ABOUT) })
    }

    private fun about() {
        header(1, "About you")
        var senior = FamilyLink.senior
        var protect = FamilyLink.protect
        lateinit var seniorBtn: TextView
        lateinit var protectBtn: TextView
        fun paint() {
            toggle(seniorBtn, senior, "👵 I'm a senior citizen", "My guardians are alerted if a scammer targets me")
            toggle(protectBtn, protect, "🛡️ Check my incoming video calls", "Recommended for seniors. Others can leave it off.")
        }
        add(text("Your name in the family", 17f, fg, bold = true, top = 8))
        val name = input("e.g. Mum, Dad, Aisha", FamilyLink.myRole?.let { FamilyLink.myName } ?: "")
        add(name)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Mum", "Dad", "Son", "Daughter").forEach { n ->
            chips.addView(chip(n) { name.setText(n); if (n == "Mum") { senior = true; protect = true; paint() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(6) })
        }
        add(chips, top = 8)
        add(text("Your phone number (optional, so family can call you)", 17f, fg, bold = true, top = 20))
        val phone = input("e.g. 012-345 6789", FamilyLink.myPhone, InputType.TYPE_CLASS_PHONE)
        add(phone)
        seniorBtn = choice { senior = !senior; if (senior) protect = true; paint() }.also { add(it, top = 20) }
        protectBtn = choice { protect = !protect; paint() }.also { add(it, top = 10) }
        paint()
        add(button("Next", blue, top = 28) {
            val n = name.text.toString().trim()
            val id = FamilyLink.idForName(n)
            if (id.isEmpty()) { toast("Please enter your name"); return@button }
            FamilyLink.myName = n; FamilyLink.myRole = id; FamilyLink.myPhone = phone.text.toString()
            FamilyLink.senior = senior; FamilyLink.protect = protect
            if (FamilyLink.enabled) connect()
            show(Step.FAMILY)
        })
    }

    private fun family() {
        header(2, "Your family")
        val inv = pendingInvite
        if (inv != null) {
            add(cardOf(
                text("📩 Join ${inv.familyName.ifBlank { "this family" }}?", 22f, fg, bold = true),
                text("Invited by ${inv.inviterName.ifBlank { "a family member" }}. Their phone (key ${inv.inviterKey}) will be trusted.", 16f, muted, top = 6),
            ), top = 8)
            add(button("Join ${inv.familyName.ifBlank { "the family" }}", green, top = 16) { join(inv) })
            add(button("Not now", 0xFF30363D.toInt(), top = 10) { pendingInvite = null; show(Step.FAMILY) })
            return
        }
        add(text("Everyone in your family uses this app. One person creates the family, the others join with a QR code.", 18f, muted, top = 8))
        if (FamilyLink.familyCode != null) {
            add(cardOf(text("👪 You're in ${FamilyLink.familyName.ifBlank { "a family" }}", 20f, fg, bold = true),
                text("You can invite others now, or continue.", 16f, muted, top = 4)), top = 16)
            add(button("Invite someone (QR code)", blue, top = 12) { show(Step.INVITE) })
            add(button("Continue", 0xFF30363D.toInt(), top = 10) { show(Step.FACE) })
            return
        }
        add(bigOption("👑", "Create a new family", "I'll invite the others") { show(Step.CREATE) }, top = 20)
        add(bigOption("📷", "Join my family", "Scan the QR code on a family member's phone") {
            scan.launch(Intent(this, ScanActivity::class.java))
        }, top = 12)
        add(text("Got an invite link instead? Just tap it: it opens this app.", 15f, muted, center = true, top = 16))
        add(button("Skip for now", 0xFF30363D.toInt(), top = 20) { show(Step.FACE) })
    }

    private fun create() {
        header(2, "Create your family")
        add(text("Family name", 17f, fg, bold = true, top = 8))
        val fname = input("e.g. Tan family", FamilyLink.familyName.ifBlank { "${FamilyLink.myName}'s family" })
        add(fname)
        add(text("Relay address", 17f, fg, bold = true, top = 20))
        add(text("The server that passes messages between your family's phones. Ask whoever runs it.", 15f, muted, top = 2))
        val base = input("e.g. https://….trycloudflare.com", FamilyLink.relay.takeIf { it.isNotBlank() }?.let { Invite.baseOf(it) } ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        add(base)
        add(button("Create family", green, top = 28) {
            val b = base.text.toString().trim()
            if (b.isEmpty()) { toast("Please enter the relay address"); return@button }
            FamilyLink.createFamily(b, fname.text.toString().trim().ifBlank { "${FamilyLink.myName}'s family" })
            connect()
            show(Step.INVITE)
        })
    }

    private fun invite() {
        val inv = FamilyLink.currentInvite() ?: return show(Step.FAMILY)
        header(2, "Invite your family")
        add(text("On their phone: install AntiScam Guard → Join my family → scan this code. Or send them the link.", 17f, muted, top = 4))
        add(ImageView(this).apply {
            setImageBitmap(Qr.bitmap(inv.toLink(), px(260))); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(px(10), px(10), px(10), px(10))
        }, top = 16, center = true)
        add(text("Anyone with this code can join ${inv.familyName}: only share it with family.", 14f, muted, center = true, top = 10))
        add(button("📤 Send invite link (WhatsApp…)", blue, top = 16) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                "Join ${inv.familyName} on AntiScam Guard, so we can check each other's video calls for deepfake scams: ${inv.toLink()}"), "Send invite"))
        })
        add(button("Next", 0xFF30363D.toInt(), top = 10) { show(Step.FACE) })
    }

    private fun face() {
        header(3, "Add your face")
        val mine = FaceStore.owner(this, FamilyLink.myName)
        add(text("So your family's phones can recognise you on a video call. Only a set of numbers is kept and shared, never a photo.", 18f, muted, top = 8))
        if (mine != null) {
            add(cardOf(text("✅ Your face is added (${mine.embeddings.size} samples)", 20f, fg, bold = true),
                text(when {
                    sharedWith > 0 -> "✓ Sent to $sharedWith family phone(s). Others get it when they come online."
                    else -> "It's sent to your family's phones automatically, as soon as they're online."
                }, 16f, muted, top = 4)), top = 16)
            add(button("Next", blue, top = 20) { show(Step.PROTECT) })
            add(button("Take them again", 0xFF30363D.toInt(), top = 10) { takeSelfies() })
        } else {
            add(text("🤳", 72f, fg, center = true, top = 24))
            add(button("📷 Take 3 quick selfies", blue, top = 16) { takeSelfies() })
            add(button("Skip for now", 0xFF30363D.toInt(), top = 10) { show(Step.PROTECT) })
        }
    }

    private fun takeSelfies() {
        // replace any old samples of "me", then take fresh ones
        FaceStore.load(this).firstOrNull { it.isOwner }?.let { FaceStore.remove(this, it) }
        selfies.launch(Intent(this, EnrolActivity::class.java)
            .putExtra(EnrolActivity.EXTRA_NAME, FamilyLink.myName).putExtra(EnrolActivity.EXTRA_OWNER, true))
    }

    private fun protect() {
        header(4, if (FamilyLink.protect) "Turn on protection" else "Allow family alerts")
        add(text(if (FamilyLink.protect) "Three switches, then you're protected." else "So family questions and alerts can reach you.", 18f, muted, top = 8))
        val notif = Notifier.canPost(this)
        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        if (FamilyLink.protect) add(checkRow(isWatcherOn(), "Check video calls",
            "Tap Turn on, then AntiScam Guard → On.") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, top = 16)
        add(checkRow(notif, "Show warnings and questions", "Lets alerts pop up, even over other apps.") {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }, top = 12)
        add(checkRow(battery, "Keep running in the background", "So battery saver doesn't switch it off.") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }, top = 12)
        val allOk = notif && battery && (!FamilyLink.protect || isWatcherOn())
        add(button(if (allOk) "Finish" else "Finish later", if (allOk) green else 0xFF30363D.toInt(), top = 28) { show(Step.DONE) })
    }

    private fun done() {
        FamilyLink.setupDone = true
        body.gravity = Gravity.CENTER_HORIZONTAL
        val ok = Notifier.canPost(this) && (!FamilyLink.protect || isWatcherOn())
        add(text(if (ok) "✅" else "🛡️", 84f, fg, center = true, top = 40))
        add(text(if (!ok) "Almost there" else if (FamilyLink.protect) "You're protected" else "Family alerts are on", 32f, fg, bold = true, center = true, top = 8))
        add(text(when {
            !ok -> "The home screen shows what's still missing, with one tap to fix it."
            FamilyLink.protect -> "When a video call looks like someone in your family, we'll ask them on their own phone, and warn you over the call."
            else -> "If someone who looks like you video-calls your family, you'll be asked here to confirm."
        }, 19f, muted, center = true, top = 12))
        add(button("Go to home", blue, top = 36) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
        })
    }

    // ------------------------------------------------------------------ actions

    private fun join(inv: Invite) {
        FamilyLink.joinFamily(inv)
        pendingInvite = null
        connect()
        toast("Joined ${inv.familyName}")
        show(Step.FACE)
    }

    private fun connect() { FamilyService.start(this); FamilyLink.restart() }

    private fun isWatcherOn(): Boolean {
        val me = ComponentName(this, CallWatcherService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ------------------------------------------------------------------ small UI kit (big, simple, high contrast)

    private fun header(n: Int, title: String) {
        body.gravity = Gravity.NO_GRAVITY
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 1..4) dots.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = px(4).toFloat(); setColor(if (i <= n) blue else 0xFF30363D.toInt()) }
        }, LinearLayout.LayoutParams(0, px(6), 1f).apply { marginEnd = px(6) })
        add(dots)
        add(text("Step $n of 4", 14f, muted, top = 10))
        add(text(title, 30f, fg, bold = true, top = 2))
    }

    private fun add(v: View, top: Int = 0, center: Boolean = false) {
        body.addView(v, LinearLayout.LayoutParams(if (center) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top); if (center) gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); setPadding(0, px(top), 0, 0)
        if (bold) setTypeface(typeface, Typeface.BOLD); if (center) gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }

    private fun input(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) = EditText(this).apply {
        this.hint = hint; setText(value); inputType = type; textSize = 20f; setTextColor(fg); setHintTextColor(0xFF6E7681.toInt())
        setPadding(px(14), px(14), px(14), px(14))
        background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(card); setStroke(px(1), 0xFF30363D.toInt()) }
    }

    private fun button(label: String, color: Int, top: Int = 0, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(px(16), px(18), px(16), px(18))
        background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
    }.also { if (top > 0) it.tag = top }.let { b -> LinearLayout(this).apply { addView(b, LinearLayout.LayoutParams(-1, -2)); setPadding(0, px(top), 0, 0) } }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(fg); gravity = Gravity.CENTER; setPadding(px(6), px(10), px(6), px(10))
        background = GradientDrawable().apply { cornerRadius = px(20).toFloat(); setColor(card); setStroke(px(1), 0xFF30363D.toInt()) }
        setOnClickListener { onClick() }
    }

    private fun choice(onClick: () -> Unit) = TextView(this).apply { textSize = 18f; setPadding(px(16), px(14), px(16), px(14)); setOnClickListener { onClick() } }

    private fun toggle(v: TextView, on: Boolean, title: String, sub: String) {
        v.text = "${if (on) "☑" else "☐"}  $title\n$sub"
        v.setTextColor(if (on) fg else muted)
        v.background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(if (on) 0xFF1E3A8A.toInt() else card); setStroke(px(2), if (on) 0xFF60A5FA.toInt() else 0xFF30363D.toInt()) }
    }

    private fun bigOption(icon: String, title: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(18), px(18), px(18), px(18))
        background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(card); setStroke(px(2), 0xFF30363D.toInt()) }
        addView(text(icon, 36f, fg))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0)
            addView(text(title, 21f, fg, bold = true)); addView(text(sub, 16f, muted, top = 2))
        })
        setOnClickListener { onClick() }
    }

    private fun cardOf(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(px(18), px(16), px(18), px(16))
        background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(card) }
        views.forEach { addView(it) }
    }

    private fun checkRow(ok: Boolean, title: String, sub: String, fix: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(card); setStroke(px(2), if (ok) green else 0xFFF59E0B.toInt()) }
        addView(text(if (ok) "✅" else "⬜", 26f, fg))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, px(8), 0)
            addView(text(title, 19f, fg, bold = true)); addView(text(if (ok) "Done" else sub, 14f, muted, top = 2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!ok) addView(TextView(context).apply {
            text = "Turn on"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(blue) }
            setOnClickListener { fix() }
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
