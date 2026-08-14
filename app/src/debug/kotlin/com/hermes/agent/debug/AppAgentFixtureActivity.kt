package com.hermes.agent.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Deterministic debug-only screen used by the on-device AppAgent smoke test. */
@SuppressLint("SetTextI18n") // Test fixture text is intentionally stable, not user-facing.
class AppAgentFixtureActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var textField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        statusView = TextView(this).apply {
            text = STATUS_IDLE
            contentDescription = "AppAgent test status"
        }
        val tapTarget = Button(this).apply {
            text = "Tap target"
            contentDescription = TAP_TARGET_DESCRIPTION
            setOnClickListener { statusView.text = STATUS_TAPPED }
        }
        textField = EditText(this).apply {
            hint = "Input target"
            contentDescription = TEXT_TARGET_DESCRIPTION
            isSingleLine = true
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(
                    tapTarget,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    textField,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    statusView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    fun statusText(): String = statusView.text.toString()

    fun enteredText(): String = textField.text.toString()

    companion object {
        const val TAP_TARGET_DESCRIPTION = "AppAgent tap target"
        const val TEXT_TARGET_DESCRIPTION = "AppAgent text target"
        const val STATUS_IDLE = "Idle"
        const val STATUS_TAPPED = "Tapped"
        const val TYPED_TEXT = "Hermes device test"

    }
}
