package io.github.lejw0925.virtualscreen.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Disposable controls for the phone-initiated backend check, on its own display. */
class BackendProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (display?.displayId == android.view.Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "MCP"
            textSize = 24f
            setTextColor(android.graphics.Color.BLACK)
        }
        val counter = Button(this).apply {
            contentDescription = COUNTER
            text = "0"
            var count = 0
            setOnClickListener { text = (++count).toString() }
            setOnLongClickListener { contentDescription = LONG_PRESSED; true }
        }
        val editor = EditText(this).apply {
            contentDescription = EDITOR
            isSingleLine = true
            setTextColor(android.graphics.Color.BLACK)
            setSelectAllOnFocus(true)
        }
        content.addView(title)
        content.addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        content.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        val scroll = ScrollView(this).apply { contentDescription = SCROLL }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(30) { index ->
            rows.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 18f
                setTextColor(android.graphics.Color.BLACK)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
        scroll.addView(rows)
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        editor.clearFocus()
        content.isFocusableInTouchMode = true
        content.requestFocus()
    }

    companion object {
        const val COUNTER = "avs_probe_counter"
        const val LONG_PRESSED = "avs_probe_long_pressed"
        const val EDITOR = "avs_probe_editor"
        const val SCROLL = "avs_probe_scroll"
    }
}
