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
 * Big card drawn on top of the video call. Accessibility services may use TYPE_ACCESSIBILITY_OVERLAY,
 * so no "draw over other apps" permission is needed. Only touches inside the card are captured;
 * the call underneath keeps working.
 */
class WarningOverlay(private val svc: AccessibilityService) {

    enum class Level { INFO, OK, WARN, DANGER }
    enum class Style { PRIMARY, GOOD, BAD, NEUTRAL }
    class Action(val label: String, val style: Style = Style.NEUTRAL, val onClick: () -> Unit)

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var bodyView: TextView? = null
    private val dp = svc.resources.displayMetrics.density

    /**
     * DANGER = large red card in the middle. Others = card at the top.
     * [autoHideMs] = 0 keeps it until a button is pressed.
     */
    fun show(
        level: Level, title: String, body: String,
        actions: List<Action> = listOf(Action("Dismiss") { hide() }),
        autoHideMs: Long = if (level == Level.DANGER) 0 else 15_000,
    ) {
        hide()
        val dark = level == Level.DANGER
        val (bg, stroke) = when (level) {
            Level.DANGER -> 0xFFDC2626 to 0
            Level.WARN -> 0xFFFEF3C7 to 0xFFF59E0B
            Level.OK -> 0xFFDCFCE7 to 0xFF16A34A
            Level.INFO -> 0xFFF1F5F9 to 0xFF94A3B8
        }
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(16))
            background = GradientDrawable().apply {
                cornerRadius = px(24).toFloat(); setColor(bg.toInt())
                if (stroke != 0L) setStroke(px(3), stroke.toInt())
            }
            elevation = px(12).toFloat()
        }
        card.addView(text("🛡 AntiScam Guard", 13f, fg, alpha = 0.75f))
        card.addView(text(title, if (dark) 28f else 21f, fg, bold = true, top = 4))
        bodyView = text(body, if (dark) 19f else 16f, fg, top = 6).also { card.addView(it) }

        // up to 2 buttons per row
        actions.chunked(2).forEach { row ->
            val line = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, 0) }
            row.forEachIndexed { i, a ->
                if (i > 0) line.addView(View(svc), LinearLayout.LayoutParams(px(10), 1))
                line.addView(button(a, dark), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            card.addView(line)
        }

        val wrap = LinearLayout(svc).apply { setPadding(px(12), 0, px(12), 0); addView(card) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (dark) Gravity.CENTER else Gravity.TOP
            y = if (dark) 0 else px(56)
        }
        try {
            wm.addView(wrap, lp)
            view = wrap
        } catch (t: Throwable) {
            LiveLog.add("Couldn't draw warning over the call: ${t.message}")
        }
        if (autoHideMs > 0) main.postDelayed({ hide() }, autoHideMs)
    }

    /** Replace the body text of the visible card (e.g. a countdown). */
    fun updateBody(body: String) { bodyView?.text = body }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null; bodyView = null
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, alpha: Float = 1f, top: Int = 0) =
        TextView(svc).apply {
            text = s; textSize = sp; setTextColor(color); this.alpha = alpha
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(top), 0, 0)
        }

    private fun button(a: Action, onDark: Boolean) = TextView(svc).apply {
        val (bg, fg) = when (a.style) {
            Style.BAD -> if (onDark) 0xFFFFFFFF to 0xFFDC2626 else 0xFFDC2626 to 0xFFFFFFFF
            Style.GOOD -> if (onDark) 0xFFFFFFFF to 0xFF16A34A else 0xFF16A34A to 0xFFFFFFFF
            Style.PRIMARY -> 0xFF2563EB to 0xFFFFFFFF
            Style.NEUTRAL -> if (onDark) 0x33FFFFFF.toLong() to 0xFFFFFFFF else 0xFFE2E8F0 to 0xFF111111
        }
        text = a.label; textSize = 17f; setTextColor(fg.toInt()); setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(px(10), px(13), px(10), px(13))
        background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(bg.toInt()) }
        setOnClickListener { a.onClick() }
    }

    private fun px(v: Int) = (v * dp).toInt()
}
