package id.imzyy.xl

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity
import org.json.JSONArray
import org.json.JSONObject

/** One on-screen button. x/y are the button centre as a fraction of the screen. [key] may be a combo like "CTRL+C" (keys joined with +). */
data class PadBtn(val id: String, val label: String, val key: String, val mode: String, val x: Float, val y: Float, val hidden: Boolean = false)

object PadKeys {
    class K(val glfw: Int, val droid: Int)

    val map: Map<String, K> = LinkedHashMap<String, K>().apply {
        for (c in 'A'..'Z') put(c.toString(), K(c.code, 29 + (c - 'A')))
        for (d in 1..9) put(d.toString(), K(48 + d, 7 + d))
        put("SPACE", K(32, 62)); put("SHIFT", K(340, 59)); put("CTRL", K(341, 113)); put("ALT", K(342, 57))
        put("TAB", K(258, 61)); put("ESC", K(256, 111)); put("ENTER", K(257, 66))
        put("F1", K(290, 131)); put("F3", K(292, 133)); put("F5", K(294, 135))
        put("F2", K(291, 132)); put("F4", K(293, 134)); put("F11", K(300, 141))
        put("SLASH", K(47, 76)); put("BACKSPACE", K(259, 67))
        put("UP", K(265, 19)); put("DOWN", K(264, 20)); put("LEFT", K(263, 21)); put("RIGHT", K(262, 22))
    }
    val mouse = listOf("LMB", "RMB", "MMB")
    val order: List<String> = mouse + map.keys.toList()

    fun short(k: String) = when (k) {
        "SPACE" -> "SPC"; "SHIFT" -> "SHF"; "CTRL" -> "CTL"; "ENTER" -> "ENT"
        "LMB" -> "L"; "RMB" -> "R"; "MMB" -> "M"
        "SLASH" -> "/"; "BACKSPACE" -> "BKS"; "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"
        else -> k
    }

    /** Label for a single key or a "A+B" combo. */
    fun label(key: String) = key.split("+").joinToString("+") { short(it) }
}

/** Shared by the Hub (edits) and the game process (reads) through a JSON file in filesDir. */
object ImzyyPad {
    private const val FILE = "imzyy_pad.json"
    const val HOLD_MS = 350L
    val MODES = listOf("PRESS", "TOGGLE", "AUTO")

    var enabled by mutableStateOf(true)
    var alwaysShow by mutableStateOf(true)
    var size by mutableStateOf(1.0f)
    var opacity by mutableStateOf(0.8f)
    val buttons = mutableStateListOf<PadBtn>()

    fun modeName(m: String) = when (m) {
        "TOGGLE" -> "TAHAN (ketuk kunci)"
        "AUTO" -> "KLIK / TAHAN LAMA"
        else -> "TEKAN"
    }

    private fun b(key: String, mode: String, x: Float, y: Float) =
        PadBtn("d_" + key.lowercase(), PadKeys.label(key), key, mode, x, y)

