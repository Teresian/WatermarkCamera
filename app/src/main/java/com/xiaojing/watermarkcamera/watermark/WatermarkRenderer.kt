package com.xiaojing.watermarkcamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把水印烧录到图片左下角 */
object WatermarkRenderer {

    private const val ACCENT = 0xFFFFD54F.toInt()
    private const val SHADOW = 0x99000000.toInt()

    private class Line(
        val text: String,
        val size: Float,
        val color: Int,
        val bold: Boolean,
        val topGap: Float
    )

    fun render(source: Bitmap, config: WatermarkConfig): Bitmap {
        if (config.isEmpty()) return source

        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, 0f, 0f, null)

        val margin = w * 0.045f
        val accentWidth = w * 0.009f
        val textLeft = margin + accentWidth + w * 0.022f
        val maxTextWidth = w - textLeft - margin

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("yyyy.MM.dd  EEEE", Locale.CHINA)
        val date = Date(config.timestamp)

        val lines = ArrayList<Line>()
        if (config.showTime) {
            lines.add(Line(timeFmt.format(date), w * 0.085f, Color.WHITE, true, 0f))
        }
        if (config.showDate) {
            lines.add(Line(dateFmt.format(date), w * 0.036f, 0xE6FFFFFF.toInt(), false, w * 0.012f))
        }
        if (config.showLocation && config.location.isNotBlank()) {
            lines.add(Line(config.location, w * 0.033f, 0xE6FFFFFF.toInt(), false, w * 0.014f))
        }
        if (config.showNote && config.note.isNotBlank()) {
            lines.add(Line(config.note, w * 0.033f, ACCENT, false, w * 0.014f))
        }
        if (lines.isEmpty()) return out

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setShadowLayer(w * 0.006f, 0f, w * 0.002f, 0xAA000000.toInt())

        // 先测量总高度
        var blockHeight = 0f
        val heights = ArrayList<Float>()
        for (line in lines) {
            paint.textSize = line.size
            paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fm = paint.fontMetrics
            val lh = fm.descent - fm.ascent
            heights.add(lh)
            blockHeight += lh + line.topGap
        }

        val bottom = h - margin
        val top = bottom - blockHeight

        // 底部渐变遮罩，保证白字可读
        val shadeTop = (top - margin * 1.6f).coerceAtLeast(0f)
        val shade = Paint()
        shade.shader = LinearGradient(
            0f, shadeTop, 0f, h,
            intArrayOf(0x00000000, 0x66000000.toInt(), SHADOW),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, shadeTop, w, h, shade)

        // 左侧强调竖线
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        barPaint.color = ACCENT
        val barTop = top + blockHeight * 0.10f
        canvas.drawRoundRect(
            margin, barTop, margin + accentWidth, bottom - blockHeight * 0.06f,
            accentWidth / 2f, accentWidth / 2f, barPaint
        )

        var y = top
        for (i in lines.indices) {
            val line = lines[i]
            y += line.topGap
            paint.textSize = line.size
            paint.color = line.color
            paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fm = paint.fontMetrics
            val baseline = y - fm.ascent
            canvas.drawText(ellipsize(line.text, paint, maxTextWidth), textLeft, baseline, paint)
            y += heights[i]
        }
        return out
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }
}
