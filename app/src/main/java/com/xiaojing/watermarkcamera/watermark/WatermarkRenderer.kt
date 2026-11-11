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

/**
 * 把水印烧录到图片左下角，版式与「今日水印相机」的基础样式一致：
 *
 *   06:36 │ 2026-11-10
 *         │ 星期二  轻度雾霾 -2°C
 *   吉林省长春市南关区自强街道 · 亚泰富苑购物中心
 *   备注：总表
 *
 * 时间在黄色竖线左侧，高度 = 右侧两行小字的高度；地点与备注整行左对齐。
 */
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

        // 水印整体放大：所有尺寸按同一比例放大，保持版式比例不变
        val margin = w * 0.050f
        val accentWidth = w * 0.011f
        val gap = w * 0.022f

        val date = Date(config.timestamp)
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)
        val weekText = SimpleDateFormat("EEEE", Locale.CHINA).format(date)

        // 黄线右侧：日期、星期+天气
        val right = ArrayList<Line>()
        if (config.showDate) right.add(Line(dateText, w * 0.043f, 0xE6FFFFFF.toInt(), false, 0f))
        val weather = if (config.showWeather) config.weather.trim() else ""
        val weekLine = if (config.showDate) {
            if (weather.isNotEmpty()) "$weekText  $weather" else weekText
        } else weather
        if (weekLine.isNotBlank()) {
            right.add(Line(weekLine, w * 0.040f, 0xE6FFFFFF.toInt(), false, w * 0.012f))
        }

        // 整行：地点、备注
        val fullRows = ArrayList<Line>()
        if (config.showLocation && config.location.isNotBlank()) {
            fullRows.add(Line(config.location, w * 0.040f, 0xE6FFFFFF.toInt(), false, w * 0.016f))
        }
        if (config.showNote && config.note.isNotBlank()) {
            fullRows.add(Line("备注： " + config.note, w * 0.040f, ACCENT, false, w * 0.014f))
        }

        if (!config.showTime && right.isEmpty() && fullRows.isEmpty()) return out

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setShadowLayer(w * 0.007f, 0f, w * 0.002f, 0xAA000000.toInt())

        fun lineHeight(size: Float): Float {
            paint.textSize = size
            paint.typeface = Typeface.DEFAULT
            val fm = paint.fontMetrics
            return fm.descent - fm.ascent
        }

        var rightHeight = 0f
        for (line in right) rightHeight += lineHeight(line.size) + line.topGap
        var fullHeight = 0f
        for (line in fullRows) fullHeight += lineHeight(line.size) + line.topGap

        // 时间字号：高度固定为「两行小字」的高度
        val unitLine = if (right.isNotEmpty()) lineHeight(right[0].size) else w * 0.043f * 1.4f
        val timeSize = if (config.showTime) {
            paint.textSize = 100f
            paint.typeface = Typeface.DEFAULT_BOLD
            val probe = paint.fontMetrics
            val perUnit = (probe.descent - probe.ascent) / 100f
            ((unitLine * 2f) / perUnit).coerceIn(w * 0.050f, w * 0.112f)
        } else 0f

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        timePaint.setShadowLayer(w * 0.007f, 0f, w * 0.002f, 0xAA000000.toInt())
        timePaint.textSize = timeSize
        timePaint.typeface = Typeface.DEFAULT_BOLD
        timePaint.color = Color.WHITE
        val timeMetrics = timePaint.fontMetrics
        val timeHeight = timeMetrics.descent - timeMetrics.ascent
        val timeWidth = if (config.showTime) timePaint.measureText(timeText) else 0f

        val barLeft = margin + timeWidth + if (config.showTime) gap else 0f
        val barRight = barLeft + accentWidth
        val textLeft = barRight + gap
        val maxRightWidth = (w - textLeft - margin).coerceAtLeast(w * 0.1f)
        val maxFullWidth = (w - margin * 2f).coerceAtLeast(w * 0.1f)

        val bottom = h - margin
        val blockHeight = maxOf(rightHeight, if (config.showTime) timeHeight else 0f) + fullHeight
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

        val headBottom = bottom - fullHeight
        val headTop = headBottom - maxOf(rightHeight, if (config.showTime) timeHeight else 0f)

        // 黄色竖线：与右侧两行文字同高
        if (rightHeight > 0f) {
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            barPaint.color = ACCENT
            canvas.drawRoundRect(
                barLeft, headBottom - rightHeight, barRight, headBottom,
                accentWidth / 2f, accentWidth / 2f, barPaint
            )
        }

        // 时间：底对齐，贴在黄线左侧
        if (config.showTime) {
            canvas.drawText(timeText, margin, headBottom - timeMetrics.descent, timePaint)
        }

        // 黄线右侧两行
        var y = headBottom - rightHeight
        for (line in right) {
            y += line.topGap
            paint.textSize = line.size
            paint.color = line.color
            paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fm = paint.fontMetrics
            canvas.drawText(ellipsize(line.text, paint, maxRightWidth), textLeft, y - fm.ascent, paint)
            y += lineHeight(line.size)
        }

        // 地点、备注整行
        y = headBottom
        for (line in fullRows) {
            y += line.topGap
            paint.textSize = line.size
            paint.color = line.color
            paint.typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val fm = paint.fontMetrics
            canvas.drawText(ellipsize(line.text, paint, maxFullWidth), margin, y - fm.ascent, paint)
            y += lineHeight(line.size)
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
