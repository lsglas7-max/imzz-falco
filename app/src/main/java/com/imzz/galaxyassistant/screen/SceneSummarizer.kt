package com.imzz.galaxyassistant.screen

import android.graphics.Bitmap
import android.graphics.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Mata" sederhana di dalam HP, tanpa API dan tanpa internet, untuk AI yang hanya bisa membaca teks.
 * Screenshot diperkecil jadi grid kasar, lalu dicari gumpalan yang mencolok (warna cerah/jenuh atau jauh lebih
 * terang dari latar). Hasilnya daftar "x,y LEBARxTINGGI warna" yang bisa dipakai AI sebagai kandidat titik tap
 * di layar tanpa teks (menu game, tombol ikon, dll).
 *
 * Ini perkiraan dari warna, BUKAN pengenalan gambar: tidak tahu apa objeknya, hanya di mana ada sesuatu yang mencolok.
 */
object SceneSummarizer {
    private const val GRID_W = 32

    fun summarize(src: Bitmap): String {
        if (src.width < 8 || src.height < 8) return ""
        val gw = GRID_W
        val gh = (GRID_W * src.height.toFloat() / src.width).toInt().coerceIn(24, 80)
        val small = Bitmap.createScaledBitmap(src, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== src) small.recycle()

        val hsv = FloatArray(3)
        val hue = FloatArray(px.size)
        val sat = FloatArray(px.size)
        val value = FloatArray(px.size)
        for (i in px.indices) {
            Color.colorToHSV(px[i], hsv)
            hue[i] = hsv[0]; sat[i] = hsv[1]; value[i] = hsv[2]
        }
        val sorted = value.copyOf().also { it.sort() }
        val median = sorted[sorted.size / 2]
        val avg = value.average().toFloat()

        val salient = BooleanArray(px.size) { i ->
            (sat[i] > 0.5f && value[i] > 0.4f) || (value[i] - median > 0.4f)
        }

        val seen = BooleanArray(px.size)
        val stack = IntArray(px.size)
        data class Blob(val cx: Float, val cy: Float, val w: Float, val h: Float, val cells: Int, val color: String)
        val blobs = ArrayList<Blob>()

        for (start in px.indices) {
            if (!salient[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var minX = gw; var maxX = 0; var minY = gh; var maxY = 0; var cells = 0
            val tally = HashMap<String, Int>()
            while (top > 0) {
                val p = stack[--top]
                val x = p % gw
                val y = p / gw
                cells++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                val name = colorName(hue[p], sat[p], value[p])
                tally[name] = (tally[name] ?: 0) + 1
                if (x > 0) push(p - 1, salient, seen, stack, top).also { if (it) top++ }
                if (x < gw - 1) push(p + 1, salient, seen, stack, top).also { if (it) top++ }
                if (y > 0) push(p - gw, salient, seen, stack, top).also { if (it) top++ }
                if (y < gh - 1) push(p + gw, salient, seen, stack, top).also { if (it) top++ }
            }
            if (cells < 3) continue
            val color = tally.maxByOrNull { it.value }?.key ?: "campur"
            blobs.add(
                Blob(
                    (minX + maxX + 1) / 2f / gw, (minY + maxY + 1) / 2f / gh,
                    (maxX - minX + 1).toFloat() / gw, (maxY - minY + 1).toFloat() / gh, cells, color
                )
            )
        }

        val brightness = when {
            avg < 0.25f -> "gelap"
            avg < 0.6f -> "sedang"
            else -> "terang"
        }
        val out = StringBuilder("Layar $brightness. ")
        if (blobs.isEmpty()) {
            out.append("Tidak ada objek mencolok terdeteksi.")
            return out.toString()
        }
        out.append("Objek mencolok (x,y lebar×tinggi warna):\n")
        blobs.sortedByDescending { it.cells }.take(14).forEach { b ->
            val shape = when {
                b.w > 2.2f * b.h -> "memanjang(mungkin tombol/bar)"
                b.h > 2.2f * b.w -> "tinggi"
                abs(b.w - b.h) < 0.05f -> "bulat/kotak"
                else -> "kotak"
            }
            out.append(String.format(Locale.US, "%.2f,%.2f %.2f×%.2f %s %s\n", b.cx, b.cy, b.w, b.h, b.color, shape))
        }
        return out.toString().take(1100)
    }

    /** Dorong tetangga ke stack kalau layak; true kalau ditambahkan. */
    private fun push(n: Int, salient: BooleanArray, seen: BooleanArray, stack: IntArray, top: Int): Boolean {
        if (!salient[n] || seen[n]) return false
        seen[n] = true
        stack[top] = n
        return true
    }

    private fun colorName(h: Float, s: Float, v: Float): String = when {
        v < 0.2f -> "hitam"
        s < 0.15f -> if (v > 0.8f) "putih" else "abu"
        h < 15f || h >= 345f -> "merah"
        h < 40f -> "oranye"
        h < 70f -> "kuning"
        h < 160f -> "hijau"
        h < 200f -> "cyan"
        h < 255f -> "biru"
        h < 290f -> "ungu"
        else -> "pink"
    }
}
