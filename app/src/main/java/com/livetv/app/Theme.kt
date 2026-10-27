package com.livetv.app

import android.graphics.Color

class AppTheme(
    val bg: Int,
    val text: Int,
    val sub: Int,
    val bar: Int,
    val card: Int,
    val card2: Int,
    val accent: Int
)

object Themes {
    val names = listOf("Light", "Dark", "AMOLED")

    private fun c(s: String): Int = Color.parseColor(s)

    fun get(i: Int): AppTheme = when (i) {
        1 -> AppTheme(
            c("#0F172A"), c("#F8FAFC"), c("#94A3B8"), c("#1E293B"),
            c("#1E293B"), c("#162033"), c("#3B82F6")
        )
        2 -> AppTheme(
            c("#000000"), c("#FFFFFF"), c("#9CA3AF"), c("#111111"),
            c("#161616"), c("#0B0B0B"), c("#22D3EE")
        )
        else -> AppTheme(
            c("#F1F5F9"), c("#0F172A"), c("#475569"), c("#E2E8F0"),
            c("#FFFFFF"), c("#EEF2F7"), c("#2563EB")
        )
    }
}
