package com.livetv.app

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class AppTheme(val bg: Int, val text: Int, val sub: Int, val bar: Int)

object Themes {
    val names = listOf("Light", "Dark", "AMOLED")

    fun get(i: Int): AppTheme = when (i) {
        1 -> AppTheme(
            Color.parseColor("#1E1E1E"), Color.WHITE,
            Color.parseColor("#B0B0B0"), Color.parseColor("#2C2C2C")
        )
        2 -> AppTheme(
            Color.BLACK, Color.WHITE,
            Color.parseColor("#9E9E9E"), Color.parseColor("#141414")
        )
        else -> AppTheme(
            Color.WHITE, Color.BLACK,
            Color.DKGRAY, Color.parseColor("#EEEEEE")
        )
    }
}

class ThemedAdapter(ctx: Context, items: List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {

    var theme: AppTheme = Themes.get(0)

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent)
        (v as? TextView)?.setTextColor(theme.text)
        return v
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getDropDownView(position, convertView, parent)
        (v as? TextView)?.let {
            it.setTextColor(theme.text)
            it.setBackgroundColor(theme.bar)
        }
        return v
    }
    }
