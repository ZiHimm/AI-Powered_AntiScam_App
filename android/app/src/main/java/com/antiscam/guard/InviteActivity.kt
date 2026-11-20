package com.antiscam.guard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** "Invite your family": QR code + copy/share the invite link (like a Slack invite). */
class InviteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyLink.init(this)
        val inv = FamilyLink.currentInvite() ?: run {
            Toast.makeText(this, "Create or join a family first", Toast.LENGTH_LONG).show(); finish(); return
        }
        val link = inv.toLink()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(24), px(24), px(24), px(24))
        }
        root.addView(TextView(this).apply {
            text = "Invite to ${inv.familyName.ifBlank { "your family" }}"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE6EDF3.toInt()); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "On their phone: open AntiScam Guard → Join family → scan this code. Or send them the link."
            textSize = 16f; setTextColor(0xFFB1BAC4.toInt()); gravity = Gravity.CENTER; setPadding(0, px(8), 0, px(16))
        })
        root.addView(ImageView(this).apply { setImageBitmap(qr(link, px(300))); setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(px(12), px(12), px(12), px(12)) })
        root.addView(TextView(this).apply {
            text = "Invited by ${inv.inviterName} · key ${inv.inviterKey}\nAnyone with this code can join the family: only share it with family."
            textSize = 13f; setTextColor(0xFF8B949E.toInt()); gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(12))
        })
        root.addView(Button(this).apply {
            text = "📋 Copy invite link"; setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Family invite", link))
                Toast.makeText(this@InviteActivity, "Invite link copied", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "📤 Share invite (WhatsApp…)"; setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Join ${inv.familyName.ifBlank { "our family" }} on AntiScam Guard, so we can check each other's video calls for deepfake scams: $link"),
                    "Send invite"))
            }
        })
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0D1117.toInt()) })
    }

    private fun qr(text: String, size: Int): Bitmap = Qr.bitmap(text, size)
}
