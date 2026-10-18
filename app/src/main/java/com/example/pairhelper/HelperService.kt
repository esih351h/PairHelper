package com.example.pairhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import java.nio.ByteBuffer

class OverlayView(ctx: Context, private val grid: () -> FloatArray) : View(ctx) {
    var pairId = IntArray(HelperService.N)
    var alive = BooleanArray(HelperService.N) { true }
    var showGrid = false
    var showBadges = true
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val line = Paint().apply { style = Paint.Style.STROKE; color = 0xFF00FF00.toInt(); strokeWidth = 2f }

    override fun onDraw(c: Canvas) {
        val g = grid()
        val w = width.toFloat(); val h = height.toFloat()
        val cw = (g[2] - g[0]) * w / HelperService.COLS
        val ch = (g[3] - g[1]) * h / HelperService.ROWS
        for (i in 0 until HelperService.N) {
            val x = g[0] * w + (i % HelperService.COLS) * cw
            val y = g[1] * h + (i / HelperService.COLS) * ch
            if (showGrid) c.drawRect(x, y, x + cw, y + ch, line)
            val p = pairId[i]
            if (showBadges && p > 0 && alive[i]) {
                val cx = x + cw / 2; val cy = y + ch * 0.22f; val r = minOf(cw, ch) * 0.17f
                fill.color = HelperService.PALETTE[(p - 1) % HelperService.PALETTE.size]
                stroke.strokeWidth = r * 0.15f
                c.drawCircle(cx, cy, r, fill); c.drawCircle(cx, cy, r, stroke)
                txt.textSize = r * 1.15f
                c.drawText(p.toString(), cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
            }
        }
    }
}

class HelperService : Service() {
    companion object {
        const val COLS = 8
        const val ROWS = 3
        const val N = 24
        val PALETTE = intArrayOf(
            0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFD81B60.toInt(), 0xFFC0CA33.toInt(),
            0xFF6D4C41.toInt(), 0xFF00897B.toInt(), 0xFFFF7043.toInt(), 0xFF546E7A.toInt()
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private lateinit var overlay: OverlayView
    private lateinit var panel: View
    private lateinit var statusView: TextView

    @Volatile private var gx0 = 0.1646f
    @Volatile private var gy0 = 0.192f
    @Volatile private var gx1 = 0.8594f
    @Volatile private var gy1 = 0.973f

    // state used on the background thread only
    private var mode = 0 // 0 = armed/idle, 1 = scanning
    private var manual = false
    private var wasLow = false
    private var template: IntArray? = null
    private var lastProc = 0L
    private var scanStart = 0L
    private var allSince = 0L
    private val prevDesc = arrayOfNulls<IntArray>(N)
    private val stable = IntArray(N)
    private val bestRun = IntArray(N)
    private val best = arrayOfNulls<IntArray>(N)
    private var lastAlive = BooleanArray(N) { true }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun grid() = floatArrayOf(gx0, gy0, gx1, gy1)
    private fun status(t: String) { ui.post { statusView.text = t } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (projection != null || i == null) return START_NOT_STICKY
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("helper", "Pair helper", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "helper")
            .setContentTitle("Pair helper is running")
            .setContentText("Use X on the panel to stop")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val p = getSharedPreferences("grid", MODE_PRIVATE)
        gx0 = p.getFloat("x0", gx0); gy0 = p.getFloat("y0", gy0)
        gx1 = p.getFloat("x1", gx1); gy1 = p.getFloat("y1", gy1)

        bgThread = HandlerThread("cap").also { it.start() }
        bg = Handler(bgThread.looper)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { stopSelf() } }
        }, ui)

        addWindows()
        setupCapture()
        status("Armed")
        return START_NOT_STICKY
    }

