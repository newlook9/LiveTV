package com.livetv.app

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.bumptech.glide.Glide
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
    private lateinit var pickRow: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var tabViews: List<TextView>
    private lateinit var btnFav: TextView
    private lateinit var btnRec: TextView
    private lateinit var btnQ: TextView
    private lateinit var btnFull: TextView
    private lateinit var btnTheme: TextView
    private lateinit var btnAction: TextView
    private lateinit var btnMode: TextView
    private lateinit var btnItem: TextView
    private lateinit var orient: OrientationEventListener

    private val adapter = ChAdapter()
    private val iconCache = HashMap<String, Drawable>()
    private var isTv = false
    private var tvFull = false
    private var tab = 0
    private var mode = 0
    private var curIdx = 0
    private var themeIdx = 1
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
        themeIdx = store.getInt("theme", 1)
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
        loadCountriesFromNet()
        selectTab(0)

        val lu = store.getString("last_url", "")
        if (lu.isNotEmpty()) {
            play(Channel(store.getString("last_name", ""), lu))
        }
    }

    // ---------- chhote helper ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    private fun rr(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setColor(color)
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    private fun gradBg(c1: Int, c2: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2))
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun stateBg(normal: Drawable, radiusDp: Int): StateListDrawable {
        val t = Themes.get(themeIdx)
        val sl = StateListDrawable()
        sl.addState(
            intArrayOf(android.R.attr.state_focused),
            rr(withAlpha(t.accent, 0x55), radiusDp, t.accent, 3)
        )
        sl.addState(
            intArrayOf(android.R.attr.state_pressed),
            rr(withAlpha(t.accent, 0x77), radiusDp, t.accent, 2)
        )
        sl.addState(intArrayOf(), normal)
        return sl
    }

    private fun lp(
        w: Int, h: Int, wt: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, wt)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun letterIcon(name: String): Drawable {
        val colors = intArrayOf(
            0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF10B981.toInt(),
            0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFF06B6D4.toInt()
        )
        val ci = (name.hashCode() and 0x7fffffff) % colors.size
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "TV"
        val key = "$letter$ci"
        iconCache[key]?.let { return it }
        val size = dp(44)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colors[ci]
        c.drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            dp(10).toFloat(), dp(10).toFloat(), p
        )
        p.color = Color.WHITE
        p.textSize = size * 0.5f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val y = size / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(letter, size / 2f, y, p)
        val d = BitmapDrawable(resources, bmp)
        iconCache[key] = d
        return d
    }

    // ---------- player ----------

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

    // ---------- UI ----------

    private fun mkBtn(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.textSize = 13f
        b.setPadding(dp(4), dp(12), dp(4), dp(12))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { onClick() }
        b.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
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
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        for (b in listOf(btnFav, btnRec, btnQ, btnFull, btnTheme)) {
            bar.addView(b, lp(0, wrap, 1f, 2, 2, 2, 2))
        }

        left.addView(videoBox)
        left.addView(bar, lp(match, wrap, 0f, 8, 8, 8, 4))

        // header
        val logoIv = ImageView(this)
        logoIv.setImageResource(R.drawable.ic_launcher)
        title = TextView(this)
        title.text = "Live TV"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        status = TextView(this)
        status.textSize = 12f
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.addView(title)
        tcol.addView(status)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(12), dp(8), dp(12), dp(4))
        header.addView(logoIv, lp(dp(40), dp(40), 0f, 0, 0, 10, 0))
        header.addView(tcol, lp(0, wrap, 1f))

        val labels = listOf("TV", "Radio", "Fav", "Files", "Lists")
        tabViews = labels.mapIndexed { i, s -> mkBtn(s) { selectTab(i) } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (t in tabViews) tabs.addView(t, lp(0, wrap, 1f, 3, 0, 3, 0))

        btnAction = mkBtn("") { onAction() }
        btnAction.textSize = 14f
        btnAction.visibility = View.GONE

        btnMode = mkBtn("") {
            showPicker("Chunein", listOf("Mulk (Country)", "Category / Tag"), mode) { pos ->
                mode = pos
                store.putInt("mode", pos)
                setItemsForMode()
            }
        }
        btnItem = mkBtn("") {
            showPicker(
                if (mode == 0) "Mulk chunein" else "Category chunein",
                curItems.map { it.label }, curIdx
            ) { pos -> selectItem(pos) }
        }
        for (b in listOf(btnMode, btnItem)) {
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setPadding(dp(14), dp(12), dp(14), dp(12))
            b.maxLines = 1
            b.ellipsize = TextUtils.TruncateAt.END
            b.textSize = 14f
        }
        pickRow = LinearLayout(this)
        pickRow.orientation = LinearLayout.HORIZONTAL
        pickRow.addView(btnMode, lp(0, wrap, 1f, 0, 0, 4, 0))
        pickRow.addView(btnItem, lp(0, wrap, 1.4f, 4, 0, 0, 0))

        search = EditText(this)
        search.hint = "Search / تلاش"
        search.setSingleLine(true)
        search.textSize = 15f
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
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
        list.clipToPadding = false
        list.setPadding(0, 0, 0, dp(8))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.addView(header)
        panel.addView(tabs, lp(match, wrap, 0f, 8, 4, 8, 4))
        panel.addView(btnAction, lp(match, wrap, 0f, 8, 2, 8, 4))
        panel.addView(pickRow, lp(match, wrap, 0f, 8, 2, 8, 4))
        panel.addView(search, lp(match, wrap, 0f, 8, 2, 8, 4))
        panel.addView(list, lp(match, 0, 1f))

        root.addView(left)
        root.addView(panel)
        setContentView(root)
        applyTheme()
        applyLayout()
    }

    private fun applyTheme() {
        val t = Themes.get(themeIdx)
        root.background = gradBg(t.bg, t.card2, 0)
        bar.background = gradBg(t.card, t.card2, 16)
        bar.elevation = dp(6).toFloat()
        title.setTextColor(t.text)
        status.setTextColor(t.sub)
        for (b in listOf(btnFav, btnRec, btnQ, btnFull, btnTheme)) {
            b.setTextColor(t.text)
            b.background = stateBg(ColorDrawable(Color.TRANSPARENT), 12)
        }
        btnAction.setTextColor(t.accent)
        btnAction.background =
            stateBg(rr(withAlpha(t.accent, 0x28), 12, t.accent, 1), 12)
        for (b in listOf(btnMode, btnItem)) {
            b.setTextColor(t.text)
            b.background = stateBg(rr(t.card, 12, withAlpha(t.sub, 0x66), 1), 12)
        }
        search.setTextColor(t.text)
        search.setHintTextColor(t.sub)
        search.background = rr(t.card, 12, withAlpha(t.sub, 0x66), 1)
        adapter.notifyDataSetChanged()
        updateTabs()
    }

    private fun updateTabs() {
        val t = Themes.get(themeIdx)
        val onAccent = if (themeIdx == 2) Color.BLACK else Color.WHITE
        for (i in tabViews.indices) {
            val b = tabViews[i]
            if (i == tab) {
                b.setTextColor(onAccent)
                b.background = stateBg(rr(t.accent, 12), 12)
            } else {
                b.setTextColor(t.text)
                b.background = stateBg(ColorDrawable(Color.TRANSPARENT), 12)
            }
        }
    }

    private fun updateRows() {
        pickRow.visibility = if (tab <= 1) View.VISIBLE else View.GONE
        val showAction = tab == 3 || (tab == 4 && openPlaylist == null)
        btnAction.visibility = if (showAction) View.VISIBLE else View.GONE
        btnAction.text = if (tab == 3) "+ Gallery se files chunein" else "+ Nayi Playlist"
    }

    private fun updatePickerLabels() {
        btnMode.text = (if (mode == 0) "Mulk" else if (tab == 0) "Category" else "Tag") + "  ▾"
        if (curIdx in curItems.indices) {
            btnItem.text = curItems[curIdx].label + "  ▾"
        }
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

    // ---------- picker (dropdown ki jagah) ----------

    private fun showPicker(
        titleText: String,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val t = Themes.get(themeIdx)
        var dlg: AlertDialog? = null
        val rv = RecyclerView(this)
        val lm = LinearLayoutManager(this)
        rv.layoutManager = lm
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup, viewType: Int
            ): RecyclerView.ViewHolder {
                val tv = TextView(parent.context)
                val p = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                p.setMargins(dp(8), dp(3), dp(8), dp(3))
                tv.layoutParams = p
                tv.setPadding(dp(14), dp(12), dp(14), dp(12))
                tv.textSize = 16f
                tv.isFocusable = true
                tv.isClickable = true
                return object : RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val tv = h.itemView as TextView
                val sel = pos == selected
                tv.text = (if (sel) "✓  " else "     ") + labels[pos]
                tv.setTextColor(if (sel) t.accent else t.text)
                tv.background = stateBg(rr(t.card, 12), 12)
                tv.setOnClickListener {
                    dlg?.dismiss()
                    onPick(pos)
                }
            }

            override fun getItemCount(): Int = labels.size
        }
        val head = TextView(this)
        head.text = titleText
        head.textSize = 18f
        head.setTypeface(null, Typeface.BOLD)
        head.setTextColor(t.text)
        head.setPadding(dp(18), dp(16), dp(18), dp(8))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(head)
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val d = AlertDialog.Builder(this).setView(box).create()
        dlg = d
        d.window?.setBackgroundDrawable(rr(t.bg, 16))
        d.show()
        rv.post {
            lm.scrollToPositionWithOffset(selected, dp(120))
            rv.post { rv.findViewHolderForAdapterPosition(selected)?.itemView?.requestFocus() }
        }
    }

    // ---------- tabs aur lists ----------

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
        val saved = store.getString("sel${tab}_$mode", if (mode == 0) "pk" else "news")
        val idx = curItems.indexOfFirst { it.key == saved }
        curIdx = if (idx < 0) 0 else idx
        updatePickerLabels()
        loadList(curItems[curIdx])
    }

    private fun selectItem(pos: Int) {
        if (pos !in curItems.indices) return
        curIdx = pos
        val item = curItems[pos]
        store.putString("sel${tab}_$mode", item.key)
        updatePickerLabels()
        loadList(item)
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
        adapter.notifyDataSetChanged()
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

    // ---------- recording ----------

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

    // ---------- list adapter ----------

    inner class ChAdapter : RecyclerView.Adapter<ChAdapter.VH>() {
        var items: List<Channel> = emptyList()

        inner class VH(
            val row: LinearLayout,
            val logo: ImageView,
            val name: TextView,
            val heart: TextView
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val p = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            p.setMargins(dp(8), dp(4), dp(8), dp(4))
            row.layoutParams = p
            row.setPadding(dp(10), dp(8), dp(12), dp(8))
            row.isFocusable = true
            row.isClickable = true

            val iv = ImageView(ctx)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(2), dp(2), dp(2), dp(2))
            row.addView(iv, LinearLayout.LayoutParams(dp(46), dp(46)))

            val tv = TextView(ctx)
            tv.textSize = 16f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            tp.setMargins(dp(12), 0, dp(8), 0)
            row.addView(tv, tp)

            val heart = TextView(ctx)
            heart.text = "♥"
            heart.textSize = 16f
            row.addView(
                heart,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return VH(row, iv, tv, heart)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = Themes.get(themeIdx)
            val ch = items[position]
            val fav = favs.any { it.url == ch.url }
            val playing = ch.url == nowPlaying?.url

            h.row.background = stateBg(rr(t.card, 14), 14)
            h.name.text = ch.name
            h.name.setTextColor(if (playing) t.accent else t.text)
            h.name.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            h.heart.setTextColor(t.accent)
            h.heart.visibility = if (fav) View.VISIBLE else View.GONE
            h.logo.background = rr(withAlpha(t.sub, 0x22), 10)

            val icon = letterIcon(ch.name)
            if (ch.logo.isNotEmpty()) {
                Glide.with(h.logo)
                    .load(ch.logo)
                    .placeholder(icon)
                    .error(icon)
                    .override(132, 132)
                    .into(h.logo)
            } else {
                Glide.with(h.logo).clear(h.logo)
                h.logo.setImageDrawable(icon)
            }

            h.row.setOnClickListener { onItemClick(ch) }
            h.row.setOnLongClickListener {
                showItemMenu(ch)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
