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

data class WatermarkUiState(
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showLocation: Boolean = true,
    val showNote: Boolean = false,
    val note: String = "",
    val location: String = "",
    val locationLoading: Boolean = false,
    val shooting: Boolean = false,
    val toast: String? = null
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WatermarkUiState())
    val state: StateFlow<WatermarkUiState> = _state.asStateFlow()

    fun toggleTime() = _state.update { it.copy(showTime = !it.showTime) }

    fun toggleDate() = _state.update { it.copy(showDate = !it.showDate) }

    fun toggleLocation() = _state.update { it.copy(showLocation = !it.showLocation) }

    fun toggleNote() = _state.update { it.copy(showNote = !it.showNote) }

    fun setNote(text: String) = _state.update { it.copy(note = text) }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    fun refreshLocation() {
        if (_state.value.locationLoading) return
        _state.update { it.copy(locationLoading = true) }
        viewModelScope.launch {
            val place = LocationHelper.currentPlace(getApplication())
            _state.update {
                it.copy(
                    location = place.orEmpty(),
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
            val path = withContext(Dispatchers.IO) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = decode(file)
                    val config = WatermarkConfig(
                        showTime = snapshot.showTime,
                        showDate = snapshot.showDate,
                        showLocation = snapshot.showLocation,
                        showNote = snapshot.showNote,
                        note = snapshot.note,
                        location = snapshot.location,
                        timestamp = System.currentTimeMillis()
                    )
                    val stamped = WatermarkRenderer.render(bitmap, config)
                    val saved = ImageSaver.save(getApplication(), stamped)
                    if (stamped !== bitmap) stamped.recycle()
                    saved
                } catch (e: Exception) {
                    null
                } finally {
                    bitmap?.recycle()
                    file.delete()
                }
            }
            _state.update {
                it.copy(
                    shooting = false,
                    toast = if (path == null) "保存失败，请检查存储权限" else "已保存到相册/WatermarkCamera"
                )
            }
        }
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
