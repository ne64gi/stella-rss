package net.fuyumori.stellarss

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Debug-only, isolated standard AppWidgetHost surface. Never modifies launcher state. */
class FixtureHostActivity : Screen() {
    lateinit var surface: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        heading("Stella RSS", "独立ウィジェット検証用ホスト", back = false)
        surface = FrameLayout(this)
        root.addView(surface, LinearLayout.LayoutParams(dp(350), dp(400)))
    }
}