    private fun realSize(): IntArray {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun addWindows() {
        val s = realSize()
        overlay = OverlayView(this) { grid() }
        val lp = WindowManager.LayoutParams(
            s[0], s[1], WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(overlay, lp)

        panel = buildPanel()
        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        plp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        wm.addView(panel, plp)
    }

    private fun nudge(a: Float, b: Float, c: Float, d: Float) {
        gx0 += a; gy0 += b; gx1 += c; gy1 += d
        getSharedPreferences("grid", MODE_PRIVATE).edit()
            .putFloat("x0", gx0).putFloat("y0", gy0).putFloat("x1", gx1).putFloat("y1", gy1).apply()
        overlay.invalidate()
    }

    private fun buildPanel(): View {
        val g = GridLayout(this).apply {
            columnCount = 2
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        fun b(t: String, f: () -> Unit) {
            g.addView(Button(this).apply {
                text = t; textSize = 11f; isAllCaps = false
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { f() }
                layoutParams = GridLayout.LayoutParams().apply { width = dp(44); height = dp(32); setMargins(1, 1, 1, 1) }
            })
        }
        val s = 0.004f
        b("Scan") { refreshGeometry(); overlay.showGrid = false; bg.post { manual = true } }
        b("Grid") { overlay.showGrid = !overlay.showGrid; overlay.invalidate() }
        b("◀") { nudge(-s, 0f, -s, 0f) }
        b("▶") { nudge(s, 0f, s, 0f) }
        b("▲") { nudge(0f, -s, 0f, -s) }
        b("▼") { nudge(0f, s, 0f, s) }
        b("W+") { nudge(-s / 2, 0f, s / 2, 0f) }
        b("W−") { nudge(s / 2, 0f, -s / 2, 0f) }
        b("H+") { nudge(0f, -s / 2, 0f, s / 2) }
        b("H−") { nudge(0f, s / 2, 0f, -s / 2) }
        b("Hide") { overlay.showBadges = !overlay.showBadges; overlay.invalidate() }
        b("✕") { stopSelf() }
        statusView = TextView(this).apply {
            textSize = 10f; setTextColor(Color.WHITE)
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 2)).apply {
                width = dp(90); height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        g.addView(statusView)
        return g
    }

    private fun refreshGeometry() {
        val s = realSize()
        try {
            val lp = overlay.layoutParams as WindowManager.LayoutParams
            lp.width = s[0]; lp.height = s[1]
            wm.updateViewLayout(overlay, lp)
        } catch (e: Exception) { }
        setupCapture()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ui.postDelayed({ refreshGeometry() }, 300)
    }

    private fun setupCapture() {
        val s = realSize()
        val w = s[0] / 2
        val h = s[1] / 2
        if (w == capW && h == capH && vd != null) return
        val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        nr.setOnImageAvailableListener({ r -> onImage(r) }, bg)
        if (vd == null) {
            vd = projection!!.createVirtualDisplay(
                "pairhelper", w, h, s[2], DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, nr.surface, null, null
            )
        } else {
            vd!!.resize(w, h, s[2])
            vd!!.surface = nr.surface
        }
        reader?.close()
        reader = nr
        capW = w; capH = h
    }

    // ---------- frame handling (background thread) ----------

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val gap = if (mode == 1) 70L else 400L
            if (now - lastProc >= gap) { lastProc = now; handle(img, now) }
        } catch (e: Exception) {
        } finally {
            img.close()
        }
    }

    private fun cellRect(c: Int, w: Int, h: Int, g: FloatArray): FloatArray {
        val cw = (g[2] - g[0]) * w / COLS
        val ch = (g[3] - g[1]) * h / ROWS
        return floatArrayOf(g[0] * w + (c % COLS) * cw, g[1] * h + (c / COLS) * ch, cw, ch)
    }

    private fun sampleRegion(
        buf: ByteBuffer, rs: Int, w: Int, h: Int, rect: FloatArray,
        fx0: Float, fy0: Float, fx1: Float, fy1: Float, nx: Int, ny: Int
    ): IntArray {
        val out = IntArray(nx * ny * 3)
        val rx = rect[0] + rect[2] * fx0; val ry = rect[1] + rect[3] * fy0
        val rw = rect[2] * (fx1 - fx0); val rh = rect[3] * (fy1 - fy0)
        val sx = maxOf(1, (rw / nx / 3).toInt()); val sy = maxOf(1, (rh / ny / 3).toInt())
        var k = 0
        for (j in 0 until ny) for (i in 0 until nx) {
            val cx = (rx + (i + 0.5f) * rw / nx).toInt(); val cy = (ry + (j + 0.5f) * rh / ny).toInt()
            var r = 0; var gg = 0; var b = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val x = (cx + dx * sx).coerceIn(0, w - 1); val y = (cy + dy * sy).coerceIn(0, h - 1)
                val idx = y * rs + x * 4
                r += buf.get(idx).toInt() and 255
                gg += buf.get(idx + 1).toInt() and 255
                b += buf.get(idx + 2).toInt() and 255
                n++
            }
            out[k++] = r / n; out[k++] = gg / n; out[k++] = b / n
        }
        return out
    }

    private fun mad(a: IntArray, b: IntArray): Float {
        var s = 0
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s.toFloat() / a.size
    }

    private fun median(v: Array<IntArray>): IntArray {
        val m = IntArray(v[0].size)
        val tmp = IntArray(v.size)
        for (k in m.indices) {
            for (c in v.indices) tmp[c] = v[c][k]
            tmp.sort()
            m[k] = tmp[tmp.size / 2]
        }
        return m
    }

