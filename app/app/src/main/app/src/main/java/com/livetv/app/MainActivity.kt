package com.livetv.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Live TV - Step 1 OK"
        tv.textSize = 28f
        tv.setPadding(48, 48, 48, 48)
        setContentView(tv)
    }
}
