package com.xiaojing.watermarkcamera.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaojing.watermarkcamera.util.ImageSaver
import com.xiaojing.watermarkcamera.util.LocationHelper
import com.xiaojing.watermarkcamera.util.PhotoStitcher
import com.xiaojing.watermarkcamera.util.PlaceLevel
import com.xiaojing.watermarkcamera.util.WeatherHelper
import com.xiaojing.watermarkcamera.watermark.WatermarkConfig
import com.xiaojing.watermarkcamera.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 拍摄画幅 */
enum class CaptureRatio(val label: String) {
    RATIO_4_3("4:3"),
    RATIO_FULL("全屏")
}

/** 取景参考线 */
enum class GridMode(val label: String) {
    NONE("无"),
    GRID("九宫格"),
    CROSS("十字线")
}

/** 闪光灯模式：关 / 拍照闪光 / 常亮 */
enum class FlashMode(val label: String) {
    OFF("关"),
    ON("闪光"),
    TORCH("常亮")
}

/** 备注默认快捷选项（可自行增删） */
val DEFAULT_NOTE_PRESETS = listOf("总表", "电采暖A项", "电采暖B项", "电采暖C项")

data class WatermarkUiState(
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showLocation: Boolean = true,
    val showNote: Boolean = false,
    val note: String = "",
    val location: String = "",
    val weather: String = "",
    val placeLevel: PlaceLevel = PlaceLevel.STREET,
    val locationLoading: Boolean = false,
    val shooting: Boolean = false,
    val toast: String? = null,
    // 画幅与参考线
    val ratio: CaptureRatio = CaptureRatio.RATIO_4_3,
    val grid: GridMode = GridMode.NONE,
    // 边拍边拼
    val stitching: Boolean = false,
    val pendingCount: Int = 0,
    val stitchColumns: Int = 2,
    val busy: Boolean = false,
    // 自定义备注
    val notePresets: List<String> = DEFAULT_NOTE_PRESETS
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WatermarkUiState())
    val state: StateFlow<WatermarkUiState> = _state.asStateFlow()

    /** 边拍边拼暂存区：已烧好水印、等待拼接的图片 */
    private val pending = ArrayList<Bitmap>()

    fun toggleTime() = _state.update { it.copy(showTime = !it.showTime) }

    fun toggleDate() = _state.update { it.copy(showDate = !it.showDate) }

    fun toggleWeather() = _state.update { it.copy(showWeather = !it.showWeather) }

    fun toggleLocation() = _state.update { it.copy(showLocation = !it.showLocation) }

    fun toggleNote() = _state.update { it.copy(showNote = !it.showNote) }

    fun setNote(text: String) = _state.update {
        it.copy(note = text, showNote = text.isNotBlank())
    }

    /** 选预设备注；传空串表示「无」 */
    fun pickNote(text: String) = _state.update {
        it.copy(note = text, showNote = text.isNotBlank())
    }

    /** 把当前输入的备注加进快捷列表 */
    fun addNotePreset(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            _state.update { it.copy(toast = "先输入备注内容再添加") }
            return
        }
        _state.update {
            if (it.notePresets.contains(t)) it.copy(toast = "该备注已存在")
            else it.copy(notePresets = it.notePresets + t, toast = "已添加备注：$t")
        }
    }

    /** 从快捷列表移除一条备注 */
    fun removeNotePreset(text: String) = _state.update {
        it.copy(notePresets = it.notePresets - text)
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    fun setToast(msg: String) = _state.update { it.copy(toast = msg) }

    fun setRatio(ratio: CaptureRatio) = _state.update { it.copy(ratio = ratio) }

    fun setGrid(grid: GridMode) = _state.update { it.copy(grid = grid) }

    fun setPlaceLevel(level: PlaceLevel) {
        _state.update { it.copy(placeLevel = level) }
        refreshLocation()
    }

    fun toggleStitching() = _state.update { it.copy(stitching = !it.stitching) }

    fun setStitchColumns(n: Int) = _state.update { it.copy(stitchColumns = n.coerceIn(1, 4)) }

    /** 刷新定位 + 天气（天气依赖坐标） */
    fun refreshLocation() {
        if (_state.value.locationLoading) return
        _state.update { it.copy(locationLoading = true) }
        val level = _state.value.placeLevel
        viewModelScope.launch {
            val place = LocationHelper.currentPlace(getApplication(), level)
            val weather = place?.let { WeatherHelper.current(it.lat, it.lon) }.orEmpty()
            _state.update {
                it.copy(
                    location = place?.name.orEmpty(),
                    weather = weather,
                    locationLoading = false,
                    toast = if (place == null) "定位失败，请检查定位权限与开关" else it.toast
                )
            }
        }
    }

    fun onPhotoCaptured(file: File) {
        if (_state.value.shooting) return
        _state.update { it.copy(shooting = true) }
        val snapshot = _state.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = decode(file)
                    val config = WatermarkConfig(
                        showTime = snapshot.showTime,
                        showDate = snapshot.showDate,
                        showWeather = snapshot.showWeather,
                        showLocation = snapshot.showLocation,
                        showNote = snapshot.showNote,
                        note = snapshot.note,
                        location = snapshot.location,
                        weather = snapshot.weather,
                        timestamp = System.currentTimeMillis()
                    )
                    val stamped = WatermarkRenderer.render(bitmap, config)
                    if (stamped !== bitmap) bitmap.recycle()
                    if (snapshot.stitching) {
                        // 边拍边拼：先进暂存区，不落相册
                        pending.add(stamped)
                        null
                    } else {
                        val saved = ImageSaver.save(getApplication(), stamped)
                        if (saved == null) stamped.recycle()
                        saved
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    file.delete()
                }
            }
            _state.update {
                it.copy(
                    shooting = false,
                    pendingCount = pending.size,
                    toast = when {
                        it.stitching -> "已暂存第 " + pending.size + " 张"
                        result == null -> "保存失败，请检查存储权限"
                        else -> "已保存到相册/WatermarkCamera"
                    }
                )
            }
        }
    }

    /** 把暂存区里的照片按列数拼成一张并保存 */
    fun stitchAndSave() {
        if (pending.isEmpty()) {
            _state.update { it.copy(toast = "暂存区是空的，先拍几张") }
            return
        }
        if (_state.value.busy) return
        val columns = _state.value.stitchColumns
        val sources = ArrayList(pending)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                var merged: Bitmap? = null
                try {
                    merged = PhotoStitcher.stitch(sources, columns)
                    val saved = merged?.let { ImageSaver.save(getApplication(), it) }
                    saved
                } catch (e: Exception) {
                    null
                } finally {
                    merged?.recycle()
                }
            }
            if (path != null) {
                sources.forEach { it.recycle() }
                pending.clear()
            }
            _state.update {
                it.copy(
                    busy = false,
                    pendingCount = pending.size,
                    toast = if (path == null) "拼接失败，请重试"
                    else "已拼成 " + PhotoStitcher.columnLabel(columns) + "长图并保存"
                )
            }
        }
    }

    /** 清空暂存区 */
    fun clearPending() {
        pending.forEach { if (!it.isRecycled) it.recycle() }
        pending.clear()
        _state.update { it.copy(pendingCount = 0, toast = "已清空暂存区") }
    }

    /** 撤销最后一张暂存 */
    fun undoPending() {
        if (pending.isEmpty()) return
        val last = pending.removeAt(pending.size - 1)
        if (!last.isRecycled) last.recycle()
        _state.update { it.copy(pendingCount = pending.size, toast = "已撤销最后一张") }
    }

    override fun onCleared() {
        super.onCleared()
        pending.forEach { if (!it.isRecycled) it.recycle() }
        pending.clear()
    }

    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val maxSide = 3000
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)

        val rotation = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }

        return if (rotation == 0f) {
            bitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }
    }
}