    private fun handle(img: Image, now: Long) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val w = img.width; val h = img.height
        val g = grid()
        // small sample of the centre-lower part of each card: the card back is the same everywhere
        val vecs = Array(N) { c ->
            sampleRegion(buf, rs, w, h, cellRect(c, w, h, g), 0.25f, 0.45f, 0.75f, 0.90f, 6, 6)
        }
        if (mode == 1) scanStep(now, vecs, buf, rs, w, h, g) else idleStep(now, vecs)
    }

    private fun idleStep(now: Long, vecs: Array<IntArray>) {
        val med = median(vecs)
        var similar = 0
        for (c in 0 until N) if (mad(vecs[c], med) < 14f) similar++
        var mr = 0; var mb = 0; var k = 0
        while (k < med.size) { mr += med[k]; mb += med[k + 2]; k += 3 }
        val backLike = mb > mr + 8 * (med.size / 3)
        val pristine = similar >= 20 && backLike
        if (pristine) template = med
        val tpl = template
        var down = 0
        if (tpl != null) {
            val al = BooleanArray(N) { c -> mad(vecs[c], tpl) < 22f }
            down = al.count { it }
            if (!al.contentEquals(lastAlive)) {
                lastAlive = al
                ui.post { overlay.alive = al; overlay.invalidate() }
            }
        }
        if (!pristine && (tpl == null || down <= 10)) wasLow = true
        if (manual || (pristine && wasLow)) {
            val useTpl = if (pristine) med else (tpl ?: med)
            manual = false
            startScan(now, useTpl)
        }
    }

    private fun startScan(now: Long, tpl: IntArray) {
        mode = 1
        template = tpl
        scanStart = now; allSince = 0L
        for (c in 0 until N) { prevDesc[c] = null; stable[c] = 0; bestRun[c] = 0; best[c] = null }
        ui.post {
            overlay.pairId = IntArray(N)
            overlay.showGrid = false
            overlay.invalidate()
        }
        status("Scanning...")
    }

    private fun scanStep(now: Long, vecs: Array<IntArray>, buf: ByteBuffer, rs: Int, w: Int, h: Int, g: FloatArray) {
        val tpl = template!!
        if (now - scanStart < 200) return
        for (c in 0 until N) {
            val d = mad(vecs[c], tpl)
            if (d > 30f) {
                val fd = sampleRegion(buf, rs, w, h, cellRect(c, w, h, g), 0.08f, 0.05f, 0.92f, 0.95f, 12, 18)
                val pv = prevDesc[c]
                stable[c] = if (pv != null && mad(fd, pv) < 8f) stable[c] + 1 else 1
                prevDesc[c] = fd
                if (stable[c] >= 3 && stable[c] > bestRun[c]) { bestRun[c] = stable[c]; best[c] = fd }
            } else {
                stable[c] = 0; prevDesc[c] = null
            }
        }
        val got = best.count { it != null }
        if (got == N && allSince == 0L) allSince = now
        status("Scanning $got/$N")
        if ((allSince > 0 && now - allSince > 400) || now - scanStart > 15000) finishScan(got)
    }

    private fun finishScan(got: Int) {
        mode = 0
        wasLow = false
        val ids = (0 until N).filter { best[it] != null }
        val dm = Array(N) { FloatArray(N) }
        for (a in ids) for (b in ids) if (a != b) dm[a][b] = mad(best[a]!!, best[b]!!)
        val pairs = matchPairs(ids, dm)
        pairs.sortBy { minOf(it[0], it[1]) }
        val pid = IntArray(N)
        pairs.forEachIndexed { i, p -> pid[p[0]] = i + 1; pid[p[1]] = i + 1 }
        lastAlive = BooleanArray(N) { true }
        ui.post {
            overlay.pairId = pid
            overlay.alive = BooleanArray(N) { true }
            overlay.invalidate()
        }
        status(if (pairs.isEmpty()) "Scan failed ($got/$N). Try Scan again." else "${pairs.size} pairs ($got/$N seen)")
    }

    private fun matchPairs(ids: List<Int>, d: Array<FloatArray>): MutableList<IntArray> {
        val cand = ArrayList<Triple<Float, Int, Int>>()
        for (x in ids.indices) for (y in x + 1 until ids.size) cand.add(Triple(d[ids[x]][ids[y]], ids[x], ids[y]))
        cand.sortBy { it.first }
        val used = HashSet<Int>()
        val pairs = ArrayList<IntArray>()
        for ((_, a, b) in cand) {
            if (a !in used && b !in used) { used.add(a); used.add(b); pairs.add(intArrayOf(a, b)) }
        }
        var improved = true
        var guard = 0
        while (improved && guard++ < 50) {
            improved = false
            for (i in pairs.indices) for (j in i + 1 until pairs.size) {
                val a = pairs[i][0]; val b = pairs[i][1]; val c = pairs[j][0]; val e = pairs[j][1]
                val cur = d[a][b] + d[c][e]
                val alt1 = d[a][c] + d[b][e]
                val alt2 = d[a][e] + d[b][c]
                if (alt1 < cur - 0.01f && alt1 <= alt2) {
                    pairs[i] = intArrayOf(a, c); pairs[j] = intArrayOf(b, e); improved = true
                } else if (alt2 < cur - 0.01f) {
                    pairs[i] = intArrayOf(a, e); pairs[j] = intArrayOf(b, c); improved = true
                }
            }
        }
        return pairs
    }

    override fun onDestroy() {
        try { wm.removeView(panel) } catch (e: Exception) { }
        try { wm.removeView(overlay) } catch (e: Exception) { }
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { bgThread.quitSafely() } catch (e: Exception) { }
        super.onDestroy()
    }
}
