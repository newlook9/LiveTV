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
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var root: LinearLayout
    private lateinit var left: LinearLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var itemSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var tabTv: TextView
    private lateinit var tabFav: TextView
    private lateinit var btnFav: TextView
    private lateinit var btnQ: TextView
    private lateinit var btnFull: TextView
    private lateinit var btnTheme: TextView
    private lateinit var modeAdapter: ThemedAdapter
    private lateinit var orient: OrientationEventListener
    private var itemAdapter: ThemedAdapter? = null

    private val adapter = ChAdapter()
    private var isTv = false
    private var tvFull = false
    private var tab = 0
    private var mode = 0
    private var themeIdx = 0
    private var qIdx = 0
    private var retries = 0
    private var currentKey = ""
    private var nowPlaying: Channel? = null
    private var loaded: List<Channel> = emptyList()
    private var favs: MutableList<Channel> = ArrayList()
    private var countryItems: List<Item> = Data.countries
    private var curItems: List<Item> = Data.countries

    private val qNames = arrayOf("Auto (1080p tak)", "720p", "480p", "360p", "240p")
    private val qH = intArrayOf(1080, 720, 480, 360, 240)

    private val retryRun = Runnable {
        player.seekToDefaultPosition()
        player.prepare()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        store = Store(this)
        themeIdx = store.getInt("theme", 0)
        qIdx = store.getInt("q", 0)
        mode = store.getInt("mode", 0)
        favs = store.favorites()

        val um = getSystemService(UI_MODE_SERVICE) as UiModeManager
        isTv = um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature("android.hardware.touchscreen")

        createPlayer()
        buildUi()
        setupOrientation()
        setupBack()
        setQuality(qIdx)
        modeSpinner.setSelection(mode)
        loadCountriesFromNet()

        val lu = store.getString("last_url", "")
        if (lu.isNotEmpty()) {
            play(Channel(store.getString("last_name", ""), lu))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun focusBg(): StateListDrawable {
        val bg = StateListDrawable()
        bg.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0x553B82F6))
        bg.addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x333B82F6))
        bg.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        return bg
    }

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
            override fun onPlaybackStateChanged(state: Int) {
                val n = nowPlaying?.name ?: ""
                if (state == Player.STATE_BUFFERING) {
                    setStatus("بفرنگ... $n")
                } else if (state == Player.STATE_READY) {
                    retries = 0
                    setStatus("چل رہا ہے: $n")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retries < 3) {
                    retries++
                    setStatus("دوبارہ کوشش $retries/3")
                    handler.postDelayed(retryRun, 3000)
                } else {
                    setStatus("یہ چینل نہیں چل رہا، دوسرا چنیں")
                }
            }
        })
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun mkBtn(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.textSize = 14f
        b.setPadding(dp(6), dp(12), dp(6), dp(12))
        b.isFocusable = true
        b.isClickable = true
        b.background = focusBg()
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root = LinearLayout(this)
        left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.player = player

        videoBox = FrameLayout(this)
        videoBox.setBackgroundColor(Color.BLACK)
        videoBox.addView(playerView, FrameLayout.LayoutParams(match, match))

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isTv) toggleRotate()
                return true
            }
        })
        videoBox.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            true
        }

        btnFav = mkBtn("♡ Fav") { nowPlaying?.let { toggleFav(it) } }
        btnQ = mkBtn("Quality") { showQuality() }
        btnFull = mkBtn("Full") { goFull() }
        btnTheme = mkBtn("Theme") { showTheme() }
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (b in listOf(btnFav, btnQ, btnFull, btnTheme)) {
            bar.addView(b, LinearLayout.LayoutParams(0, wrap, 1f))
        }

        left.addView(videoBox)
        left.addView(bar, LinearLayout.LayoutParams(match, wrap))

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(dp(12), dp(8), dp(12), dp(4))

        tabTv = mkBtn("TV") {
            tab = 0
            updateTabs()
            refreshList()
        }
        tabFav = mkBtn("Favorites") {
            tab = 1
            updateTabs()
            refreshList()
        }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.addView(tabTv, LinearLayout.LayoutParams(0, wrap, 1f))
        tabs.addView(tabFav, LinearLayout.LayoutParams(0, wrap, 1f))

        modeAdapter = ThemedAdapter(this, listOf("Mulk (Country)", "Category"))
        modeSpinner = Spinner(this)
        modeSpinner.adapter = modeAdapter
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                mode = pos
                store.putInt("mode", pos)
                setItemsForMode()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        itemSpinner = Spinner(this)
        itemSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos >= curItems.size) return
                val item = curItems[pos]
                store.putString("sel$mode", item.key)
                loadList(item)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val spRow = LinearLayout(this)
        spRow.orientation = LinearLayout.HORIZONTAL
        spRow.addView(modeSpinner, LinearLayout.LayoutParams(0, wrap, 1f))
        spRow.addView(itemSpinner, LinearLayout.LayoutParams(0, wrap, 1f))

        search = EditText(this)
        search.hint = "Search / تلاش"
        search.setSingleLine(true)
        search.textSize = 15f
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList()
            }
        })

        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(status)
        panel.addView(tabs)
        panel.addView(spRow)
        panel.addView(search)
        panel.addView(list, LinearLayout.LayoutParams(match, 0, 1f))

        root.addView(left)
        root.addView(panel)
        setContentView(root)
        applyTheme()
        updateTabs()
        applyLayout()
    }

    private fun applyTheme() {
        val t = Themes.get(themeIdx)
        root.setBackgroundColor(t.bg)
        panel.setBackgroundColor(t.bg)
        bar.setBackgroundColor(t.bar)
        status.setTextColor(t.sub)
        for (b in listOf(btnFav, btnQ, btnFull, btnTheme)) b.setTextColor(t.text)
        search.setTextColor(t.text)
        search.setHintTextColor(t.sub)
        modeAdapter.theme = t
        modeAdapter.notifyDataSetChanged()
        itemAdapter?.theme = t
        itemAdapter?.notifyDataSetChanged()
        adapter.theme = t
        adapter.notifyDataSetChanged()
        updateTabs()
    }

    private fun updateTabs() {
        val t = Themes.get(themeIdx)
        val accent = Color.parseColor("#3B82F6")
        tabTv.setTextColor(if (tab == 0) accent else t.text)
        tabFav.setTextColor(if (tab == 1) accent else t.text)
    }

    private fun applyLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        val full = (!isTv && land) || (isTv && tvFull)
        if (full) {
            root.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(match, match)
            videoBox.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            bar.visibility = View.GONE
            panel.visibility = View.GONE
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            bar.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            if (isTv) {
                root.orientation = LinearLayout.HORIZONTAL
                left.layoutParams = LinearLayout.LayoutParams(0, match, 2f)
                videoBox.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
                panel.layoutParams = LinearLayout.LayoutParams(0, match, 1f)
            } else {
                root.orientation = LinearLayout.VERTICAL
                val w = resources.displayMetrics.widthPixels
                left.layoutParams = LinearLayout.LayoutParams(match, wrap)
                videoBox.layoutParams = LinearLayout.LayoutParams(match, w * 9 / 16)
                panel.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun toggleRotate() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun goFull() {
        if (isTv) {
            tvFull = true
            applyLayout()
        } else {
            toggleRotate()
        }
    }

    private fun setupOrientation() {
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

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val land = resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                if (isTv && tvFull) {
                    tvFull = false
                    applyLayout()
                } else if (!isTv && land) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    isEnabled = false
                    this@MainActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isTv && tvFull) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    step(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    step(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    tvFull = false
                    applyLayout()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun step(d: Int) {
        val items = adapter.items
        if (items.isEmpty()) return
        val cur = items.indexOfFirst { it.url == nowPlaying?.url }
        val next = if (cur < 0) 0 else (cur + d + items.size) % items.size
        play(items[next])
    }

    private fun setItemsForMode() {
        curItems = if (mode == 0) countryItems else Data.categories
        val a = ThemedAdapter(this, curItems.map { it.label })
        a.theme = Themes.get(themeIdx)
        itemAdapter = a
        itemSpinner.adapter = a
        val saved = store.getString("sel$mode", if (mode == 0) "pk" else "news")
        val idx = curItems.indexOfFirst { it.key == saved }
        itemSpinner.setSelection(if (idx < 0) 0 else idx)
    }

    private fun loadList(item: Item) {
        val key = (if (mode == 0) "countries/" else "categories/") + item.key
        if (key == currentKey) return
        currentKey = key
        val cached = store.readCache(key)
        loaded = if (cached != null) parseM3u(cached) else emptyList()
        refreshList()
        if (cached == null) setStatus("لسٹ لوڈ ہو رہی ہے...")
        Thread {
            val text = store.download("https://iptv-org.github.io/iptv/$key.m3u")
            handler.post {
                if (key != currentKey) return@post
                if (text != null) {
                    store.writeCache(key, text)
                    loaded = parseM3u(text)
                    refreshList()
                } else if (cached == null) {
                    setStatus("لسٹ نہیں ملی، انٹرنیٹ چیک کریں")
                }
            }
        }.start()
    }

    private fun refreshList() {
        val base = if (tab == 0) loaded else favs
        val q = search.text.toString().trim()
        val f = if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        adapter.items = f
        adapter.notifyDataSetChanged()
        setStatus("${f.size} چینل")
    }

    private fun loadCountriesFromNet() {
        val cached = store.readCache("countries.json")
        if (cached != null) applyCountries(cached)
        Thread {
            val text = store.download("https://iptv-org.github.io/api/countries.json")
            if (text != null) {
                store.writeCache("countries.json", text)
                handler.post { applyCountries(text) }
            }
        }.start()
    }

    private fun applyCountries(text: String) {
        try {
            val arr = JSONArray(text)
            val l = ArrayList<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                l.add(Item(o.getString("name"), o.getString("code").lowercase()))
            }
            if (l.size > 20) {
                l.sortBy { it.label }
                countryItems = l
                if (mode == 0) setItemsForMode()
            }
        } catch (e: Exception) {
        }
    }

    private fun play(ch: Channel) {
        nowPlaying = ch
        retries = 0
        handler.removeCallbacks(retryRun)
        store.putString("last_name", ch.name)
        store.putString("last_url", ch.url)
        setStatus("لوڈ ہو رہا ہے: ${ch.name}")
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
        updateFavBtn()
    }

    private fun updateFavBtn() {
        val np = nowPlaying
        val on = np != null && favs.any { it.url == np.url }
        btnFav.text = if (on) "♥ Fav" else "♡ Fav"
    }

    private fun toggleFav(ch: Channel) {
        val ex = favs.firstOrNull { it.url == ch.url }
        if (ex != null) favs.remove(ex) else favs.add(ch)
        store.saveFavorites(favs)
        updateFavBtn()
        if (tab == 1) refreshList() else adapter.notifyDataSetChanged()
    }

    private fun showQuality() {
        AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setSingleChoiceItems(qNames, qIdx) { d, which ->
                setQuality(which)
                d.dismiss()
            }
            .show()
    }

    private fun setQuality(i: Int) {
        qIdx = i
        store.putInt("q", i)
        val h = qH[i]
        val w = h * 16 / 9
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(w, h)
            .build()
    }

    private fun showTheme() {
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(Themes.names.toTypedArray(), themeIdx) { d, which ->
                themeIdx = which
                store.putInt("theme", which)
                applyTheme()
                d.dismiss()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (nowPlaying != null) player.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(retryRun)
        orient.disable()
        player.release()
    }

    inner class ChAdapter : RecyclerView.Adapter<ChAdapter.VH>() {
        var items: List<Channel> = emptyList()
        var theme: AppTheme = Themes.get(0)

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context)
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.setPadding(dp(16), dp(14), dp(16), dp(14))
            tv.textSize = 16f
            tv.isFocusable = true
            tv.isClickable = true
            tv.background = focusBg()
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            val fav = favs.any { it.url == ch.url }
            h.tv.text = if (fav) "♥  " + ch.name else ch.name
            h.tv.setTextColor(theme.text)
            h.tv.setOnClickListener { play(ch) }
            h.tv.setOnLongClickListener {
                toggleFav(ch)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
