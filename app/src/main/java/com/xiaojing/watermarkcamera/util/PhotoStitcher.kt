package com.xiaojing.watermarkcamera.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * 把多张已烧录水印的照片拼成一张单列~四列的长图。
 *
 * 分配策略：按拍摄顺序，每次把下一张放进「当前累计高度最小」的那一列，
 * 让各列长度尽量接近，避免某列特别长、其余列大片留白。
 */
object PhotoStitcher {

    /** 成品总宽度上限（像素），列数越多每列越窄 */
    private const val TARGET_WIDTH = 2400

    /** 成品总高度上限，超过则整体等比缩小，防止 OOM */
    private const val MAX_HEIGHT = 12000

    /** 列与列之间的分隔线颜色 */
    private const val DIVIDER = 0xFF000000.toInt()

    /** 图片之间的细缝颜色 */
    private const val GAP_COLOR = 0xFF101F2E.toInt()

    /** 图与图之间的缝隙占列宽的比例 */
    private const val GAP_RATIO = 0.008f

    /**
     * @param sources 已按拍摄顺序排好的照片
     * @param columns 列数，1~4
     * @return 拼接后的新图；sources 为空时返回 null
     */
    fun stitch(sources: List<Bitmap>, columns: Int): Bitmap? {
        if (sources.isEmpty()) return null
        val cols = columns.coerceIn(1, 4)

        val colWidth = (TARGET_WIDTH / cols).toFloat()
        val gap = colWidth * GAP_RATIO

        // 先算出每张图缩放后的高度
        val scaled = sources.map { bmp ->
            val h = colWidth * bmp.height / bmp.width
            bmp to h
        }

        // 最短列优先分配
        val buckets = Array(cols) { ArrayList<Pair<Bitmap, Float>>() }
        val heights = FloatArray(cols)
        for (item in scaled) {
            var target = 0
            for (i in 1 until cols) if (heights[i] < heights[target]) target = i
            buckets[target].add(item)
            heights[target] += item.second + gap
        }

        val contentHeight = heights.maxOrNull() ?: 0f
        val totalHeight = contentHeight + gap

        // 超过高度上限时整体缩小
        val scale = if (totalHeight > MAX_HEIGHT) MAX_HEIGHT / totalHeight else 1f
        val outWidth = (TARGET_WIDTH * scale).toInt().coerceAtLeast(1)
        val outHeight = (totalHeight * scale).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(GAP_COLOR)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val dst = RectF()

        for (col in 0 until cols) {
            val x = col * colWidth
            var y = 0f
            for ((bmp, h) in buckets[col]) {
                dst.set(
                    x * scale,
                    y * scale,
                    (x + colWidth) * scale,
                    (y + h) * scale
                )
                canvas.drawBitmap(bmp, null, dst, paint)
                y += h + gap
            }
        }

        // 列分隔线
        if (cols > 1) {
            val divider = Paint().apply { color = DIVIDER; strokeWidth = (colWidth * 0.004f * scale).coerceAtLeast(1f) }
            for (col in 1 until cols) {
                val x = col * colWidth * scale
                canvas.drawLine(x, 0f, x, outHeight.toFloat(), divider)
            }
        }
        return out
    }

    /** 列数对应的中文名，用于 UI 展示 */
    fun columnLabel(columns: Int): String = when (columns) {
        1 -> "单列"
        2 -> "两列"
        3 -> "三列"
        else -> "四列"
    }
}