    fun defaults(): List<PadBtn> = listOf(
        b("W", "PRESS", 0.13f, 0.56f), b("A", "PRESS", 0.07f, 0.74f), b("S", "PRESS", 0.13f, 0.92f), b("D", "PRESS", 0.19f, 0.74f),
        b("LMB", "AUTO", 0.88f, 0.74f), b("RMB", "AUTO", 0.78f, 0.88f),
        b("SPACE", "PRESS", 0.92f, 0.52f), b("SHIFT", "TOGGLE", 0.78f, 0.60f), b("E", "PRESS", 0.62f, 0.90f),
        b("Q", "PRESS", 0.52f, 0.90f), b("F3", "TOGGLE", 0.30f, 0.12f), b("F5", "PRESS", 0.38f, 0.12f),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context) {
        try {
            val f = file(ctx)
            if (!f.exists()) { resetList(); return }
            val o = JSONObject(f.readText())
            if (o.optInt("ver", 1) < 2) { resetList(); return }
            enabled = o.optBoolean("enabled", true)
            alwaysShow = o.optBoolean("alwaysShow", true)
            size = o.optDouble("size", 1.0).toFloat()
            opacity = o.optDouble("opacity", 0.8).toFloat()
            val arr = o.optJSONArray("buttons") ?: JSONArray()
            val list = ArrayList<PadBtn>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                list.add(PadBtn(j.getString("id"), j.optString("label", j.getString("key")), j.getString("key"),
                    j.optString("mode", "PRESS"), j.optDouble("x", 0.5).toFloat(), j.optDouble("y", 0.5).toFloat(),
                    j.optBoolean("hidden", false)))
            }
            buttons.clear(); buttons.addAll(list)
        } catch (_: Exception) {
            resetList()
        }
    }

    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (b in buttons) arr.put(JSONObject().put("id", b.id).put("label", b.label).put("key", b.key)
                .put("mode", b.mode).put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("hidden", b.hidden))
            file(ctx).writeText(JSONObject().put("ver", 2).put("enabled", enabled).put("alwaysShow", alwaysShow)
                .put("size", size.toDouble()).put("opacity", opacity.toDouble()).put("buttons", arr).toString())
        } catch (_: Exception) {
        }
    }

    private fun resetList() { buttons.clear(); buttons.addAll(defaults()) }

    fun reset(ctx: Context) { resetList(); enabled = true; alwaysShow = true; size = 1.0f; opacity = 0.8f; save(ctx) }

    fun add(ctx: Context, key: String) {
        val mode = if (key.endsWith("MB")) "AUTO" else "PRESS"
        buttons.add(PadBtn("u" + System.currentTimeMillis(), PadKeys.label(key), key, mode, 0.5f, 0.5f))
        save(ctx)
    }

    fun remove(ctx: Context, id: String) { buttons.removeAll { it.id == id }; save(ctx) }

    fun cycleMode(ctx: Context, id: String) {
        val i = buttons.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = buttons[i]
        buttons[i] = cur.copy(mode = MODES[(MODES.indexOf(cur.mode) + 1) % MODES.size])
        save(ctx)
    }

    fun move(ctx: Context, id: String, x: Float, y: Float) {
        val i = buttons.indexOfFirst { it.id == id }
        if (i < 0) return
        buttons[i] = buttons[i].copy(x = x, y = y)
        save(ctx)
    }

    fun toggleHidden(ctx: Context, id: String) {
        val i = buttons.indexOfFirst { it.id == id }
        if (i < 0) return
        buttons[i] = buttons[i].copy(hidden = !buttons[i].hidden)
        save(ctx)
    }

    fun setAllHidden(ctx: Context, hidden: Boolean) {
        for (i in buttons.indices) buttons[i] = buttons[i].copy(hidden = hidden)
        save(ctx)
    }

    /** Adds [key] to the button's combo ("W" + "SHIFT" -> "W+SHIFT"). All keys are pressed together. */
    fun addKeyTo(ctx: Context, id: String, key: String) {
        val i = buttons.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = buttons[i]
        if (cur.key.split("+").contains(key)) return
        val nk = cur.key + "+" + key
        buttons[i] = cur.copy(key = nk, label = PadKeys.label(nk))
        save(ctx)
    }

    /** Back to the first key only. */
    fun clearCombo(ctx: Context, id: String) {
        val i = buttons.indexOfFirst { it.id == id }
        if (i < 0) return
        val first = buttons[i].key.substringBefore('+')
        buttons[i] = buttons[i].copy(key = first, label = PadKeys.label(first))
        save(ctx)
    }

    private const val TAG = "imzyy_pad"

    fun attach(a: MinecraftActivity) {
        load(a)
        val root = a.window.decorView as? ViewGroup ?: return
        val existing = root.findViewWithTag<View>(TAG) as? PadView
        if (existing != null) { existing.rebuild(); return }
        val v = PadView(a, a)
        v.tag = TAG
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun releaseAll(a: MinecraftActivity) {
        val root = a.window.decorView as? ViewGroup ?: return
        (root.findViewWithTag<View>(TAG) as? PadView)?.release()
    }
}

