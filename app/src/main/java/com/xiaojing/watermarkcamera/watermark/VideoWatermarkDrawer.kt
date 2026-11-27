package com.xiaojing.watermarkcamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.OverlayEffect

/**
 * 视频水印实时烧录层。
 *
 * 基于 CameraX 1.4 的 OverlayEffect，目标仅 VIDEO_CAPTURE：
 * 预览保持现有 Compose 叠层不变，只有录制进视频的画面被烧上水印。
 * 水印内容与照片完全一致（复用 WatermarkRenderer.renderOverlay 透明底版式）。
 *
 * 用法：
 *  1. 绑定相机时把 [effect] 一并传给 bindToLifecycle；
 *  2. 开始录制前调 [apply] 设置当前水印快照；
 *  3. 录制期间每分钟调一次 [apply]（时间 HH:mm 会走时）；
 *  4. 结束时调 [close] 释放。
 */
class VideoWatermarkDrawer {

    private val thread = HandlerThread("wm-video-overlay").apply { start() }
    private val handler = Handler(thread.looper)

    /** 当前水印透明底位图（竖屏坐标），绘制线程每帧取用，volatile 保证可见性 */
    @Volatile
    private var overlay: Bitmap? = null

    val effect: OverlayEffect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        0, // 静态水印：不排队，每帧即时渲染
        handler,
    ) { throwable ->
        Log.e("VideoWatermark", "overlay effect error", throwable)
    }

    init {
        effect.setOnDrawListener { frame ->
            val bmp = overlay ?: return@setOnDrawListener false
            val canvas: Canvas = frame.overlayCanvas
            val crop = frame.cropRect
            canvas.save()
            /*
             * 缓冲是传感器方向（横屏），CameraX 后续会对画面做：裁剪 → 顺时针旋转 → 镜像。
             * 这里先把画布变换成「竖屏显示坐标」，再把水印整幅铺进裁剪区对应的竖屏矩形。
             * 推导（x_d/y_d 为竖屏坐标，crop 为缓冲区里的裁剪矩形）：
             *   90°:  画布先转 -90°，再平移到 (crop.left, crop.top + crop.height())
             *  180°:  画布转 180°，平移到 (crop.right, crop.bottom)
             *  270°:  画布转 +90°，平移到 (crop.right, crop.top)
             */
            val portraitW: Int
            val portraitH: Int
            when (frame.rotationDegrees) {
                90 -> {
                    canvas.translate(crop.left.toFloat(), crop.bottom.toFloat())
                    canvas.rotate(-90f)
                    portraitW = crop.height()
                    portraitH = crop.width()
                }
                180 -> {
                    canvas.translate(crop.right.toFloat(), crop.bottom.toFloat())
                    canvas.rotate(180f)
                    portraitW = crop.width()
                    portraitH = crop.height()
                }
                270 -> {
                    canvas.translate(crop.right.toFloat(), crop.top.toFloat())
                    canvas.rotate(90f)
                    portraitW = crop.height()
                    portraitH = crop.width()
                }
                else -> {
                    canvas.translate(crop.left.toFloat(), crop.top.toFloat())
                    portraitW = crop.width()
                    portraitH = crop.height()
                }
            }
            // 前置摄像头若管线做了镜像，提前反向镜像补偿，保证文字可读、位置正确
            if (frame.isMirroring) {
                canvas.translate(portraitW.toFloat(), 0f)
                canvas.scale(-1f, 1f)
            }
            canvas.drawBitmap(
                bmp, null,
                RectF(0f, 0f, portraitW.toFloat(), portraitH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            canvas.restore()
            true
        }
    }

    /** 用新的时间戳重新生成水印位图（时间走时），线程安全，可在任意线程调用 */
    fun apply(config: WatermarkConfig, timestampMs: Long) {
        handler.post {
            val old = overlay
            overlay = WatermarkRenderer.renderOverlay(1080, 1920, config.copy(timestamp = timestampMs))
            old?.recycle()
        }
    }

    fun close() {
        runCatching { effect.close() }
        handler.post {
            overlay?.recycle()
            overlay = null
        }
        thread.quitSafely()
    }
}
