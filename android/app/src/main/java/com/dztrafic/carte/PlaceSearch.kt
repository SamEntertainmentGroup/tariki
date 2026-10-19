package com.dztrafic.carte

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

private const val INDEX_FILE = "places-index.json"
private val KIND_ORDER = listOf("city", "town", "village", "suburb", "hamlet")

class PlacePoint(
    val name: String,
    val nameAr: String,
    val nameFr: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
) {
    internal val haystack = normalizeForSearch("$name $nameAr $nameFr")

    val display: String
        get() = listOf(nameAr, nameFr, name).filter { it.isNotEmpty() }.distinct().joinToString(" · ") +
            " (${kindLabel(kind)})"
}

fun kindLabel(kind: String) = when (kind) {
    "city" -> "ville"
    "town" -> "ville"
    "village" -> "village"
    "suburb" -> "quartier"
    else -> "lieu-dit"
}

fun normalizeForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val builder = StringBuilder(decomposed.length)
    for (char in decomposed) {
        when {
            char in '\u0300'..'\u036F' -> Unit
            char in '\u064B'..'\u065F' || char == '\u0670' || char == '\u0640' -> Unit
            char == 'أ' || char == 'إ' || char == 'آ' -> builder.append('ا')
            char == 'ى' -> builder.append('ي')
            char == 'ة' -> builder.append('ه')
            else -> builder.append(char)
        }
    }
    return builder.toString().trim()
}

object PlaceSearch {

    @Volatile
    private var places: List<PlacePoint> = emptyList()

    val isLoaded: Boolean
        get() = places.isNotEmpty()

    fun load(context: Context) {
        if (isLoaded) return
        places = try {
            val text = context.assets.open(INDEX_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONObject(text).getJSONArray("places")
            (0 until array.length()).map { index ->
                val row = array.getJSONArray(index)
                PlacePoint(
                    name = row.getString(0),
                    nameAr = row.getString(1),
                    nameFr = row.getString(2),
                    kind = row.getString(3),
                    latitude = row.getDouble(4),
                    longitude = row.getDouble(5),
                )
            }
        } catch (ignored: IOException) {
            emptyList()
        } catch (ignored: org.json.JSONException) {
            emptyList()
        }
    }

    fun search(query: String, limit: Int = 30): List<PlacePoint> {
        val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return places
            .filter { place -> tokens.all { place.haystack.contains(it) } }
            .sortedWith(compareBy({ rank(it, tokens.first()) }, { KIND_ORDER.indexOf(it.kind) }, { it.haystack.length }))
            .take(limit)
    }

    private fun rank(place: PlacePoint, firstToken: String): Int = when {
        place.haystack.startsWith(firstToken) -> 0
        place.haystack.contains(" $firstToken") -> 1
        else -> 2
    }
}
