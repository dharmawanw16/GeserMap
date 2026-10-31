package com.karyatunggal.gesermap.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteLocation(
    val id: String = System.currentTimeMillis().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val timestamp: Long = System.currentTimeMillis()
)

object FavoritesManager {
    private const val PREF_NAME = "gesermap_favorites_pref"
    private const val KEY_FAV_LIST = "key_fav_list"

    fun getFavorites(context: Context): List<FavoriteLocation> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_FAV_LIST, "[]") ?: "[]"
        val list = mutableListOf<FavoriteLocation>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    FavoriteLocation(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        name = obj.getString("name"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        address = obj.optString("address", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun saveFavorite(context: Context, location: FavoriteLocation) {
        val list = getFavorites(context).toMutableList()
        list.removeAll { it.id == location.id }
        list.add(0, location)
        saveList(context, list)
    }

    fun removeFavorite(context: Context, id: String) {
        val list = getFavorites(context).toMutableList()
        list.removeAll { it.id == id }
        saveList(context, list)
    }

    private fun saveList(context: Context, list: List<FavoriteLocation>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("address", item.address)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_FAV_LIST, jsonArray.toString()).apply()
    }
}
