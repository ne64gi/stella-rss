package net.fuyumori.stellarss

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

const val INK = 0xFFF4F7FC.toInt()
const val MUTED = 0xFFA5B4C8.toInt()
const val ACCENT = 0xFF9BD6EC.toInt()
const val DIVIDER = 0xFF243041.toInt()
const val SURFACE = 0xFF152235.toInt()
fun Context.dp(value: Int) = (resources.displayMetrics.density * value).toInt()
fun Context.label(value: String, size: Float = 16f, color: Int = INK) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color)
}
fun Context.button(value: String, click: () -> Unit) = Button(this).apply {
    text = value; isAllCaps = false; setTextColor(ACCENT); setOnClickListener { click() }
}
fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
fun Context.field(hintText: String, value: String = "") = EditText(this).apply {
    hint = hintText; setText(value); setTextColor(INK); setHintTextColor(MUTED); maxLines = 3
}
fun View.rounded(color: Int = SURFACE, radius: Float = 16f) {
    background = GradientDrawable().apply { setColor(color); cornerRadius = context.dp(radius.toInt()).toFloat() }
}
fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

open class Screen : ComponentActivity() {
    lateinit var root: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = column().apply { setBackgroundColor(Color.rgb(11, 18, 32)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
    fun heading(title: String, subtitle: String? = null, back: Boolean = true) {
        val line = row().apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        if (back) line.addView(button("戻る") { finish() }, LinearLayout.LayoutParams(dp(72), dp(48)))
        val words = column()
        words.addView(label(title, 26f).apply { setTypeface(null, Typeface.BOLD) })
        subtitle?.let { words.addView(label(it, 12f, MUTED)) }
        line.addView(words, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(line)
    }
    fun scrollColumn(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(24)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return content
    }
}

fun Context.iconButton(glyph: String, description: String, click: () -> Unit) = label(glyph, 24f, MUTED).apply {
    contentDescription = description; gravity = android.view.Gravity.CENTER
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    isClickable = true; isFocusable = true
    val value = android.util.TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
    if (value.resourceId != 0) setBackgroundResource(value.resourceId)
    setOnClickListener { click() }
}
