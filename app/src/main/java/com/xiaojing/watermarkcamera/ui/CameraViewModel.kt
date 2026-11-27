package com.xiaojing.watermarkcamera.ui

import android.app.Application
import android.content.Context
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
import java.io.ByteArrayOutputStream
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

/** 底部拍摄模式：照片 / 视频（默认照片） */
enum class CaptureMode(val label: String) {
    PHOTO("照片"),
    VIDEO("视频")
}

/** 地点水印显示方式：地址优先（拿不到地址回退坐标）/ 只显示坐标 */
enum class LocationDisplayMode(val label: String) {
    ADDRESS("地址"),
    COORDINATE("坐标")
}

/** 备注默认快捷选项（可自行增删） */
val DEFAULT_NOTE_PRESETS = listOf("总表", "电采暖A项", "电采暖B项", "电采暖C项")

data class WatermarkUiState(
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showLocation: Boolean = true,
    val showNote: Boolean = true,
    val note: String = "总表",
    val location: String = "",
    val weather: String = "",
    val placeLevel: PlaceLevel = PlaceLevel.POI,
    val locationMode: LocationDisplayMode = LocationDisplayMode.ADDRESS,
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
    val notePresets: List<String> = DEFAULT_NOTE_PRESETS,
    // 闪光灯（持久化，重开应用保留）
    val flashMode: FlashMode = FlashMode.OFF,
    // 变焦倍数
    val zoomRatio: Float = 1f,
    val maxZoom: Float = 8f,
    // 拼图设置面板
    val showStitchPanel: Boolean = false,
    // 拍摄模式：照片 / 视频
    val captureMode: CaptureMode = CaptureMode.PHOTO
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    /** 轻量本地配置：闪光灯、参考线等开关关掉应用也要保留 */
    private val prefs = app.getSharedPreferences("wm_camera_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        WatermarkUiState(
            flashMode = runCatching { FlashMode.valueOf(prefs.getString(KEY_FLASH, FlashMode.OFF.name)!!) }
                .getOrDefault(FlashMode.OFF),
            grid = runCatching { GridMode.valueOf(prefs.getString(KEY_GRID, GridMode.NONE.name)!!) }
                .getOrDefault(GridMode.NONE),
            stitchColumns = prefs.getInt(KEY_COLUMNS, 2).coerceIn(1, 4),
            placeLevel = runCatching { PlaceLevel.valueOf(prefs.getString(KEY_PLACE_LEVEL, PlaceLevel.POI.name)!!) }
                .getOrDefault(PlaceLevel.POI),
            locationMode = runCatching {
                LocationDisplayMode.valueOf(prefs.getString(KEY_LOCATION_MODE, LocationDisplayMode.ADDRESS.name)!!)
            }.getOrDefault(LocationDisplayMode.ADDRESS)
        )
    )
    val state: StateFlow<WatermarkUiState> = _state.asStateFlow()

    /** 最近一张暂存图的缩略图（拍照时在左下预览位显示） */
    private val _pendingThumb = MutableStateFlow<Bitmap?>(null)
    val pendingThumb: StateFlow<Bitmap?> = _pendingThumb.asStateFlow()

    /** 读取暂存区第 index 张（0 为最先拍的），供内置预览器显示大图 */
    fun pendingBitmapAt(index: Int): Bitmap? = synchronized(pendingLock) {
        pending.getOrNull(index)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /** 缓存最近一次定位结果，切换地址/坐标显示时无需重新定位 */
    private var lastPlace: com.xiaojing.watermarkcamera.util.PlaceResult? = null

    /** 边拍边拼暂存区：已烧好水印并压缩为 JPEG 字节，避免多张大图常驻内存 */
    private val pending = mutableListOf<ByteArray>()

    /** 保护暂存区的并发访问：拍照写入在 IO 协程，撤销/清空/拼接在 UI 侧 */
    private val pendingLock = Any()

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

    /** 参考线：改完立刻落盘，下次打开还是这个设定 */
    fun setGrid(grid: GridMode) {
        prefs.edit().putString(KEY_GRID, grid.name).apply()
        _state.update { it.copy(grid = grid) }
    }

    /** 闪光灯：关 / 闪光 / 常亮，同样持久化 */
    fun setFlashMode(mode: FlashMode) {
        prefs.edit().putString(KEY_FLASH, mode.name).apply()
        _state.update { it.copy(flashMode = mode) }
    }

    /** 点一下循环切换闪光灯，返回切换后的模式（无闪光灯时返回 null） */
    fun cycleFlashMode(hasFlashUnit: Boolean): FlashMode? {
        if (!hasFlashUnit) {
            setToast("当前摄像头没有闪光灯")
            return null
        }
        val next = when (_state.value.flashMode) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        setFlashMode(next)
        return next
    }

    /** 变焦：手势或按钮都走这里，范围 1x~maxZoom */
    fun setZoom(ratio: Float) = _state.update {
        it.copy(zoomRatio = ratio.coerceIn(1f, it.maxZoom))
    }

    /** 双指缩放：在当前倍数上乘一个系数 */
    fun zoomBy(factor: Float) = _state.update {
        it.copy(zoomRatio = (it.zoomRatio * factor).coerceIn(1f, it.maxZoom))
    }

    /** 相机支持的最大变焦倍数（绑定相机后回填） */
    fun setMaxZoom(max: Float) = _state.update {
        it.copy(maxZoom = if (max > 1f) max else 8f, zoomRatio = it.zoomRatio.coerceAtMost(if (max > 1f) max else 8f))
    }

    fun setShowStitchPanel(show: Boolean) = _state.update { it.copy(showStitchPanel = show) }

    fun setCaptureMode(mode: CaptureMode) {
        if (mode == CaptureMode.VIDEO) {
            setToast("视频拍摄暂未开放，请先使用照片模式")
            return
        }
        _state.update { it.copy(captureMode = CaptureMode.PHOTO) }
    }

    fun setPlaceLevel(level: PlaceLevel) {
        prefs.edit().putString(KEY_PLACE_LEVEL, level.name).apply()
        _state.update { it.copy(placeLevel = level) }
        refreshLocation()
    }

    /** 切换地址/坐标显示：持久化，并用缓存结果即时刷新，无需重新定位 */
    fun setLocationMode(mode: LocationDisplayMode) {
        prefs.edit().putString(KEY_LOCATION_MODE, mode.name).apply()
        _state.update {
            it.copy(
                locationMode = mode,
                location = formatLocation(lastPlace, mode)
            )
        }
    }

    /** 地址优先：地址可用就显示地址，否则回退坐标；坐标模式只显示坐标 */
    private fun formatLocation(place: com.xiaojing.watermarkcamera.util.PlaceResult?, mode: LocationDisplayMode): String {
        if (place == null) return ""
        return when (mode) {
            LocationDisplayMode.ADDRESS -> place.address?.takeIf { it.isNotBlank() } ?: place.coord
            LocationDisplayMode.COORDINATE -> place.coord
        }
    }

    fun toggleStitching() = _state.update { it.copy(stitching = !it.stitching) }

    fun setStitchColumns(n: Int) {
        val v = n.coerceIn(1, 4)
        prefs.edit().putInt(KEY_COLUMNS, v).apply()
        _state.update { it.copy(stitchColumns = v) }
    }

    /** 刷新定位 + 天气（天气依赖坐标） */
    fun refreshLocation() {
        if (_state.value.locationLoading) return
        _state.update { it.copy(locationLoading = true) }
        val level = _state.value.placeLevel
        viewModelScope.launch {
            val place = LocationHelper.currentPlace(getApplication(), level)
            lastPlace = place
            val weather = place?.let { WeatherHelper.current(it.lat, it.lon) }.orEmpty()
            _state.update {
                it.copy(
                    location = formatLocation(place, it.locationMode),
                    weather = weather,
                    locationLoading = false,
                    toast = if (place == null) "定位失败，请检查定位权限与开关" else it.toast
                )
            }
        }
    }

    fun onPhotoCaptured(file: File, capturedAt: Long = System.currentTimeMillis()) {
        if (_state.value.shooting) return
        _state.update { it.copy(shooting = true) }
        val snapshot = _state.value
        viewModelScope.launch {
            // null 表示失败；true 表示成功落相册或成功暂存
            val success = withContext(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                var stamped: Bitmap? = null
                try {
                    bitmap = decode(file) ?: return@withContext null
                    val config = WatermarkConfig(
                        showTime = snapshot.showTime,
                        showDate = snapshot.showDate,
                        showWeather = snapshot.showWeather,
                        showLocation = snapshot.showLocation,
                        showNote = snapshot.showNote,
                        note = snapshot.note,
                        location = snapshot.location,
                        weather = snapshot.weather,
                        timestamp = capturedAt
                    )
                    val output = WatermarkRenderer.render(bitmap!!, config)
                    stamped = output
                    if (output !== bitmap) bitmap!!.recycle()
                    bitmap = null

                    if (snapshot.stitching) {
                        // 暂存压缩后的 JPEG，避免多张 ARGB_8888 大图同时常驻导致 OOM
                        val bytes = ByteArrayOutputStream().use { bos ->
                            if (!output.compress(Bitmap.CompressFormat.JPEG, 90, bos)) null
                            else bos.toByteArray()
                        } ?: return@withContext null
                        // 生成小缩略图供左下预览位显示（不持有大图）
                        val thumb = Bitmap.createScaledBitmap(output, 160, 160, true)
                        synchronized(pendingLock) { pending.add(bytes) }
                        _pendingThumb.value = thumb
                        true
                    } else {
                        // 普通拍照：左下预览位显示本次成片缩略图
                        _pendingThumb.value = Bitmap.createScaledBitmap(output, 160, 160, true)
                        ImageSaver.save(getApplication(), output) != null
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    stamped?.recycle()
                    bitmap?.recycle()
                    file.delete()
                }
            }
            val count = synchronized(pendingLock) { pending.size }
            _state.update {
                it.copy(
                    shooting = false,
                    pendingCount = count,
                    toast = when {
                        success == null -> if (snapshot.stitching) "暂存失败，请重试" else "保存失败，请检查存储权限"
                        snapshot.stitching -> "已暂存第 $count 张"
                        else -> "已保存到相册/WatermarkCamera"
                    }
                )
            }
        }
    }

    /** 把暂存区里的照片按列数拼成一张并保存 */
    fun stitchAndSave() {
        val snapshots = synchronized(pendingLock) { pending.toList() }
        if (snapshots.isEmpty()) {
            _state.update { it.copy(toast = "暂存区是空的，先拍几张") }
            return
        }
        if (_state.value.busy) return
        val columns = _state.value.stitchColumns
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                var sources: List<Bitmap> = emptyList()
                var merged: Bitmap? = null
                try {
                    sources = snapshots.mapNotNull { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (sources.size != snapshots.size) return@withContext false
                    merged = PhotoStitcher.stitch(sources, columns)
                    merged?.let { ImageSaver.save(getApplication(), it) != null } ?: false
                } catch (e: Exception) {
                    false
                } finally {
                    merged?.recycle()
                    sources.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
            if (success) {
                // 只移除本次参与拼接的批次，不误删处理期间新拍的照片
                synchronized(pendingLock) { pending.removeAll(snapshots.toSet()) }
                refreshPendingThumb()
            }
            val count = synchronized(pendingLock) { pending.size }
            _state.update {
                it.copy(
                    busy = false,
                    pendingCount = count,
                    toast = if (!success) "拼接失败，请重试"
                    else "已拼成 " + PhotoStitcher.columnLabel(columns) + "长图并保存"
                )
            }
        }
    }

    /** 按暂存区最后一张刷新缩略图（在 IO 解码，避免主线程卡顿） */
    private fun refreshPendingThumb() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = synchronized(pendingLock) { pending.lastOrNull() }
            val thumb = bytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.let { bmp ->
                    Bitmap.createScaledBitmap(bmp, 160, 160, true)
                }
            }
            _pendingThumb.value = thumb
        }
    }

    /** 清空暂存区 */
    fun clearPending() {
        synchronized(pendingLock) { pending.clear() }
        _pendingThumb.value = null
        _state.update { it.copy(pendingCount = 0, toast = "已清空暂存区") }
    }

    /** 撤销最后一张暂存 */
    fun undoPending() {
        val removed = synchronized(pendingLock) {
            if (pending.isEmpty()) false
            else {
                pending.removeAt(pending.size - 1)
                true
            }
        }
        if (!removed) return
        refreshPendingThumb()
        val count = synchronized(pendingLock) { pending.size }
        _state.update { it.copy(pendingCount = count, toast = "已撤销最后一张") }
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(pendingLock) { pending.clear() }
        _pendingThumb.value = null
    }

    private companion object {
        const val KEY_FLASH = "flash_mode"
        const val KEY_GRID = "grid_mode"
        const val KEY_COLUMNS = "stitch_columns"
        const val KEY_PLACE_LEVEL = "place_level"
        const val KEY_LOCATION_MODE = "location_mode"
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxSide = 3000
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

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
