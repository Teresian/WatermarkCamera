package com.xiaojing.watermarkcamera.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 定位结果：可读地名 + 坐标（坐标供天气查询使用） */
data class PlaceResult(val name: String, val lat: Double, val lon: Double)

/** 通过系统定位拿到一个粗略坐标，再反解成可读地名 */
object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun currentPlace(context: Context): PlaceResult? = withContext(Dispatchers.IO) {
        val location = try {
            lastKnown(context)
        } catch (e: SecurityException) {
            null
        } ?: return@withContext null

        val name = try {
            geocode(context, location)
        } catch (e: Exception) {
            null
        }
        PlaceResult(
            name = name ?: formatCoord(location),
            lat = location.latitude,
            lon = location.longitude
        )
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (!lm.isProviderEnabled(p)) continue
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: continue
            val current = best
            if (current == null || loc.time > current.time) best = loc
        }
        return best
    }

    /** 地名：省 · 市 · 区 · 街道（不含门牌与 POI，避免过长） */
    @Suppress("DEPRECATION")
    private fun geocode(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.CHINA)
        val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        val addr = list?.firstOrNull() ?: return null
        val parts = ArrayList<String>()
        addr.adminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        addr.locality?.takeIf { it.isNotBlank() && it != addr.adminArea }?.let { parts.add(it) }
        addr.subLocality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        addr.thoroughfare?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) null else parts.joinToString("")
    }

    private fun formatCoord(location: Location): String = String.format(
        Locale.US, "%.4f, %.4f", location.latitude, location.longitude
    )
}
