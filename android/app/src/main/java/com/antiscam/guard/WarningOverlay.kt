package com.antiscam.guard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Big warning drawn on top of the video call. Accessibility services may use TYPE_ACCESSIBILITY_OVERLAY,
 * so no "draw over other apps" permission is needed. Only touches inside the card are captured;
 * the call underneath keeps working.
 */
class WarningOverlay(private val svc: AccessibilityService) {

    enum class Level { INFO, WARN, DANGER }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val dp = svc.resources.displayMetrics.density

    val isShowing get() = view != null

    /**
     * DANGER = large red card in the middle that stays until dismissed.
     * INFO / WARN = banner at the top that hides itself after a while.
     */
    fun show(level: Level, title: String, body: String, onHangUp: (() -> Unit)? = null) {
        hide()
        val danger = level == Level.DANGER
        val bg = when (level) { Level.DANGER -> 0xFFDC2626; Level.WARN -> 0xFFFEF3C7; Level.INFO -> 0xFFF1F5F9 }.toInt()
        val fg = if (danger) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(24).toFloat(); setColor(bg)
                if (!danger) setStroke(px(3), if (level == Level.WARN) 0xFFF59E0B.toInt() else 0xFF94A3B8.toInt())
            }
            elevation = px(12).toFloat()
        }
        card.addView(text("🛡 AntiScam Guard", 13f, fg, alpha = 0.75f))
        card.addView(text(title, if (danger) 28f else 21f, fg, bold = true, top = 4))
        card.addView(text(body, if (danger) 19f else 16f, fg, top = 6))

        val buttons = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(14), 0, 0)
        }
        if (onHangUp != null) {
            buttons.addView(button("HANG UP", if (danger) 0xFFFFFFFF.toInt() else 0xFFDC2626.toInt(),
                if (danger) 0xFFDC2626.toInt() else 0xFFFFFFFF.toInt()) { onHangUp() }, weighted())
            buttons.addView(View(svc), LinearLayout.LayoutParams(px(10), 1))
        }
        buttons.addView(button("Dismiss", if (danger) 0x33FFFFFF else 0xFFE2E8F0.toInt(), fg) { hide() }, weighted())
        card.addView(buttons)

        val wrap = LinearLayout(svc).apply { setPadding(px(12), 0, px(12), 0); addView(card) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (danger) Gravity.CENTER else Gravity.TOP
            y = if (danger) 0 else px(56)
        }
        try {
            wm.addView(wrap, lp)
            view = wrap
        } catch (t: Throwable) {
            LiveLog.add("Couldn't draw warning over the call: ${t.message}")
        }
        if (!danger) main.postDelayed({ hide() }, 15_000)
    }

    /** Replace the body text of the visible card (e.g. "tap the red button to hang up"). */
    fun updateBody(body: String) {
        val card = (view as? LinearLayout)?.getChildAt(0) as? LinearLayout ?: return
        (card.getChildAt(2) as? TextView)?.text = body
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, alpha: Float = 1f, top: Int = 0) =
        TextView(svc).apply {
            text = s; textSize = sp; setTextColor(color); this.alpha = alpha
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(top), 0, 0)
        }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(svc).apply {
        text = label; textSize = 18f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(px(12), px(14), px(12), px(14))
        background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(bg) }
        setOnClickListener { onClick() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun px(v: Int) = (v * dp).toInt()
}