@SuppressLint("ViewConstructor")
class PadView(ctx: Context, private val act: MinecraftActivity) : FrameLayout(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val dp = ctx.resources.displayMetrics.density
    private val btns = ArrayList<PadButtonView>()
    private val editBtn = TextView(ctx)
    private val editBar = LinearLayout(ctx)
    var editMode = false

    private val tick = object : Runnable {
        override fun run() {
            val show = ImzyyPad.enabled && (ImzyyPad.alwaysShow || !act.isGrabbing)
            val vis = if (show) View.VISIBLE else View.GONE
            if (visibility != vis) {
                visibility = vis
                if (!show) release()
            }
            h.postDelayed(this, 300)
        }
    }

    init {
        isMotionEventSplittingEnabled = true
        clipChildren = false
        editBtn.text = "PAD"
        editBtn.setTextColor(Color.WHITE)
        editBtn.textSize = 11f
        editBtn.gravity = Gravity.CENTER
        editBtn.setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        editBtn.background = GradientDrawable().apply {
            setColor(Color.argb(140, 0, 0, 0)); cornerRadius = 40 * dp; setStroke((1 * dp).toInt().coerceAtLeast(1), Color.argb(120, 255, 255, 255))
        }
        editBtn.setOnClickListener { toggleEdit() }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = (8 * dp).toInt()
        lp.marginEnd = (84 * dp).toInt()
        addView(editBtn, lp)

        val modsBtn = PadUi.pill(context, "MODS") { release(); ModsPanel(act).show() }
        val mlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.gravity = Gravity.TOP or Gravity.END
        mlp.topMargin = (8 * dp).toInt()
        mlp.marginEnd = (152 * dp).toInt()
        addView(modsBtn, mlp)

        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.visibility = View.GONE
        val gap = (6 * dp).toInt()
        for ((txt, act2) in listOf<Pair<String, () -> Unit>>(
            "+ TOMBOL" to { openAdd() },
            "DAFTAR / SEMBUNYI" to { openList() },
            "RESET" to { ImzyyPad.reset(context); rebuild() },
        )) {
            val c = PadUi.pill(context, txt) { act2() }
            val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clp.marginStart = gap
            editBar.addView(c, clp)
        }
        val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        blp.topMargin = (52 * dp).toInt()
        addView(editBar, blp)
        rebuild()
    }

    fun rebuild() {
        for (v in btns) removeView(v)
        btns.clear()
        val px = (56 * dp * ImzyyPad.size).toInt()
        for (m in ImzyyPad.buttons.toList()) {
            val v = PadButtonView(context, act, m, this)
            v.visibility = if (m.hidden && !editMode) View.GONE else View.VISIBLE
            btns.add(v)
            addView(v, FrameLayout.LayoutParams(px, px))
        }
        requestLayout()
    }

    /** Edit mode: tapping a button (without dragging) opens its editor. */
    fun editTap(m: PadBtn) {
        val cur = ImzyyPad.buttons.firstOrNull { it.id == m.id } ?: return
        openEditor(cur)
    }

    private fun refreshAfterChange() {
        rebuild()
        for (v in btns) v.invalidate()
    }

    private fun openEditor(m: PadBtn) {
        PadUi.panel(context, 0.8f, 0.9f, true) { col, d ->
            col.addView(PadUi.text(context, "Tombol  " + m.label, 16f, Color.WHITE, true))
            col.addView(PadUi.text(context, "Kombinasi: " + m.key.replace("+", "  +  ") + (if (m.hidden) "   (disembunyikan)" else ""), 12f, Color.argb(200, 255, 255, 255), false))
            col.addView(PadUi.text(context, "Mode: " + ImzyyPad.modeName(m.mode), 12f, Color.argb(200, 255, 255, 255), false))
            val row = PadUi.row(context)
            row.addView(PadUi.pill(context, "GANTI MODE", false, 10) { ImzyyPad.cycleMode(context, m.id); d.dismiss(); refreshAfterChange(); editTap(m) }, PadUi.weight())
            row.addView(PadUi.pill(context, if (m.hidden) "TAMPILKAN" else "SEMBUNYIKAN", false, 10) { ImzyyPad.toggleHidden(context, m.id); d.dismiss(); refreshAfterChange() }, PadUi.weight())
            row.addView(PadUi.pill(context, "HAPUS", false, 10) { ImzyyPad.remove(context, m.id); d.dismiss(); refreshAfterChange() }, PadUi.weight())
            col.addView(row)
            col.addView(PadUi.text(context, "Gabung tombol lain ke tombol ini (ditekan bersamaan). Contoh: CTRL + C.", 12f, Color.argb(200, 255, 255, 255), false))
            col.addView(PadUi.keyGrid(context, 7) { k -> ImzyyPad.addKeyTo(context, m.id, k); d.dismiss(); refreshAfterChange(); editTap(m) })
            val row2 = PadUi.row(context)
            row2.addView(PadUi.pill(context, "HAPUS GABUNGAN", false, 10) { ImzyyPad.clearCombo(context, m.id); d.dismiss(); refreshAfterChange(); editTap(m) }, PadUi.weight())
            row2.addView(PadUi.pill(context, "TUTUP", true, 10) { d.dismiss() }, PadUi.weight())
            col.addView(row2)
        }
    }

    private fun openAdd() {
        val picked = ArrayList<String>()
        PadUi.panel(context, 0.8f, 0.9f, true) { col, d ->
            col.addView(PadUi.text(context, "Tambah tombol", 16f, Color.WHITE, true))
            col.addView(PadUi.text(context, "Pilih satu tombol, atau beberapa untuk digabung (ditekan bersamaan), mis. CTRL lalu C.", 12f, Color.argb(200, 255, 255, 255), false))
            val sel = PadUi.text(context, "Pilihan: -", 14f, Color.WHITE, true)
            col.addView(sel)
            col.addView(PadUi.keyGrid(context, 7) { k ->
                if (!picked.remove(k)) picked.add(k)
                sel.text = "Pilihan: " + (if (picked.isEmpty()) "-" else picked.joinToString(" + "))
            })
            val row = PadUi.row(context)
            row.addView(PadUi.pill(context, "BUAT TOMBOL", true, 10) {
                if (picked.isEmpty()) {
                    Toast.makeText(context, "Pilih minimal satu tombol", Toast.LENGTH_SHORT).show()
                } else {
                    ImzyyPad.add(context, picked.joinToString("+"))
                    d.dismiss()
                    refreshAfterChange()
                }
            }, PadUi.weight())
            row.addView(PadUi.pill(context, "BATAL", false, 10) { d.dismiss() }, PadUi.weight())
            col.addView(row)
        }
    }

    private fun openList() {
        PadUi.panel(context, 0.8f, 0.9f, true) { col, d ->
            col.addView(PadUi.text(context, "Tombol IMZYY", 16f, Color.WHITE, true))
            col.addView(PadUi.text(context, "Sembunyikan tombol yang menabrak tombol lain. Tombol tersembunyi tetap bisa dimunculkan lagi dari sini.", 12f, Color.argb(200, 255, 255, 255), false))
            val top = PadUi.row(context)
            top.addView(PadUi.pill(context, "SEMBUNYI SEMUA", false, 10) { ImzyyPad.setAllHidden(context, true); d.dismiss(); refreshAfterChange() }, PadUi.weight())
            top.addView(PadUi.pill(context, "TAMPILKAN SEMUA", false, 10) { ImzyyPad.setAllHidden(context, false); d.dismiss(); refreshAfterChange() }, PadUi.weight())
            col.addView(top)
            for (b in ImzyyPad.buttons.toList()) {
                val r = PadUi.row(context)
                r.gravity = Gravity.CENTER_VERTICAL
                val name = PadUi.text(context, b.key.replace("+", " + ") + "   [" + ImzyyPad.modeName(b.mode) + "]", 13f, if (b.hidden) Color.GRAY else Color.WHITE, false)
                r.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(PadUi.pill(context, if (b.hidden) "TAMPILKAN" else "SEMBUNYIKAN", false, 10) {
                    ImzyyPad.toggleHidden(context, b.id); d.dismiss(); refreshAfterChange(); openList()
                })
                col.addView(r)
            }
            col.addView(PadUi.pill(context, "TUTUP", true, 10) { d.dismiss() })
        }
    }

    private fun toggleEdit() {
        release()
        editMode = !editMode
        editBtn.text = if (editMode) "SIMPAN" else "PAD"
        editBar.visibility = if (editMode) View.VISIBLE else View.GONE
        for (v in btns) {
            v.visibility = if (v.model.hidden && !editMode) View.GONE else View.VISIBLE
            v.invalidate()
        }
        requestLayout()
    }

    fun commit(v: PadButtonView) {
        ImzyyPad.move(context, v.model.id, v.fx, v.fy)
    }

    fun release() {
        for (v in btns) v.release()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = r - l
        val hh = b - t
        for (v in btns) {
            val s = v.measuredWidth
            val cx = (v.fx * w).toInt()
            val cy = (v.fy * hh).toInt()
            v.layout(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        h.post(tick)
    }

    override fun onDetachedFromWindow() {
        h.removeCallbacks(tick)
        release()
        super.onDetachedFromWindow()
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PadButtonView(ctx: Context, private val act: MinecraftActivity, val model: PadBtn, private val pad: PadView) : View(ctx) {
    var fx = model.x
    var fy = model.y
    private var held = false
    private var latched = false
    private var ignoreUp = false
    private var downAt = 0L
    private var sx = 0f
    private var sy = 0f
    private var sfx = 0f
    private var sfy = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val r = s / 2f
        val a = ((ImzyyPad.opacity * 255).toInt().coerceIn(40, 255) * (if (model.hidden) 0.35f else 1f)).toInt()
        val active = held || latched
        fill.color = if (active) Color.argb(a, 255, 255, 255) else Color.argb((a * 0.5f).toInt(), 16, 16, 16)
        c.drawCircle(r, r, r - 2f, fill)
        line.strokeWidth = if (pad.editMode) 4f else 2f
        line.color = Color.argb(if (pad.editMode) 255 else (a * 0.7f).toInt(), 255, 255, 255)
        c.drawCircle(r, r, r - 2f, line)
        // glossy highlight on the upper edge
        line.strokeWidth = 3f
        line.color = Color.argb((a * 0.55f).toInt(), 255, 255, 255)
        box.set(s * 0.16f, s * 0.12f, s * 0.84f, s * 0.88f)
        c.drawArc(box, 200f, 140f, false, line)
        text.color = if (active) Color.BLACK else Color.WHITE
        text.textSize = when {
            model.label.length <= 2 -> s * 0.36f
            model.label.length == 3 -> s * 0.27f
            model.label.length <= 5 -> s * 0.2f
            else -> s * 0.15f
        }
        c.drawText(model.label, r, r - (text.descent() + text.ascent()) / 2f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pad.editMode) return drag(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); press() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> lift()
        }
        return true
    }

    private fun drag(e: MotionEvent): Boolean {
        val p = parent as? View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; sfx = fx; sfy = fy }
            MotionEvent.ACTION_MOVE -> {
                fx = (sfx + (e.rawX - sx) / p.width).coerceIn(0.03f, 0.97f)
                fy = (sfy + (e.rawY - sy) / p.height).coerceIn(0.05f, 0.95f)
                p.requestLayout()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = e.rawX - sx
                val dy = e.rawY - sy
                val slop = 10f * resources.displayMetrics.density
                if (e.actionMasked == MotionEvent.ACTION_UP && dx * dx + dy * dy < slop * slop) pad.editTap(model) else pad.commit(this)
            }
        }
        return true
    }

    private fun press() {
        downAt = SystemClock.uptimeMillis()
        when (model.mode) {
            "TOGGLE" -> { latched = !latched; send(latched) }
            "AUTO" -> {
                if (latched) { latched = false; ignoreUp = true; send(false) } else { held = true; send(true) }
            }
            else -> { held = true; send(true) }
        }
        invalidate()
    }

    private fun lift() {
        when (model.mode) {
            "TOGGLE" -> {}
            "AUTO" -> {
                if (ignoreUp) {
                    ignoreUp = false
                } else if (held) {
                    held = false
                    if (SystemClock.uptimeMillis() - downAt >= ImzyyPad.HOLD_MS) latched = true else send(false)
                }
            }
            else -> if (held) { held = false; send(false) }
        }
        invalidate()
    }

    fun release() {
        if (held || latched) {
            held = false
            latched = false
            send(false)
        }
        ignoreUp = false
        invalidate()
    }

    private fun sendOne(k: String, down: Boolean) {
        when (k) {
            "LMB" -> ImzyyInput.mouse(act, 0, down)
            "RMB" -> ImzyyInput.mouse(act, 1, down)
            "MMB" -> ImzyyInput.mouse(act, 2, down)
            else -> {
                val kk = PadKeys.map[k] ?: return
                ImzyyInput.key(act, kk.glfw, kk.droid, down)
            }
        }
    }

    /** A combo like "CTRL+C" presses every key in order and releases them in reverse order. */
    private fun send(down: Boolean) {
        try {
            val parts = model.key.split("+")
            if (down) for (k in parts) sendOne(k, true) else for (k in parts.asReversed()) sendOne(k, false)
        } catch (_: Throwable) {
        }
    }
}

