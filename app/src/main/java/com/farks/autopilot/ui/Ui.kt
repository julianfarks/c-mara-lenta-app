package com.farks.autopilot.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Utilidades mínimas para construir pantallas por código (sin XML ni librerías de UI extra). */
internal object Ui {
    val BG = Color.parseColor("#0E1116")
    val CARD = Color.parseColor("#171C24")
    val TEXT = Color.parseColor("#E6EAF0")
    val MUTED = Color.parseColor("#9AA6B6")
    val GREEN = Color.parseColor("#2BD97C")
    val RED = Color.parseColor("#E5484D")
    val AMBER = Color.parseColor("#F5A524")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun scroll(c: Context, content: LinearLayout): ScrollView = ScrollView(c).apply {
        setBackgroundColor(BG)
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 20), dp(c, 16), dp(c, 24))
    }

    fun title(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 24f; setTextColor(TEXT); setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun text(c: Context, t: String, size: Float = 14f, color: Int = TEXT) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color)
    }

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(c, 14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(c, 12) }
    }

    fun button(c: Context, label: String, color: Int = Color.parseColor("#2A3342"), onClick: () -> Unit) =
        Button(c).apply {
            text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(c, 12).toFloat() }
            minimumHeight = dp(c, 48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(c, 8) }
            setOnClickListener { onClick() }
        }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun spacer(c: Context, h: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c, h)) }
}
