package com.driver.app.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Единая точка геокодирования: системный Android Geocoder + fallback через сервер.
 *
 * Все методы — suspend-функции, вызывать из корутины.
 */
class GeocodeRepository(private val context: Context) {

    companion object {
        private const val TAG = "GeocodeRepository"
        private val LOCALE_RU = Locale("ru", "RU")
    }

    data class GeocodeResult(
        val latitude: Double,
        val longitude: Double,
        val label: String
    )

    /**
     * Прямой геокодер: текстовый запрос → список результатов.
     */
    suspend fun search(query: String, limit: Int = 8): List<GeocodeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GeocodeResult>()

        // 1. Системный Geocoder
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(context, LOCALE_RU)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, limit) ?: emptyList()
                val seen = mutableSetOf<String>()
                for (address in addresses) {
                    val (title, subtitle) = splitAddressParts(address)
                    val key = "$title|$subtitle"
                    if (seen.add(key)) {
                        results.add(
                            GeocodeResult(
                                latitude = address.latitude,
                                longitude = address.longitude,
                                label = if (subtitle.isNotEmpty()) "$title, $subtitle" else title
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Android Geocoder search failed", e)
            }
        }

        results
    }

    /**
     * Обратный геокодер: координаты → адресная строка.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, fallback: String = ""): String = withContext(Dispatchers.IO) {
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, LOCALE_RU)
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(lat, lon, 1)
                val address = results?.firstOrNull()
                if (address != null) return@withContext buildAddressLabel(address, fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reverse geocode failed", e)
        }
        fallback.ifEmpty { String.format(Locale.US, "%.5f, %.5f", lat, lon) }
    }

    /**
     * Резервный геокодер через сервер (Nominatim/Яндекс).
     */
    suspend fun searchViaServer(query: String, serverUrl: String): GeocodeResult? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$serverUrl/geocode?q=$encoded")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            if (code !in 200..299) return@withContext null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            GeocodeResult(
                latitude = json.getDouble("lat"),
                longitude = json.getDouble("lon"),
                label = json.optString("label", query)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Server geocode failed", e)
            null
        }
    }

    private fun splitAddressParts(address: Address): Pair<String, String> {
        val lines = (0..address.maxAddressLineIndex).map { address.getAddressLine(it) }
        return when {
            lines.size >= 2 -> lines[0] to lines.drop(1).joinToString(", ")
            lines.isNotEmpty() -> lines[0] to ""
            else -> "" to ""
        }
    }

    private fun buildAddressLabel(address: Address, fallback: String): String {
        return buildString {
            for (i in 0..address.maxAddressLineIndex) {
                if (isNotEmpty()) append(", ")
                append(address.getAddressLine(i))
            }
        }.ifBlank { fallback }
    }
}