/** Small helpers for the programmatic (View based) in-game panels. */
internal object PadUi {
    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    fun pill(ctx: Context, text: String, solid: Boolean = false, padH: Int = 12, onClick: () -> Unit): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextColor(if (solid) Color.BLACK else Color.WHITE)
        t.textSize = 11f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, padH.toFloat()), dp(ctx, 6f), dp(ctx, padH.toFloat()), dp(ctx, 6f))
        t.background = GradientDrawable().apply {
            setColor(if (solid) Color.argb(235, 255, 255, 255) else Color.argb(140, 30, 30, 30))
            cornerRadius = dp(ctx, 40f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), Color.argb(120, 255, 255, 255))
        }
        t.setOnClickListener { onClick() }
        return t
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
        return t
    }

    fun row(ctx: Context): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
        return r
    }

    /** Equal-width child inside a horizontal row, with a small gap. */
    fun weight(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginStart = 4
        lp.marginEnd = 4
        return lp
    }

    /** Grid of every pad key (mouse + keyboard). [onPick] gets the key name. */
    fun keyGrid(ctx: Context, perRow: Int, onPick: (String) -> Unit): LinearLayout {
        val grid = LinearLayout(ctx)
        grid.orientation = LinearLayout.VERTICAL
        for (chunk in PadKeys.order.chunked(perRow)) {
            val r = row(ctx)
            for (k in chunk) r.addView(pill(ctx, PadKeys.short(k), false, 2) { onPick(k) }, weight())
            for (i in chunk.size until perRow) r.addView(View(ctx), weight())
            grid.addView(r)
        }
        return grid
    }

    /** Dark rounded dialog. [scroll] wraps the content in a vertical ScrollView. */
    fun panel(ctx: Context, widthFrac: Float, heightFrac: Float?, scroll: Boolean, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 14f)
        col.setPadding(p, p, p, p)
        col.background = GradientDrawable().apply {
            setColor(Color.argb(244, 10, 10, 10))
            cornerRadius = dp(ctx, 18f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), Color.argb(80, 255, 255, 255))
        }
        build(col, d)
        if (scroll) {
            val sv = ScrollView(ctx)
            sv.addView(col)
            d.setContentView(sv)
        } else {
            d.setContentView(col)
        }
        val dm = ctx.resources.displayMetrics
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout((dm.widthPixels * widthFrac).toInt(), if (heightFrac != null) (dm.heightPixels * heightFrac).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        return d
    }
}

