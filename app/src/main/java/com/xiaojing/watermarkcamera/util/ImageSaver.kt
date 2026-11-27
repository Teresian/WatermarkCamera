package com.xiaojing.watermarkcamera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把成品图写入系统相册的 WatermarkCamera 目录 */
object ImageSaver {

    private const val ALBUM = "WatermarkCamera"

    fun save(context: Context, bitmap: Bitmap): String? {
        val name = "WM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, bitmap, name)
            } else {
                saveViaFile(context, bitmap, name)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, name: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        // 用独立标志表示「整条保存链路完成」；任何一步失败或抛异常都要清理 IS_PENDING 记录
        var completed = false
        try {
            val out = resolver.openOutputStream(uri) ?: return null
            var compressed = false
            out.use { compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (!compressed) return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            // update 返回 0 表示记录未更新，IS_PENDING 仍为 1，相册中不可见
            if (resolver.update(uri, values, null, null) == 0) return null
            completed = true
            return uri.toString()
        } finally {
            if (!completed) runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun saveViaFile(context: Context, bitmap: Bitmap, name: String): String? {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        var createdFile: File? = null
        var success = false
        try {
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, name)
            createdFile = file
            FileOutputStream(file).use { out ->
                success = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (!success) return null
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            return file.absolutePath
        } finally {
            if (!success) createdFile?.delete()
        }
    }
}
