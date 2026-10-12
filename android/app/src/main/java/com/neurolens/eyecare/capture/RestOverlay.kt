package com.neurolens.eyecare.capture

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A small, dismissible non-focusable card; no full-screen touch layer. */
class RestOverlay(private val context: Context, private val rest: () -> Unit,
    private val snooze: () -> Unit, private val dismiss: () -> Unit,
    private val disable: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    val showing get() = view != null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun show(title: String, message: String): Boolean {
        if (showing) return true
        if (!Settings.canDrawOverlays(context)) return false
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 252, 248)); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(136, 185, 157))
            }
            elevation = dp(8).toFloat()
            addView(TextView(context).apply {
                text = title
                textSize = 15f; setTextColor(Color.rgb(23, 51, 48))
            })
            addView(TextView(context).apply {
                text = message
                textSize = 12f; setTextColor(Color.rgb(83, 107, 101))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                fun button(label: String, action: () -> Unit) {
                    addView(Button(context).apply {
                        text = label; textSize = 11f; isAllCaps = false
                        setOnClickListener { action() }
                    })
                }
                button("20초 쉬기", rest); button("10분 뒤에", snooze)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                fun button(label: String, action: () -> Unit) {
                    addView(Button(context).apply {
                        text = label; textSize = 11f; isAllCaps = false
                        setOnClickListener { action() }
                    })
                }
                button("닫기", dismiss); button("알림 끄기", disable)
            })
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(28)
        }
        return try { windows.addView(body, params); view = body; true }
        catch (_: Exception) { false }
    }

    fun hide() {
        val old = view ?: return
        view = null
        runCatching { windows.removeViewImmediate(old) }
    }
}
