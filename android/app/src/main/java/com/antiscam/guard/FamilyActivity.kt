package com.antiscam.guard

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Family mode screen (Son's / Daughter's phone). Native counterpart of Family Verify's member screen:
 * "Are you calling <them>?" with Yes/No behind the fingerprint, and signed guardian alerts, for any family member.
 */
class FamilyActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var settings: LinearLayout
    private lateinit var fixButton: TextView
    private var shown = -1L
    private val dp get() = resources.displayMetrics.density
    private val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyLink.init(this); MemberHub.init(this); Notifier.ensureChannels(this)
        if (FamilyLink.myRole == null) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (FamilyLink.enabled && FamilyLink.relay.isNotBlank()) FamilyService.start(this)
        else settings.visibility = View.VISIBLE
    }

    override fun onResume() { super.onResume(); shown = -1; main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll); super.onPause() }

    private val poll = object : Runnable {
        override fun run() {
            val v = MemberHub.version * 1_000_003 + FamilyLink.version
            if (v != shown || MemberHub.cards.any { it.kind == MemberHub.Kind.PING && it.pingState == MemberHub.PingState.OPEN }) {
                shown = v; render()
            }
            main.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(20), px(20), px(20)) }
        val me = FamilyLink.nameOf(FamilyLink.myRole ?: "son")
        root.addView(text("📬 Family questions & alerts", 26f, 0xFFE6EDF3.toInt(), bold = true))
        root.addView(text("$me's phone · AntiScam Guard", 15f, 0xFF8B949E.toInt()))
        status = text("", 17f, 0xFFE6EDF3.toInt(), top = 12)
        root.addView(status)
        fixButton = bigButton("", 0xFFF59E0B.toInt()) {}.apply { visibility = View.GONE }
        root.addView(fixButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(12), 0, 0) }
        root.addView(list)

        root.addView(Button(this).apply {
            text = "⚙ Settings"; setOnClickListener { settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })

        settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        settings.addView(text("Relay address (same on every family phone)", 15f, 0xFFC9D1D9.toInt(), top = 8))
        val addr = EditText(this).apply {
            setText(FamilyLink.relay); hint = "https://…fly.dev/f/CODE or 192.168.1.104:8000"
            setTextColor(0xFFE6EDF3.toInt()); setHintTextColor(0xFF6E7681.toInt()); setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
        }
        settings.addView(addr)
        settings.addView(Button(this).apply {
            text = "Connect"; setOnClickListener {
                FamilyLink.relay = addr.text.toString().trim()
                if (FamilyLink.relay.isBlank()) return@setOnClickListener
                FamilyLink.enabled = true
                FamilyService.stop(this@FamilyActivity); FamilyService.start(this@FamilyActivity)
                settings.visibility = View.GONE
            }
        })
        settings.addView(Button(this).apply {
            text = "Disconnect"; setOnClickListener { FamilyLink.enabled = false; FamilyService.stop(this@FamilyActivity) }
        })
        settings.addView(Button(this).apply {
            text = "Forget paired family keys"; setOnClickListener {
                AlertDialog.Builder(this@FamilyActivity).setMessage("Forget the family's paired keys? Do this if a family phone was reinstalled or replaced.")
                    .setPositiveButton("Forget") { _, _ -> FamilyLink.forgetPins() }.setNegativeButton("Cancel", null).show()
            }
        })
        settings.addView(Button(this).apply {
            text = "Home & settings"; setOnClickListener {
                startActivity(Intent(this@FamilyActivity, MainActivity::class.java))
            }
        })
        root.addView(settings)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0D1117.toInt()) })
    }

    private fun render() {
        val online = FamilyLink.members.values.filter { it.online }.map { it.name.ifBlank { FamilyLink.nameOf(it.id) } }
        status.text = when {
            !FamilyLink.enabled -> "⚪ Not connected. Open ⚙ Settings and enter the relay address."
            !FamilyLink.connected -> "🟠 ${FamilyLink.status}"
            FamilyLink.keyMismatch.isNotEmpty() -> "⚠️ ${FamilyLink.keyMismatch.joinToString { FamilyLink.nameOf(it) }}: different key than before. " +
                "Their messages are blocked until you tap ⚙ → Forget paired family keys."
            online.isNotEmpty() -> "🟢 Connected · online: ${online.joinToString(", ")}"
            else -> "🟡 Connected · no other family phone online"
        }
        // the one thing that would stop questions/alerts from reaching this phone, with a one-tap fix
        val notifOk = Notifier.canPost(this)
        val batteryOk = getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true
        when {
            !notifOk -> fix("⚠️ Allow notifications, or family questions can't pop up") {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
            !batteryOk -> fix("⚠️ Keep alerts on: don't let battery saver stop them") {
                try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))) }
                catch (_: Throwable) { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            else -> fixButton.visibility = View.GONE
        }
        list.removeAllViews()
        if (MemberHub.cards.isEmpty()) list.addView(text("Nothing yet. If someone who looks like you video-calls a family member, you'll be asked here.", 16f, 0xFF8B949E.toInt(), top = 8))
        for (c in MemberHub.cards.take(20)) list.addView(cardView(c))
    }

    private fun cardView(c: MemberHub.Card): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(14)) }
        val (bg, stroke) = when {
            c.kind == MemberHub.Kind.BLOCKED -> 0xFF3B1D1D to 0xFFF87171
            c.kind == MemberHub.Kind.PING && c.pingState == MemberHub.PingState.OPEN -> 0xFF1E3A8A to 0xFF60A5FA
            c.kind == MemberHub.Kind.ALERT && c.alert?.optString("level") == "urgent" -> 0xFF450A0A to 0xFFEF4444
            else -> 0xFF161B22 to 0xFF30363D
        }
        box.background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(bg.toInt()); setStroke(px(2), stroke.toInt()) }
        val white = 0xFFF0F6FC.toInt(); val muted = 0xFFB1BAC4.toInt()
        val time = hhmmss.format(Date(c.at))
        when (c.kind) {
            MemberHub.Kind.PING -> {
                val req = c.req!!
                box.addView(text("🛡 AntiScam Guard · $time", 13f, muted))
                box.addView(text("Are you calling ${c.calleeName} right now?", 24f, white, bold = true, top = 4))
                when (c.pingState) {
                    MemberHub.PingState.OPEN, MemberHub.PingState.ANSWERING -> {
                        val left = ((req.optLong("expires") - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        box.addView(text("Someone who looks like you is video-calling ${c.calleeName}. (${left}s to answer)", 17f, white, top = 6))
                        if (c.pingState == MemberHub.PingState.OPEN) box.addView(row(
                            bigButton("Yes, it's me", 0xFF16A34A.toInt()) { confirmThen(c, "yes") },
                            bigButton("No, not me", 0xFFDC2626.toInt()) { confirmThen(c, "no") }))
                        else box.addView(text("🔒 Confirming…", 16f, muted, top = 8))
                        box.addView(text("🔒 Fingerprint required · request ${req.optString("nonce").take(8)}…", 13f, muted, top = 8))
                        box.addView(text(if (c.unsigned) "⚠️ Not signed (older app). Only answer if ${c.calleeName} really is on a call."
                                         else "✔ Signed by ${c.calleeName}'s phone", 13f, if (c.unsigned) 0xFFFBBF24.toInt() else 0xFF86EFAC.toInt(), top = 4))
                    }
                    MemberHub.PingState.ANSWERED -> box.addView(text(c.body, 17f, white, top = 6))
                    MemberHub.PingState.EXPIRED -> box.addView(text("Expired. ${c.calleeName} was told you didn't reply.", 17f, muted, top = 6))
                    MemberHub.PingState.CANCELLED -> box.addView(text("The call ended before you replied.", 17f, muted, top = 6))
                }
            }
            MemberHub.Kind.ALERT -> {
                val urgent = c.alert?.optString("level") == "urgent"
                box.addView(text(if (urgent) "🚨 AntiScam Guard · URGENT · $time" else "🛡 AntiScam Guard · ${c.calleeName} · $time", 13f, muted))
                box.addView(text(c.title, 21f, white, bold = true, top = 4))
                if (c.body.isNotEmpty()) box.addView(text(c.body, 16f, white, top = 4))
                if (urgent) box.addView(row(
                    bigButton(if (c.called) "📞 Calling ${c.calleeName}…" else "📞 Call ${c.calleeName} now", 0xFF16A34A.toInt(), enabled = !c.called) {
                        MemberHub.callCallee(c)
                        FamilyLink.members[c.from]?.phone?.takeIf { it.isNotBlank() }?.let { n ->
                            startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$n")))
                        }
                    },
                    bigButton(if (c.warned) "✓ Sent to ${c.calleeName}" else "⚠️ Tell ${c.calleeName} to hang up", 0xFFDC2626.toInt(), enabled = !c.warned) { MemberHub.warnCallee(c) }))
                c.shared.forEach { box.addView(text(it, 15f, white, top = 6)) }
                box.addView(text("✔ Signed by ${c.calleeName}'s phone · ${c.sig.take(8)}…", 13f, 0xFF86EFAC.toInt(), top = 8))
            }
            MemberHub.Kind.LOG -> box.addView(text("📝 ${c.title} · $time", 15f, muted))
            MemberHub.Kind.BLOCKED -> {
                box.addView(text("🛡 AntiScam Guard · blocked · $time", 13f, muted))
                box.addView(text(c.title, 21f, 0xFFFCA5A5.toInt(), bold = true, top = 4))
                box.addView(text(c.body, 16f, white, top = 4))
            }
        }
        return LinearLayout(this).apply { setPadding(0, px(6), 0, px(6)); addView(box, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun fix(label: String, action: () -> Unit) {
        fixButton.text = label; fixButton.visibility = View.VISIBLE; fixButton.setOnClickListener { action() }
    }

    // ------------------------------------------------------------------ fingerprint before Yes/No

    private fun confirmThen(c: MemberHub.Card, answer: String) {
        c.pingState = MemberHub.PingState.ANSWERING; shown = -1
        val reason = if (answer == "yes") "Confirm it is you calling ${c.calleeName}" else "Confirm it is NOT you"
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            // no fingerprint/PIN set up on this phone: confirm by tap instead (logged, so it's visible on the monitor)
            AlertDialog.Builder(this).setTitle(reason).setMessage("This phone has no fingerprint or screen lock set up.")
                .setPositiveButton(if (answer == "yes") "Yes, it's me" else "No, not me") { _, _ ->
                    FamilyLink.log("warn", "No fingerprint set up on ${FamilyLink.nameOf(FamilyLink.myRole ?: "")}'s phone: confirmed by tap")
                    MemberHub.answer(c, answer)
                }
                .setNegativeButton("Cancel") { _, _ -> reopen(c) }.setOnCancelListener { reopen(c) }.show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(reason).setAllowedAuthenticators(auth)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }.build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                FamilyLink.log("sec", "${FamilyLink.nameOf(FamilyLink.myRole ?: "")}'s phone: ✓ fingerprint verified by Android")
                MemberHub.answer(c, answer)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { reopen(c) }
        }).authenticate(info)
    }

    private fun reopen(c: MemberHub.Card) {
        if (c.pingState == MemberHub.PingState.ANSWERING) c.pingState = MemberHub.PingState.OPEN
        shown = -1
    }

    // ------------------------------------------------------------------ small view helpers

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); setPadding(0, px(top), 0, 0)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun bigButton(label: String, color: Int, enabled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER; setPadding(px(8), px(14), px(8), px(14)); isEnabled = enabled; alpha = if (enabled) 1f else 0.6f
        background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(color) }
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, px(12), 0, 0)
        addView(a, LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(this@FamilyActivity), LinearLayout.LayoutParams(px(10), 1))
        addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun px(v: Int) = (v * dp).toInt()
}