internal object PadActions {
    /** Presses the named keys together (in order), then releases them in reverse. e.g. chord(act, "F3", "T"). */
    fun chord(act: MinecraftActivity, vararg names: String) {
        val h = Handler(Looper.getMainLooper())
        val ks = names.mapNotNull { PadKeys.map[it] }
        var t = 0L
        for (k in ks) { h.postDelayed({ ImzyyInput.key(act, k.glfw, k.droid, true) }, t); t += 70L }
        t += 70L
        for (k in ks.asReversed()) { h.postDelayed({ ImzyyInput.key(act, k.glfw, k.droid, false) }, t); t += 70L }
    }

    /**
     * New mods only load on a fresh JVM. The hub (main process) waits until this game process is gone, applies any
     * staged modpack and starts the very same game intent again, so the player lands back in the game.
     */
    fun restart(act: MinecraftActivity) {
        val originalGameIntent = act.intent?.let { Intent(it) }
        if (originalGameIntent == null) {
            Toast.makeText(act, "Tidak bisa restart: data game tidak ditemukan.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val hub = Intent().setClassName(act.packageName, ImzyyHubActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            hub.putExtra(ImzyyHubActivity.EXTRA_RELAUNCH, originalGameIntent)
            act.startActivity(hub)
            // Do not remove the task containing the Hub. Let the Hub observe process death before touching mods/.
            act.finish()
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1_000L)
        } catch (e: Throwable) {
            Toast.makeText(act, "Restart gagal: ${e.message ?: "gagal membuka Hub"}", Toast.LENGTH_LONG).show()
        }
    }
}

