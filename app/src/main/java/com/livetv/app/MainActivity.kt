package com.livetv.app

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
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
    private lateinit var spRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var itemSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var tabViews: List<TextView>
    private lateinit var btnFav: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnQ: TextView
    private lateinit var btnFull: TextView
    private lateinit var btnTheme: TextView
    private lateinit var btnAction: TextView
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
    private var files: MutableList<Channel> = ArrayList()
    private var playlists: MutableList<Playlist> = ArrayList()
    private var openPlaylist: Playlist? = null
    private var rec: Recorder? = null
    private var countryItems: List<Item> = Data.countries
    private var curItems: List<Item> = Data.countries

    private val qNames = arrayOf("Auto (1080p tak)", "720p", "480p", "360p", "240p")
    private val qH = intArrayOf(1080, 720, 480, 360, 240)

    private val retryRun = Runnable {
        player.seekToDefaultPosition()
        player.prepare()
    }

    private val recTick = object : Runnable {
        override fun run() {
            val r = rec
            if (r != null && r.active) {
                btnRec.text = "■ " + (r.bytes / 1048576) + " MB"
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                val s = u.toString()
                if (files.none { it.url == s }) files.add(Channel(displayName(u), s))
            }
            store.saveList("files", files)
            if (tab == 3) refreshList()
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startRecording()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        store = Store(this)
        themeIdx = store.getInt("theme", 0)
        qIdx = store.getInt("q", 0)
        mode = store.getInt("mode", 0)
        favs = store.getList("fav")
        files = store.getList("files")
        playlists = store.getPlaylists()

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
        selectTab(0)

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
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val n = nowPlaying?.name ?: ""
                if (state == Player.STATE_BUFFERING) {
                    setStatus("بفرنگ... $n")
                } else if (state == Player.STATE_READY) {
                    retries = 0
                    setStatus("چل رہا ہے: $n")
                } else if (state == Player.STATE_ENDED && tab >= 3) {
                    step(1)
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
        b.textSize = 13f
        b.setPadding(dp(4), dp(12), dp(4), dp(12))
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
        btnRec = mkBtn("● Rec") { toggleRecord() }
        btnQ = mkBtn("Quality") { showQuality() }
        btnFull = mkBtn("Full") { goFull() }
        btnTheme = mkBtn("Theme") { showTheme() }
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (b in listOf(btnFav, btnRec, btnQ, btnFull, btnTheme)) {
            bar.addView(b, LinearLayout.LayoutParams(0, wrap, 1f))
        }

        left.addView(videoBox)
        left.addView(bar, LinearLayout.LayoutParams(match, wrap))

        status = TextView(this)
        status.textSize = 14f
        status.setPadding(dp(12), dp(8), dp(12), dp(4))

        val labels = listOf("TV", "Radio", "Fav", "Files", "Lists")
        tabViews = labels.mapIndexed { i, s -> mkBtn(s) { selectTab(i) } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (t in tabViews) tabs.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))

        btnAction = mkBtn("") { onAction() }
        btnAction.textSize = 14f
        btnAction.visibility = View.GONE

        modeAdapter = ThemedAdapter(this, listOf("Mulk (Country)", "Category / Tag"))
        modeSpinner = Spinner(this)
        modeSpinner.adapter = modeAdapter
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                mode = pos
                store.putInt("mode", pos)
                if (tab <= 1) setItemsForMode()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        itemSpinner = Spinner(this)
        itemSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (tab > 1 || pos >= curItems.size) return
                val item = curItems[pos]
                store.putString("sel${tab}_$mode", item.key)
                loadList(item)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spRow = LinearLayout(this)
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
        panel.addView(btnAction)
        panel.addView(spRow)
        panel.addView(search)
        panel.addView(list, LinearLayout.LayoutParams(match, 0, 1f))

        root.addView(left)
        root.addView(panel)
        setContentView(root)
        applyTheme()
        applyLayout()
    }

    private fun applyTheme() {
        val t = Themes.get(themeIdx)
        root.setBackgroundColor(t.bg)
        panel.setBackgroundColor(t.bg)
        bar.setBackgroundColor(t.bar)
        status.setTextColor(t.sub)
        for (b in listOf(btnFav, btnRec, btnQ, btnFull, btnTheme, btnAction)) {
            b.setTextColor(t.text)
        }
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
        for (i in tabViews.indices) {
            tabViews[i].setTextColor(if (i == tab) accent else t.text)
        }
    }

    private fun updateRows() {
        spRow.visibility = if (tab <= 1) View.VISIBLE else View.GONE
        val showAction = tab == 3 || (tab == 4 && openPlaylist == null)
        btnAction.visibility = if (showAction) View.VISIBLE else View.GONE
        btnAction.text = if (tab == 3) "+ Gallery se files chunein" else "+ Nayi Playlist"
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
                } else if (tab == 4 && openPlaylist != null) {
                    openPlaylist = null
                    updateRows()
                    refreshList()
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
        val items = adapter.items.filter { !it.url.startsWith("pl:") && it.url != "back:" }
        if (items.isEmpty()) return
        val cur = items.indexOfFirst { it.url == nowPlaying?.url }
        val next = if (cur < 0) 0 else (cur + d + items.size) % items.size
        play(items[next])
    }

    private fun selectTab(t: Int) {
        tab = t
        openPlaylist = null
        search.setText("")
        if (t <= 1) {
            val prefix = if (t == 0) "tv/" else "radio/"
            if (!currentKey.startsWith(prefix)) {
                loaded = emptyList()
                currentKey = ""
            }
            setItemsForMode()
        }
        updateTabs()
        updateRows()
        refreshList()
    }
private fun setItemsForMode() {
        curItems = if (mode == 0) {
            countryItems
        } else if (tab == 0) {
            Data.categories
        } else {
            RData.tags
        }
        val a = ThemedAdapter(this, curItems.map { it.label })
        a.theme = Themes.get(themeIdx)
        itemAdapter = a
        itemSpinner.adapter = a
        val saved = store.getString("sel${tab}_$mode", if (mode == 0) "pk" else "news")
        val idx = curItems.indexOfFirst { it.key == saved }
        itemSpinner.setSelection(if (idx < 0) 0 else idx)
    }

    private fun parseAny(key: String, text: String): List<Channel> =
        if (key.startsWith("tv/")) parseM3u(text) else parseRadio(text)

    private fun fetchFor(key: String, item: Item): String? {
        if (key.startsWith("tv/")) {
            return store.download(
                "https://iptv-org.github.io/iptv/" + key.removePrefix("tv/") + ".m3u"
            )
        }
        val path = if (key.startsWith("radio/country/")) {
            var cc = item.key.uppercase()
            if (cc == "UK") cc = "GB"
            "bycountrycodeexact/$cc"
        } else {
            "bytag/" + item.key
        }
        val q = "?hidebroken=true&order=clickcount&reverse=true&limit=500"
        for (h in listOf("de1", "nl1", "at1")) {
            val t = store.download("https://$h.api.radio-browser.info/json/stations/$path$q")
            if (t != null && t.startsWith("[")) return t
        }
        return null
    }

    private fun loadList(item: Item) {
        val key = if (tab == 0) {
            (if (mode == 0) "tv/countries/" else "tv/categories/") + item.key
        } else {
            (if (mode == 0) "radio/country/" else "radio/tag/") + item.key
        }
        if (key == currentKey) return
        currentKey = key
        val cached = store.readCache(key)
        loaded = if (cached != null) parseAny(key, cached) else emptyList()
        refreshList()
        if (cached == null) setStatus("لسٹ لوڈ ہو رہی ہے...")
        Thread {
            val text = fetchFor(key, item)
            handler.post {
                if (key != currentKey) return@post
                if (text != null) {
                    store.writeCache(key, text)
                    loaded = parseAny(key, text)
                    refreshList()
                } else if (cached == null) {
                    setStatus("لسٹ نہیں ملی، انٹرنیٹ چیک کریں")
                }
            }
        }.start()
    }

    private fun refreshList() {
        val pl = openPlaylist
        val base: List<Channel> = when (tab) {
            0, 1 -> loaded
            2 -> favs
            3 -> files
            else -> if (pl == null) {
                playlists.map { Channel("▸ " + it.name + " (" + it.items.size + ")", "pl:" + it.name) }
            } else {
                pl.items
            }
        }
        val q = search.text.toString().trim()
        var f: List<Channel> =
            if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }
        if (tab == 4 && pl != null) f = listOf(Channel("←  واپس", "back:")) + f
        adapter.items = f
        adapter.notifyDataSetChanged()
        setStatus("${f.size} آئٹم")
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
                if (mode == 0 && tab <= 1) setItemsForMode()
            }
        } catch (e: Exception) {
        }
    }

    private fun onAction() {
        if (tab == 3) {
            pickFiles.launch(arrayOf("audio/*", "video/*"))
        } else if (tab == 4) {
            newPlaylist(null)
        }
    }

    private fun displayName(u: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) n = c.getString(i)
                }
            }
        } catch (e: Exception) {
        }
        return n ?: (u.lastPathSegment ?: "File")
    }

    private fun onItemClick(ch: Channel) {
        if (ch.url == "back:") {
            openPlaylist = null
            updateRows()
            refreshList()
            return
        }
        if (ch.url.startsWith("pl:")) {
            openPlaylist = playlists.firstOrNull { "pl:" + it.name == ch.url }
            updateRows()
            refreshList()
            return
        }
        play(ch)
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
        store.saveList("fav", favs)
        updateFavBtn()
        if (tab == 2) refreshList() else adapter.notifyDataSetChanged()
    }

    private fun newPlaylist(first: Channel?) {
        val et = EditText(this)
        et.hint = "Playlist ka naam"
        AlertDialog.Builder(this)
            .setTitle("Nayi Playlist")
            .setView(et)
            .setPositiveButton("Banayein") { _, _ ->
                var n = et.text.toString().trim()
                if (n.isEmpty()) n = "Playlist ${playlists.size + 1}"
                while (playlists.any { it.name == n }) n += "_"
                val p = Playlist(n, ArrayList())
                if (first != null) p.items.add(first)
                playlists.add(p)
                store.savePlaylists(playlists)
                if (tab == 4) refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylist(ch: Channel) {
        val names = ArrayList<String>()
        for (p in playlists) names.add(p.name)
        names.add("+ Nayi Playlist")
        AlertDialog.Builder(this)
            .setTitle("Playlist chunein")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == playlists.size) {
                    newPlaylist(ch)
                } else {
                    val p = playlists[which]
                    if (p.items.none { it.url == ch.url }) p.items.add(ch)
                    store.savePlaylists(playlists)
                    setStatus("playlist میں شامل: ${p.name}")
                }
            }
            .show()
    }

    private fun showItemMenu(ch: Channel) {
        if (ch.url == "back:") return
        if (ch.url.startsWith("pl:")) {
            val p = playlists.firstOrNull { "pl:" + it.name == ch.url } ?: return
            AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(arrayOf("Playlist hataein")) { _, _ ->
                    playlists.remove(p)
                    store.savePlaylists(playlists)
                    refreshList()
                }
                .show()
            return
        }
        val isFav = favs.any { it.url == ch.url }
        val opts = ArrayList<String>()
        opts.add(if (isFav) "Favorite se hataein" else "Favorite mein daalein")
        opts.add("Playlist mein daalein")
        if (tab == 3) opts.add("List se hataein")
        if (tab == 4 && openPlaylist != null) opts.add("Playlist se hataein")
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "Playlist mein daalein" -> addToPlaylist(ch)
                    "List se hataein" -> {
                        files.removeAll { it.url == ch.url }
                        store.saveList("files", files)
                        refreshList()
                    }
                    "Playlist se hataein" -> {
                        openPlaylist?.items?.removeAll { it.url == ch.url }
                        store.savePlaylists(playlists)
                        refreshList()
                    }
                    else -> toggleFav(ch)
                }
            }
            .show()
    }

    private fun toggleRecord() {
        val r = rec
        if (r != null && r.active) {
            r.stop()
            setStatus("ریکارڈنگ محفوظ ہو رہی ہے...")
            return
        }
        val ch = nowPlaying
        if (ch == null) {
            setStatus("پہلے کوئی چینل چلائیں")
            return
        }
        if (ch.url.startsWith("content:")) {
            setStatus("اپنی فائل کی ریکارڈنگ کی ضرورت نہیں")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val ch = nowPlaying ?: return
        val old = rec
        if (old != null && old.active) return
        val radio = player.videoSize.width == 0
        val r = Recorder(this) { msg -> handler.post { recDone(msg) } }
        rec = r
        r.start(ch.url, ch.name, radio)
        setStatus("ریکارڈنگ شروع...")
        handler.post(recTick)
    }

    private fun recDone(msg: String) {
        btnRec.text = "● Rec"
        setStatus(msg)
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
        if (player.videoSize.width > 0) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(retryRun)
        rec?.stop()
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
            h.tv.setOnClickListener { onItemClick(ch) }
            h.tv.setOnLongClickListener {
                showItemMenu(ch)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
