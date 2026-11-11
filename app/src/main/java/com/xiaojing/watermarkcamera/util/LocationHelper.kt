package com.xiaojing.watermarkcamera.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 定位粒度：从「省」到最细的「地点」（街道 + POI 名称，不含门牌号） */
enum class PlaceLevel(val label: String) {
    PROVINCE("省"),
    CITY("市"),
    DISTRICT("区"),
    STREET("街道"),
    POI("地点")
}

/** 定位结果：可读地名 + 坐标（坐标供天气查询使用） */
data class PlaceResult(val name: String, val lat: Double, val lon: Double)

/** 通过系统定位拿到一个粗略坐标，再反解成可读地名 */
object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun currentPlace(context: Context, level: PlaceLevel = PlaceLevel.STREET): PlaceResult? =
        withContext(Dispatchers.IO) {
            val location = try {
                lastKnown(context)
            } catch (e: SecurityException) {
                null
            } ?: return@withContext null

            val name = try {
                geocode(context, location, level)
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

    /** 按粒度拼地名：省 / 省·市 / … / 街道 · 地点 */
    @Suppress("DEPRECATION")
    private fun geocode(context: Context, location: Location, level: PlaceLevel): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.CHINA)
        val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        val addr = list?.firstOrNull() ?: return null

        val province = addr.adminArea?.takeIf { it.isNotBlank() }
        val city = addr.locality?.takeIf { it.isNotBlank() && it != province }
        val district = addr.subLocality?.takeIf { it.isNotBlank() }
        val street = addr.thoroughfare?.takeIf { it.isNotBlank() }
        val poi = (addr.featureName ?: addr.premises)
            ?.takeIf { it.isNotBlank() && it != street && it != district }

        val parts = when (level) {
            PlaceLevel.PROVINCE -> listOfNotNull(province)
            PlaceLevel.CITY -> listOfNotNull(province, city)
            PlaceLevel.DISTRICT -> listOfNotNull(province, city, district)
            PlaceLevel.STREET -> listOfNotNull(province, city, district, street)
            PlaceLevel.POI -> listOfNotNull(province, city, district, street)
        }
        val base = parts.joinToString("")
        return when {
            base.isEmpty() && level == PlaceLevel.POI && poi != null -> poi
            base.isEmpty() -> null
            level == PlaceLevel.POI && poi != null -> base + " · " + poi
            else -> base
        }
    }

    private fun formatCoord(location: Location): String = String.format(
        Locale.US, "%.4f, %.4f", location.latitude, location.longitude
    )
}
