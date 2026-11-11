package com.xiaojing.watermarkcamera.watermark

/** 水印内容与开关配置 */
data class WatermarkConfig(
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showNote: Boolean = false,
    val note: String = "",
    val location: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isEmpty(): Boolean {
        if (showTime || showDate) return false
        if (showLocation && location.isNotBlank()) return false
        if (showNote && note.isNotBlank()) return false
        return true
    }
}