/** In-game modpack browser: search Modrinth, install into the running instance, reload / restart helpers. */
class ModsPanel(private val act: MinecraftActivity) {
    private val h = Handler(Looper.getMainLooper())
    private var busy = false
    private var log: TextView? = null
    private var bar: ProgressBar? = null
    private var list: LinearLayout? = null
    private val lines = ArrayList<String>()

    private fun instance(): Pair<InstanceMeta, File> {
        val path = act.intent?.getStringExtra("instance_dir") ?: error("Instance game tidak diketahui. Jalankan lewat daftar instance.")
        val dir = File(path)
        val meta = InstanceManager.loadMeta(dir) ?: error("Data instance tidak terbaca: ${dir.name}")
        return meta to dir
    }

    private fun say(s: String) {
        h.post {
            lines.add(s)
            while (lines.size > 6) lines.removeAt(0)
            log?.text = lines.joinToString("\n")
        }
    }

    private fun progress(f: Float) {
        h.post { bar?.progress = (f.coerceIn(0f, 1f) * 1000).toInt() }
    }

    fun show() {
        val inst = try {
            instance()
        } catch (e: Exception) {
            Toast.makeText(act, e.message ?: "Instance tidak ditemukan", Toast.LENGTH_LONG).show()
            return
        }
        val meta = inst.first
        val dir = inst.second
        val mc = meta.mcVersion
        val loader = meta.loaderType ?: ""
        val dim = Color.argb(200, 255, 255, 255)
        PadUi.panel(act, 0.94f, 0.94f, false) { col, d ->
            col.addView(PadUi.text(act, "MODPACK  •  Minecraft $mc  •  " + (if (loader.isEmpty()) "VANILLA" else loader.uppercase()) + "  •  " + meta.name, 14f, Color.WHITE, true))
            if (loader.isEmpty()) col.addView(PadUi.text(act, "Instance vanilla: modpack butuh loader (Fabric/Forge/NeoForge/Quilt).", 11f, dim, false))

            val q = EditText(act)
            q.hint = "Cari modpack (kosong = populer)"
            q.setHintTextColor(Color.GRAY)
            q.setTextColor(Color.WHITE)
            q.textSize = 14f
            q.setSingleLine(true)
            q.imeOptions = EditorInfo.IME_ACTION_SEARCH
            val sr = PadUi.row(act)
            sr.gravity = Gravity.CENTER_VERTICAL
            sr.addView(q, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val doSearch = { search(q.text.toString().trim(), mc, loader, meta, dir) }
            sr.addView(PadUi.pill(act, "CARI", true, 14) { doSearch() })
            q.setOnEditorActionListener { _, _, _ -> doSearch(); true }
            col.addView(sr)

            val sv = ScrollView(act)
            val l = LinearLayout(act)
            l.orientation = LinearLayout.VERTICAL
            sv.addView(l)
            list = l
            col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val pb = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 1000
            bar = pb
            col.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PadUi.dp(act, 8f)))
            val lg = PadUi.text(act, "Ketik nama modpack lalu CARI. Modpack harus cocok dengan Minecraft $mc.", 11f, dim, false)
            log = lg
            col.addView(lg)

            val rr = PadUi.row(act)
            rr.addView(PadUi.pill(act, "RELOAD TEKSTUR (F3+T)", false, 6) { d.dismiss(); h.postDelayed({ PadActions.chord(act, "F3", "T") }, 250L) }, PadUi.weight())
            rr.addView(PadUi.pill(act, "RELOAD CHUNK (F3+A)", false, 6) { d.dismiss(); h.postDelayed({ PadActions.chord(act, "F3", "A") }, 250L) }, PadUi.weight())
            rr.addView(PadUi.pill(act, "RESTART GAME", false, 6) { PadActions.restart(act) }, PadUi.weight())
            rr.addView(PadUi.pill(act, "TUTUP", true, 6) { d.dismiss() }, PadUi.weight())
            col.addView(rr)
            col.addView(PadUi.text(act, "Modpack disimpan dulu dengan aman, lalu diterapkan saat RESTART GAME (game dibuka lagi otomatis). RELOAD hanya memuat ulang tekstur & render, tanpa restart.", 10f, dim, false))
            d.setOnDismissListener { list = null; log = null; bar = null }
        }
        search("", mc, loader, meta, dir)
    }

    private fun search(query: String, mc: String, loader: String, meta: InstanceMeta, dir: File) {
        if (busy) return
        busy = true
        say("Mencari modpack…")
        Thread {
            try {
                val hits = Modrinth.searchPacks(query, mc, loader)
                h.post { showHits(hits, mc, loader, meta, dir) }
                say(if (hits.isEmpty()) "Tidak ada modpack untuk $mc ${loader.ifEmpty { "vanilla" }}" else "${hits.size} modpack ditemukan")
            } catch (e: Exception) {
                say("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun showHits(hits: List<Hit>, mc: String, loader: String, meta: InstanceMeta, dir: File) {
        val l = list ?: return
        l.removeAllViews()
        for (x in hits) {
            val r = PadUi.row(act)
            r.gravity = Gravity.CENTER_VERTICAL
            val c = LinearLayout(act)
            c.orientation = LinearLayout.VERTICAL
            c.addView(PadUi.text(act, x.title, 14f, Color.WHITE, true))
            val d2 = TextView(act)
            d2.text = x.desc + "  •  ⬇ " + x.downloads
            d2.textSize = 11f
            d2.setTextColor(Color.argb(190, 255, 255, 255))
            d2.maxLines = 2
            c.addView(d2)
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(PadUi.pill(act, "PASANG", true, 14) { install(x, mc, loader, meta, dir) })
            l.addView(r)
        }
    }

    private fun install(x: Hit, mc: String, loader: String, meta: InstanceMeta, dir: File) {
        if (busy) return
        busy = true
        progress(0f)
        Thread {
            try {
                say("Mencari versi ${x.title} untuk $mc…")
                val f = Modrinth.resolvePack(x.id, mc, loader)
                    ?: error("${x.title} tidak punya versi untuk Minecraft $mc${if (loader.isNotEmpty()) " / $loader" else ""}")
                say("Mengunduh ${f.filename} (${Net.fmtBytes(f.size)})…")
                val tmp = File(act.cacheDir, "imzyy-game-pack.mrpack")
                var got = 0L
                Net.download(f.url, tmp, f.sha512, f.sha1) { n ->
                    got += n
                    if (f.size > 0) progress(got.toFloat() / f.size * 0.15f)
                }
                try {
                    val uri = Uri.fromFile(tmp)
                    val info = Mrpack.readIndex(act, uri)
                    val packMc = info.deps["minecraft"]
                    if (packMc != null && packMc != mc) error("Modpack butuh Minecraft $packMc, instance ini $mc")
                    val packLoader = listOf("fabric-loader" to "fabric", "quilt-loader" to "quilt", "neoforge" to "neoforge", "forge" to "forge")
                        .firstOrNull { info.deps.containsKey(it.first) }?.second
                    if (packLoader != null && packLoader != meta.loaderType) error("Modpack butuh loader $packLoader, instance ini ${meta.loaderType ?: "vanilla"}")
                    // Staged: files are copied into a side folder so the running JVM's mod jars are never touched.
                    val staged = File(dir, Mrpack.PENDING)
                    Mrpack.install(act, uri, info, staged, { s -> say(s) }, { p -> progress(0.15f + p * 0.8f) })
                    try {
                        // Most packs ship no Mod Menu, so the title screen has no "Mods" button. Add it (Fabric only).
                        if (Modrinth.ensureModMenu(listOf(File(dir, "mods"), File(staged, "mods")), File(staged, "mods"), mc, loader) { s -> say(s) }) {
                            say("✓ Mod Menu ditambahkan (tombol Mods di layar utama)")
                        }
                    } catch (e: Exception) {
                        say("Mod Menu dilewati: ${e.message ?: e.javaClass.simpleName}")
                    }
                    say("✓ ${info.name} siap. Tekan RESTART GAME: modpack diterapkan lalu game dibuka lagi otomatis.")
                } finally {
                    tmp.delete()
                }
            } catch (e: Exception) {
                say("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }.start()
    }
}
