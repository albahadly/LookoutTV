package com.albahadly.swanntv.probe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.sqrt

/**
 * SwannTV probe: measures what the TV can really do before the full app is built.
 *  - Device info: chip, RAM, ABI and the hardware decoder limits.
 *  - Grid tests: 4 / 9 / 16 live sub-streams with per-tile decoder, fps and dropped frames.
 *  - Switch test: OK grows the tile to full screen in place (its sub-stream never stops), then
 *    the 4K main stream fades in on top at its first frame. Left/right switches camera on the
 *    same main-stream player, so a second 4K decoder is never opened.
 * Logcat tag: SwannProbe
 */
class MainActivity : ComponentActivity() {

    private companion object {
        val ACCENT = Color.parseColor("#5B9BFF")
        val BG = Color.parseColor("#0E1116")
        val SURFACE = Color.parseColor("#1B2027")
        val TEXT = Color.parseColor("#E8EAED")
        val QUIET = Color.parseColor("#AEB6C2")
        val OVERLAY = Color.argb(200, 10, 12, 16)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tiles = mutableListOf<Tile>()
    private var tileCount = 4
    private var textureTiles = false
    private var tickCount = 0
    private var gridStartTick = 0

    private lateinit var root: FrameLayout
    private lateinit var deviceLine: TextView
    private lateinit var totalsLine: TextView
    private lateinit var grid: LinearLayout
    private lateinit var column: LinearLayout
    private lateinit var bar: LinearLayout
    private val rows = mutableListOf<LinearLayout>()
    private lateinit var infoPanel: ScrollView
    private lateinit var infoText: TextView

    // Full screen
    private lateinit var fullPanel: FrameLayout
    private lateinit var mainTexture: TextureView
    private lateinit var subTexture: TextureView
    private lateinit var fullText: TextView
    private var fullIndex = -1
    private var fullMain: StreamProbe? = null
    private var fullSub: StreamProbe? = null
    private var switchCount = 0
    private val mainTimes = mutableListOf<Long>()
    private var subMs = -1L
    private var mainMs = -1L
    private var expanded: Tile? = null
    private var mainShowing = false

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1_000)
        }
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(BG) }

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(20))
        }

        deviceLine = text(14f, QUIET).apply { maxLines = 2 }
        column.addView(deviceLine)

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        bar.addView(button("4 tiles") { buildGrid(4, texture = false) })
        bar.addView(button("9 tiles") { buildGrid(9, texture = false) })
        bar.addView(button("16 tiles") { buildGrid(16, texture = false) })
        // Experiment: tiles drawn by the app (TextureView) instead of one video layer each (SurfaceView).
        bar.addView(button("9 Texture") { buildGrid(9, texture = true) })
        bar.addView(button("16 Texture") { buildGrid(16, texture = true) })
        bar.addView(button("Device info") { showInfo() })
        totalsLine = text(16f, TEXT).apply { setPadding(dp(24), 0, 0, 0) }
        bar.addView(totalsLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bar)

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column, match())
        root.addView(buildFullPanel(), match())
        root.addView(buildInfoPanel(), match())
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        deviceLine.text = DeviceInfo.summary(this)
        ProbeLog.init(this)
        ProbeLog.write("\n" + DeviceInfo.report(this))
    }

    private fun buildFullPanel(): FrameLayout {
        fullPanel = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isFocusable = true
            setOnKeyListener { _, keyCode, event -> onFullscreenKey(keyCode, event) }
        }
        // Main stream underneath (invisible until its first frame); a sub-stream on top is only
        // used when switching camera, or as a fallback while the main stream recovers.
        mainTexture = TextureView(this)
        subTexture = TextureView(this)
        fullPanel.addView(mainTexture, match())
        fullPanel.addView(subTexture, match())
        fullText = text(18f, TEXT).apply {
            setBackgroundColor(OVERLAY)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        fullPanel.addView(fullText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(32), dp(24), 0, 0) })
        return fullPanel
    }

    private fun buildInfoPanel(): ScrollView {
        infoText = text(15f, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(40), dp(32), dp(40), dp(32))
        }
        infoPanel = ScrollView(this).apply {
            setBackgroundColor(BG)
            visibility = View.GONE
            isFocusable = true
            addView(infoText)
        }
        return infoPanel
    }

    private fun showInfo() {
        infoText.text = DeviceInfo.report(this) +
            "\nPress Back to return.\n\nTip: run  adb logcat -s SwannProbe  on your PC to copy these results."
        infoPanel.visibility = View.VISIBLE
        infoPanel.requestFocus()
    }

    // ---------------------------------------------------------------- Grid

    private inner class Tile(val index: Int, val camera: Camera, copy: Int, texture: Boolean) {
        val label = if (copy > 0) "${camera.name} #${copy + 1}" else camera.name
        val view = FrameLayout(this@MainActivity)
        val surface: View = if (texture) TextureView(this@MainActivity) else SurfaceView(this@MainActivity)
        val stats: TextView = text(13f, TEXT)
        val name: TextView = text(16f, TEXT)
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        var probe: StreamProbe? = null

        init {
            view.isFocusable = true
            view.isClickable = true
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.setBackgroundColor(SURFACE)
            view.foreground = focusRing()
            view.setOnClickListener { openFullscreen(index) }
            view.addView(surface, match())

            name.apply {
                text = label
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(OVERLAY)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            view.addView(name, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(dp(8), 0, 0, dp(8)) })

            stats.setBackgroundColor(OVERLAY)
            stats.setPadding(dp(8), dp(4), dp(8), dp(4))
            view.addView(stats, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply { setMargins(dp(8), dp(8), 0, 0) })
        }

        fun start() {
            stop()
            probe = StreamProbe(this@MainActivity, label, ProbeConfig.url(camera, main = false))
                .also { it.startOn(surface) }
        }

        fun stop() {
            probe?.release()
            probe = null
        }
    }

    private fun buildGrid(count: Int, texture: Boolean = textureTiles) {
        closeFullscreen(restartGrid = false)
        stopGrid()
        tiles.clear()
        rows.clear()
        grid.removeAllViews()
        tileCount = count
        textureTiles = texture

        val cols = sqrt(count.toDouble()).toInt()
        val rowCount = (count + cols - 1) / cols
        val cams = ProbeConfig.cameras
        for (r in 0 until rowCount) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rows.add(row)
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= count) break
                val tile = Tile(i, cams[i % cams.size], i / cams.size, texture)
                tiles.add(tile)
                tile.params.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(tile.view, tile.params)
            }
        }
        startGrid()
        tiles.firstOrNull()?.view?.post { tiles.firstOrNull()?.view?.requestFocus() }
        gridStartTick = tickCount
        ProbeLog.write("=== Grid test: $count tiles (${if (texture) "TextureView" else "SurfaceView"}) ===")
    }

    /** Starts tiles that aren't playing (a tile that kept playing in full screen is left alone). */
    private fun startGrid() = tiles.forEach { if (it.probe == null) it.start() }

    private fun stopGrid() = tiles.forEach { it.stop() }

    // ---------------------------------------------------------------- Full screen

    private fun openFullscreen(index: Int) {
        val t0 = SystemClock.elapsedRealtime()
        val tile = tiles.getOrNull(index) ?: return
        fullIndex = index
        switchCount++
        subMs = -1
        mainMs = -1
        mainShowing = false

        // Instant step: grow the tile itself. Its sub-stream keeps playing on the same surface,
        // so there is no gap at all (moving it to another surface cost ~400 ms on the TCL).
        expandTile(tile)

        // Only after that frame is on screen, free the other tiles' decoders. Releasing a player
        // blocks the main thread until its decoder closes (~130 ms each on the TCL), which is
        // what delayed the full-screen picture by ~470 ms when done first.
        // The panel stays see-through until the main stream has a frame.
        fullPanel.setBackgroundColor(Color.TRANSPARENT)
        mainTexture.alpha = 0f
        subTexture.visibility = View.GONE
        fullPanel.visibility = View.VISIBLE
        fullPanel.requestFocus()

        // Building the 4K player and closing the other tiles both block the main thread, so both
        // wait until the enlarged tile is on screen (on the TCL this held the picture ~500 ms).
        tile.view.onNextDraw {
            if (fullIndex != index) return@onNextDraw
            subMs = SystemClock.elapsedRealtime() - t0
            ProbeLog.write("open ${tile.label}: enlarged tile on screen after $subMs ms")
            playMain(tile, t0)
            tiles.forEach { if (it !== tile) it.stop() }
            updateFullText()
        }
    }

    /** Left/right in full screen. The main-stream player (and its 4K decoder) is reused. */
    private fun stepFullscreen(delta: Int) {
        if (tiles.isEmpty()) return
        val t0 = SystemClock.elapsedRealtime()
        fullIndex = (fullIndex + delta + tiles.size) % tiles.size
        val tile = tiles[fullIndex]
        switchCount++
        subMs = -1
        mainMs = -1
        mainShowing = false

        // The previous camera's picture must not linger: black until the new camera shows.
        expanded?.stop()
        mainTexture.alpha = 0f
        fullPanel.setBackgroundColor(Color.BLACK)
        startFallbackSub(tile)
        playMain(tile, t0)
    }

    /** Sub-stream on top of everything, used while a main stream is loading or recovering. */
    private fun startFallbackSub(tile: Tile) {
        fullSub?.release()
        subTexture.visibility = View.VISIBLE
        fullSub = StreamProbe(this, "${tile.label} sub", ProbeConfig.url(tile.camera, main = false)).also {
            it.onFirstFrame = { ms ->
                if (subMs < 0) subMs = ms
                updateFullText()
            }
            it.startOn(subTexture)
        }
    }

    private fun playMain(tile: Tile, t0: Long) {
        val url = ProbeConfig.url(tile.camera, main = true)
        val label = "${tile.label} main"
        val main = fullMain
        if (main == null) {
            fullMain = StreamProbe(this, label, url).also {
                it.onFirstFrame = { ms -> mainReady(ms) }
                it.onError = { mainFailed() }
                it.startOn(mainTexture)
            }
        } else {
            main.switchTo(url, label, t0)
        }
        updateFullText()
    }

    private fun mainReady(ms: Long) {
        val main = fullMain ?: return
        val tile = tiles.getOrNull(fullIndex) ?: return
        if (mainMs < 0) {
            mainMs = ms
            mainTimes.add(ms)
            if (subMs < 0) subMs = ms // the main stream was the first picture to appear
            ProbeLog.write("switch #$switchCount ${tile.camera.name}: picture=${subMs}ms main=${ms}ms " +
                "decoder=${main.decoderName} size=${main.resolution}")
        } else {
            ProbeLog.write("${main.label} recovered, picture back after $ms ms")
        }
        mainShowing = true
        mainTexture.alpha = 1f
        fullPanel.setBackgroundColor(Color.BLACK)
        expanded?.stop() // free the tile's sub-stream decoder
        fullSub?.release()
        fullSub = null
        subTexture.visibility = View.GONE
        updateFullText()
    }

    /** Keep a picture on screen: bring a sub-stream back until the main stream recovers. */
    private fun mainFailed() {
        val tile = tiles.getOrNull(fullIndex) ?: return
        val tileStillPlaying = expanded?.probe != null
        if (fullSub == null && !tileStillPlaying) {
            ProbeLog.write("${tile.label}: main stream failed, showing sub-stream meanwhile")
            mainShowing = false
            mainTexture.alpha = 0f
            fullPanel.setBackgroundColor(Color.BLACK)
            startFallbackSub(tile)
        }
        updateFullText()
    }

    private fun expandTile(tile: Tile) {
        expanded = tile
        deviceLine.visibility = View.GONE
        bar.visibility = View.GONE
        column.setPadding(0, 0, 0, 0)
        for (row in rows) row.visibility = if (tile.view.parent === row) View.VISIBLE else View.GONE
        for (t in tiles) if (t !== tile) t.view.visibility = View.GONE
        tile.params.setMargins(0, 0, 0, 0)
        tile.view.layoutParams = tile.params
        tile.stats.visibility = View.GONE
        tile.name.visibility = View.GONE
    }

    private fun restoreGrid() {
        val tile = expanded ?: return
        expanded = null
        deviceLine.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        column.setPadding(dp(32), dp(20), dp(32), dp(20))
        rows.forEach { it.visibility = View.VISIBLE }
        tiles.forEach { it.view.visibility = View.VISIBLE }
        tile.params.setMargins(dp(4), dp(4), dp(4), dp(4))
        tile.view.layoutParams = tile.params
        tile.stats.visibility = View.VISIBLE
        tile.name.visibility = View.VISIBLE
    }

    private fun updateFullText() {
        val tile = tiles.getOrNull(fullIndex) ?: return
        val main = fullMain
        val avg = if (mainTimes.isEmpty()) "-" else "${mainTimes.average().toLong()} ms over ${mainTimes.size}"
        fullText.text = buildString {
            appendLine("${tile.label}  (switch #$switchCount)")
            appendLine("Picture full screen:   ${if (subMs >= 0) "$subMs ms" else "..."}")
            appendLine("4K main stream sharp:  ${if (mainMs >= 0) "$mainMs ms" else "..."}")
            appendLine("Average main:          $avg")
            if (main != null) {
                appendLine("Main: ${main.statusLine()}  ${main.decoderName ?: ""}")
                if (main.rebuilds > 0) appendLine("Player rebuilt ${main.rebuilds}x after decoder errors")
            }
            if (fullSub != null && mainShowing.not() && mainMs >= 0) appendLine("Showing sub-stream while main recovers")
            append("< > other camera    OK / Back: grid")
        }
    }

    private fun closeFullscreen(restartGrid: Boolean = true) {
        if (fullIndex < 0) return
        val index = fullIndex
        fullIndex = -1
        fullMain?.release()
        fullMain = null
        fullSub?.release()
        fullSub = null
        mainShowing = false
        fullPanel.visibility = View.GONE
        restoreGrid()
        if (restartGrid) {
            startGrid()
            tiles.getOrNull(index)?.view?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- Stats

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        tickCount++

        var live = 0
        var hw = 0
        var sw = 0
        var errors = 0
        var totalFps = 0.0
        var totalDropped = 0
        for (tile in tiles) {
            val p = tile.probe ?: continue
            p.tick(now)
            tile.stats.text = p.statusLine()
            when (p.state) {
                StreamProbe.State.LIVE -> live++
                StreamProbe.State.ERROR -> errors++
                else -> Unit
            }
            when (p.isHardware) {
                true -> hw++
                false -> sw++
                null -> Unit
            }
            totalFps += p.fps
            totalDropped += p.dropped
        }

        fullSub?.tick(now)
        fullMain?.let { main ->
            main.tick(now)
            updateFullText()
        }

        val totals = String.format(
            Locale.US, "%d tiles | live %d | HW %d | SW %d | errors %d | %.0f fps total | dropped %d | app CPU %s",
            tiles.size, live, hw, sw, errors, totalFps, totalDropped, cpuUsage(now),
        )
        totalsLine.text = totals
        if (fullIndex < 0) {
            if (tickCount % 5 == 0) ProbeLog.write(totals)
            // 15 s into each grid test: record every tile's state once.
            if (tickCount - gridStartTick == 15 || tickCount - gridStartTick == 30) {
                ProbeLog.write("--- $tileCount-tile ${if (textureTiles) "TextureView" else "SurfaceView"} snapshot after ${tickCount - gridStartTick} s ---")
                tiles.forEach { t -> ProbeLog.write("  ${t.label}: ${t.probe?.statusLine() ?: "stopped"}  ${t.probe?.decoderName ?: ""}") }
            }
        }
    }

    private var lastCpuTicks = -1L
    private var lastCpuAt = 0L

    /**
     * This app's CPU use since the last call, as % of one core and of the whole chip
     * (e.g. "180% = 45% of 4 cores"). High = CPU-bound; low while frames drop = compositor/GPU-bound.
     */
    private fun cpuUsage(nowMs: Long): String {
        val ticks = try {
            val fields = java.io.File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
            fields[11].toLong() + fields[12].toLong() // utime + stime, in 1/100 s
        } catch (e: Exception) {
            return "n/a"
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val result = if (lastCpuTicks < 0 || nowMs <= lastCpuAt) "..." else {
            val pctOfCore = (ticks - lastCpuTicks) * 10.0 * 1000.0 / (nowMs - lastCpuAt) / 10.0
            String.format(Locale.US, "%.0f%% = %.0f%% of %d cores", pctOfCore, pctOfCore / cores, cores)
        }
        lastCpuTicks = ticks
        lastCpuAt = nowMs
        return result
    }

    // ---------------------------------------------------------------- Keys & lifecycle

    /** Back: close the info screen, then full screen, then quit. */
    private fun handleBack() {
        when {
            infoPanel.visibility == View.VISIBLE -> {
                infoPanel.visibility = View.GONE
                tiles.firstOrNull()?.view?.requestFocus()
            }
            fullIndex >= 0 -> closeFullscreen()
            else -> finish()
        }
    }

    /** Remote keys while full screen (the full-screen panel holds focus). */
    private fun onFullscreenKey(keyCode: Int, event: KeyEvent): Boolean {
        if (fullIndex < 0) return false
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down) closeFullscreen()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) stepFullscreen(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) stepFullscreen(+1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true // keep focus here
            else -> false
        }
    }

    override fun onStart() {
        super.onStart()
        buildGrid(tileCount)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        closeFullscreen(restartGrid = false)
        stopGrid()
        super.onStop()
    }

    // ---------------------------------------------------------------- Helpers

    /** Runs [action] once, right after the next frame containing this view has been drawn. */
    private fun View.onNextDraw(action: () -> Unit) {
        val observer = viewTreeObserver
        var fired = false
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (fired) return
                fired = true
                val self = this
                // A draw listener can't remove itself during onDraw, so finish on the next pass.
                handler.post {
                    if (observer.isAlive) observer.removeOnDrawListener(self)
                    action()
                }
            }
        }
        observer.addOnDrawListener(listener)
        invalidate()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun text(sizeSp: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun focusRing(): StateListDrawable = StateListDrawable().apply {
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(4), ACCENT)
        }
        addState(intArrayOf(android.R.attr.state_focused), ring)
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
}
