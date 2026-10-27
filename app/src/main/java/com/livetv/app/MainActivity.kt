package com.livetv.app

import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String)

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var root: LinearLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var orient: OrientationEventListener
    private val adapter = ChAdapter()
    private var isTv = false
    private var currentCode = ""

    private val names = ArrayList<String>()
    private val codes = ArrayList<String>()

    private val countryData =
        "Pakistan|pk,India|in,Bangladesh|bd,Afghanistan|af,Iran|ir,Saudi Arabia|sa," +
        "United Arab Emirates|ae,Qatar|qa,Kuwait|kw,Oman|om,Bahrain|bh,Turkey|tr," +
        "Egypt|eg,Algeria|dz,Morocco|ma,Iraq|iq,Jordan|jo,Lebanon|lb,Syria|sy," +
        "Palestine|ps,United Kingdom|uk,United States|us,Canada|ca,Australia|au," +
        "Germany|de,France|fr,Italy|it,Spain|es,Russia|ru,China|cn,Japan|jp," +
        "South Korea|kr,Indonesia|id,Malaysia|my,Singapore|sg,Brazil|br,Mexico|mx," +
        "Argentina|ar,South Africa|za,Nigeria|ng,Kenya|ke,Sri Lanka|lk,Nepal|np"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val um = getSystemService(UI_MODE_SERVICE) as UiModeManager
        isTv = um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature("android.hardware.touchscreen")

        for (item in countryData.split(",")) {
            val p = item.split("|")
            names.add(p[0])
            codes.add(p[1])
        }

        createPlayer()
        buildUi()

        orient = object : OrientationEventListener(this) {
            override fun onOrientationChanged(a: Int) {
                if (a < 0) return
                val ro = requestedOrientation
                if (ro == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
                val physLand = (a in 70..110) || (a in 250..290)
                val physPort = a <= 20 || a >= 340 || (a in 160..200)
                if ((ro == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE && physLand) ||
                    (ro == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && physPort)
                ) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        if (orient.canDetectOrientation()) orient.enable()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 3000, 6000)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 9) LiveTV")
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "یہ چینل نہیں چل رہا، دوسرا چنیں"
            }
        })
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.setBackgroundColor(Color.WHITE)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.player = player

        videoBox = FrameLayout(this)
        videoBox.setBackgroundColor(Color.BLACK)
        videoBox.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val land = resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                requestedOrientation = if (land) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                return true
            }
        })
        videoBox.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            true
        }

        status = TextView(this)
        status.textSize = 14f
        status.setTextColor(Color.DKGRAY)
        status.setPadding(dp(12), dp(8), dp(12), dp(4))

        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                loadCountry(codes[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(status)
        panel.addView(spinner)
        panel.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(videoBox)
        root.addView(panel)
        setContentView(root)
        applyLayout()
    }

    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        if (isTv) {
            root.orientation = LinearLayout.HORIZONTAL
            videoBox.layoutParams = LinearLayout.LayoutParams(0, match, 2f)
            panel.layoutParams = LinearLayout.LayoutParams(0, match, 1f)
            panel.visibility = View.VISIBLE
        } else if (land) {
            root.orientation = LinearLayout.VERTICAL
            videoBox.layoutParams = LinearLayout.LayoutParams(match, match)
            panel.visibility = View.GONE
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            root.orientation = LinearLayout.VERTICAL
            val w = resources.displayMetrics.widthPixels
            videoBox.layoutParams = LinearLayout.LayoutParams(match, w * 9 / 16)
            panel.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            panel.visibility = View.VISIBLE
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun fetch(url: String, cb: (String?) -> Unit) {
        Thread {
            var result: String? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 25000
                c.setRequestProperty("User-Agent", "Mozilla/5.0")
                result = c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
            }
            handler.post { cb(result) }
        }.start()
    }

    private fun loadCountry(code: String) {
        currentCode = code
        status.text = "لسٹ لوڈ ہو رہی ہے..."
        adapter.items = emptyList()
        adapter.notifyDataSetChanged()
        fetch("https://iptv-org.github.io/iptv/countries/$code.m3u") { text ->
            if (code != currentCode) return@fetch
            if (text == null) {
                status.text = "انٹرنیٹ یا لسٹ کا مسئلہ ہے، دوبارہ کوشش کریں"
            } else {
                val ch = parseM3u(text)
                adapter.items = ch
                adapter.notifyDataSetChanged()
                status.text = if (ch.isEmpty()) "اس ملک کے چینل نہیں ملے" else "${ch.size} چینل"
            }
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                out.add(Channel(name, line))
                name = null
            }
        }
        return out
    }

    private fun play(ch: Channel) {
        status.text = "چل رہا ہے: ${ch.name}"
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        orient.disable()
        player.release()
    }

    inner class ChAdapter : RecyclerView.Adapter<ChAdapter.VH>() {
        var items: List<Channel> = emptyList()

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context)
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.setPadding(dp(16), dp(14), dp(16), dp(14))
            tv.textSize = 16f
            tv.setTextColor(Color.BLACK)
            tv.isFocusable = true
            tv.isClickable = true
            val bg = StateListDrawable()
            bg.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0x553B82F6))
            bg.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x333B82F6))
            bg.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            tv.background = bg
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            h.tv.text = ch.name
            h.tv.setOnClickListener { play(ch) }
        }

        override fun getItemCount(): Int = items.size
    }
}
