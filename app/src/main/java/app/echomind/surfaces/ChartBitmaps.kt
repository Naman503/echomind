package app.echomind.surfaces

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.echomind.brain.Languages

/**
 * Small charts drawn to bitmaps, for places Compose can't draw: home-screen widgets
 * and notifications. Colours are mid-tones that read on light and dark.
 */
object ChartBitmaps {
    const val HINDI = 0xFFF08A2C.toInt()
    const val ENGLISH = 0xFF5C6CFF.toInt()
    const val HINGLISH = 0xFF14A394.toInt()
    const val BAR = 0xFF6F7BF7.toInt()
    const val TRACK = 0x33888888

    fun langColor(l: Languages.Lang) = when (l) { Languages.Lang.HINDI -> HINDI; Languages.Lang.ENGLISH -> ENGLISH; Languages.Lang.HINGLISH -> HINGLISH }

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    /** One rounded bar split into shares. */
    fun stackedBar(w: Int, h: Int, parts: List<Pair<Float, Int>>): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val r = h / 2f
        val clip = android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint(TRACK))
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total > 0) {
            var x = 0f
            parts.filter { it.first > 0 }.forEach { (v, col) -> val dx = v / total * w; c.drawRect(x, 0f, x + dx, h.toFloat(), paint(col)); x += dx + 2f }
        }
        return b
    }

    /** Vertical bars (e.g. minutes of talk per hour); [highlight] drawn in the accent. */
    fun bars(w: Int, h: Int, values: List<Float>, color: Int = BAR, highlight: Int = -1, accent: Int = HINDI): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        if (values.isEmpty()) return b
        val max = values.max().takeIf { it > 0 } ?: 1f
        val gap = 2f; val bw = (w - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val bh = (v / max * h).coerceAtLeast(if (v > 0) 3f else 2f)
            val p = paint(if (i == highlight) accent else if (v > 0) color else TRACK)
            val x = i * (bw + gap)
            c.drawRoundRect(RectF(x, h - bh, x + bw, h.toFloat()), bw / 3, bw / 3, p)
        }
        return b
    }

    /** A ring split into shares, with an optional ring of progress instead ([parts] of one). */
    fun donut(size: Int, parts: List<Pair<Float, Int>>, stroke: Float = size * 0.16f): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val rect = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        p.color = TRACK; c.drawArc(rect, 0f, 360f, false, p)
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total > 0) {
            var start = -90f
            parts.filter { it.first > 0 }.forEach { (v, col) -> val sweep = v / total * 360f; p.color = col; c.drawArc(rect, start, sweep, false, p); start += sweep }
        }
        return b
    }

    /** Progress ring 0..1. */
    fun ring(size: Int, fraction: Float, color: Int = BAR): Bitmap {
        val f = fraction.coerceIn(0f, 1f)
        return donut(size, listOf(f to color, (1 - f) to 0))
    }

    fun langParts(s: Languages.Split) = Languages.Lang.entries.map { s[it].toFloat() to langColor(it) }

    /**
     * The evening recap picture: up to four big numbers, talk by hour and your language split,
     * on its own dark card so it reads on any notification shade.
     */
    fun dayCard(numbers: List<Pair<String, String>>, hours: List<Float>, lang: Languages.Split, w: Int = 1000, h: Int = 480): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, paint(0xFF171A2E.toInt()))
        val pad = 44f
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 58f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4B8D6.toInt(); textSize = 26f }
        val col = (w - pad * 2) / numbers.size.coerceAtLeast(1)
        numbers.take(4).forEachIndexed { i, (v, l) ->
            c.drawText(v, pad + i * col, pad + 56f, big)
            c.drawText(l, pad + i * col, pad + 92f, small)
        }
        // Talk by hour, 6 AM to midnight.
        val top = pad + 130f; val chartH = 150f
        if (hours.any { it > 0 }) {
            val bars = bars((w - pad * 2).toInt(), chartH.toInt(), hours, color = 0xFF8C96FF.toInt(), highlight = -1)
            c.drawBitmap(bars, pad, top, null)
            listOf(0 to "6 AM", 6 to "12 PM", 12 to "6 PM", 17 to "11 PM").forEach { (i, t) ->
                c.drawText(t, pad + (w - pad * 2) * i / hours.size.coerceAtLeast(1), top + chartH + 30f, small)
            }
        }
        // Languages.
        if (lang.total > 0) {
            val y = h - pad - 52f
            c.drawBitmap(stackedBar((w - pad * 2).toInt(), 18, langParts(lang)), pad, y, null)
            var x = pad
            Languages.Lang.entries.filter { lang[it] > 0 }.forEach { l ->
                val dot = paint(langColor(l)); c.drawCircle(x + 9f, y + 46f, 9f, dot)
                val label = "${l.label} ${lang.pct(l)}%"
                c.drawText(label, x + 26f, y + 55f, small); x += 26f + small.measureText(label) + 30f
            }
        }
        return b
    }
}
