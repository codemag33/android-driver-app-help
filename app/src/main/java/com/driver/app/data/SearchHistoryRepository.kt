package com.driver.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранение недавних адресов поиска (точки А и Б отдельно).
 *
 * Максимум 5 записей для каждого target. Дедупликация по заголовку.
 */
class SearchHistoryRepository(context: Context) {

    data class SearchEntry(
        val title: String,
        val subtitle: String,
        val lat: Double,
        val lon: Double
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("search_recent", Context.MODE_PRIVATE)

    suspend fun load(target: Char): List<SearchEntry> = withContext(Dispatchers.IO) {
        val json = prefs.getString("recent_$target", null) ?: return@withContext emptyList()
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SearchEntry(
                    title = obj.getString("title"),
                    subtitle = obj.optString("subtitle", ""),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun save(
        target: Char,
        title: String,
        subtitle: String,
        lat: Double,
        lon: Double,
        maxEntries: Int = 5
    ) = withContext(Dispatchers.IO) {
        try {
            val json = prefs.getString("recent_$target", "[]") ?: "[]"
            val arr = JSONArray(json)

            // Фильтруем дубликаты по заголовку
            val filtered = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getString("title") != title) {
                    filtered.put(obj)
                }
            }

            // Новый элемент в начало
            val result = JSONArray()
            result.put(JSONObject().apply {
                put("title", title)
                put("subtitle", subtitle)
                put("lat", lat)
                put("lon", lon)
            })
            for (i in 0 until filtered.length()) {
                if (i >= maxEntries - 1) break
                result.put(filtered.getJSONObject(i))
            }

            prefs.edit().putString("recent_$target", result.toString()).apply()
        } catch (e: Exception) {
            // Игнорируем ошибки сохранения
        }
    }
}
