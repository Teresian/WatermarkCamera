package com.xiaojing.watermarkcamera.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 天气：走 open-meteo 免费接口（无需 key），拿不到就返回 null，水印自动不显示天气 */
object WeatherHelper {

    /** 返回形如 "轻度雾霾 7°C" 的短文本 */
    suspend fun current(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val latS = String.format(Locale.US, "%.4f", lat)
            val lonS = String.format(Locale.US, "%.4f", lon)

            val weatherJson = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$latS&longitude=$lonS" +
                    "&current=temperature_2m,weather_code&timezone=auto"
            ) ?: return@withContext null

            val cur = JSONObject(weatherJson).optJSONObject("current") ?: return@withContext null
            val temp = cur.optDouble("temperature_2m", Double.NaN)
            val code = cur.optInt("weather_code", -1)
            if (temp.isNaN()) return@withContext null

            // 空气质量：pm2.5 高时用「雾霾」描述更贴近现实
            val pm = try {
                val aq = get(
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$latS&longitude=$lonS" +
                        "&current=pm2_5&timezone=auto"
                )
                JSONObject(aq ?: "{}").optJSONObject("current")?.optDouble("pm2_5", Double.NaN) ?: Double.NaN
            } catch (e: Exception) {
                Double.NaN
            }

            val desc = when {
                !pm.isNaN() && pm >= 115 -> "重度雾霾"
                !pm.isNaN() && pm >= 75 -> "中度雾霾"
                !pm.isNaN() && pm >= 35 -> "轻度雾霾"
                else -> codeText(code)
            }
            String.format(Locale.US, "%s %.0f°C", desc, temp)
        } catch (e: Exception) {
            null
        }
    }

    private fun codeText(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "阴"
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WatermarkCamera/1.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
