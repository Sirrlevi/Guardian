package com.sirrlevi.guardian.child

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sirrlevi.guardian.core.BlockReason
import com.sirrlevi.guardian.core.Decision
import com.sirrlevi.guardian.core.Durations

/**
 * The full-screen "time's up" / "blocked" screen. It is an ordinary, visible overlay window
 * (needs the user-granted "Display over other apps" permission) and always offers a way home.
 * All methods must be called on the main thread.
 */
internal class BlockOverlay(
    private val context: Context,
    private val onRequestMore: (packageName: String, appName: String, reason: BlockReason) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    var shownPackage: String? = null
        private set
    private var shownReason = BlockReason.NONE

    fun show(packageName: String, appName: String, decision: Decision) {
        if (root != null && shownPackage == packageName && shownReason == decision.reason) return
        hide()
        val view = build(packageName, appName, decision)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            windowManager.addView(view, params)
            root = view
            shownPackage = packageName
            shownReason = decision.reason
        } catch (_: Exception) {
            // The overlay permission was withdrawn; the permission centre tells the child.
        }
    }

    fun hide() {
        root?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        root = null
        shownPackage = null
        shownReason = BlockReason.NONE
    }

    fun goHome() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    private class BackAwareFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun build(packageName: String, appName: String, decision: Decision): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val accent = when (decision.reason) {
            BlockReason.DAILY_LIMIT -> Color.parseColor("#FFC16B")
            BlockReason.SCHEDULE -> Color.parseColor("#7FB2FF")
            BlockReason.PAUSED -> Color.parseColor("#C3A6FF")
            else -> Color.parseColor("#FF8A8A")
        }
        val title: String
        val detail: String
        when (decision.reason) {
            BlockReason.DAILY_LIMIT -> {
                title = "Time's up for $appName"
                detail = "You've used your " + Durations.compact(decision.limitSec ?: 0) +
                    " for today. It opens again tomorrow."
            }
            BlockReason.SCHEDULE -> {
                title = decision.scheduleName.ifBlank { "Quiet time" }
                detail = "$appName isn't available right now. Only the apps your parent chose can be used."
            }
            BlockReason.PAUSED -> {
                title = "Apps are paused"
                detail = "Your parent paused apps for a little while."
            }
            else -> {
                title = "$appName is blocked"
                detail = "Your parent has turned this app off."
            }
        }

        val frame = BackAwareFrame(context) { goHome() }
        frame.setBackgroundColor(Color.parseColor("#0B1220"))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val icon = ImageView(context).apply {
            try { setImageDrawable(context.packageManager.getApplicationIcon(packageName)) } catch (_: Exception) { }
            alpha = 0.55f
        }
        column.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(24) })
        column.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        column.addView(TextView(context).apply {
            text = detail
            setTextColor(Color.parseColor("#A9B8CC"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        })
        column.addView(TextView(context).apply {
            text = "Go to home screen"
            setTextColor(Color.parseColor("#0B1220"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(28).toFloat() }
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        if (decision.reason != BlockReason.PAUSED) {
            val ask = TextView(context).apply {
                text = "Ask my parent for more time"
                setTextColor(accent)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(18), dp(16), dp(18))
            }
            ask.setOnClickListener {
                ask.text = "Request sent to your parent ✓"
                ask.setTextColor(Color.parseColor("#A9B8CC"))
                ask.isEnabled = false
                onRequestMore(packageName, appName, decision.reason)
            }
            column.addView(ask, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        frame.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return frame
    }
}
