package com.deivela.radioslocales

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Radio(
    val name: String,
    val streamUrl: String,
    val city: String = "",
    val frequency: String = "",
    val imageUrl: String = ""
) {
    /** "90.0 FM / Nombre Emisora" si hay frecuencia; si no, solo el nombre. */
    val displayName: String
        get() = if (frequency.isBlank()) name else "$frequency / $name"
}

/** Limpia URLs de espacios y de caracteres accidentales al inicio/final como paréntesis o comillas */
fun sanitizeUrl(url: String): String {
    var cleaned = url.trim()
    while (cleaned.startsWith("(") || cleaned.startsWith("\"") || cleaned.startsWith("'")) {
        cleaned = cleaned.substring(1).trim()
    }
    while (cleaned.endsWith(")") || cleaned.endsWith("\"") || cleaned.endsWith("'")) {
        cleaned = cleaned.substring(0, cleaned.length - 1).trim()
    }
    return cleaned
}

/** Convierte una emisora en el elemento que entiende el reproductor (título, subtítulo y logo). */
fun Radio.toMediaItem(): MediaItem {
    val cleanStreamUrl = sanitizeUrl(streamUrl)
    val cleanImageUrl = sanitizeUrl(imageUrl)

    val metadata = MediaMetadata.Builder()
        .setTitle(displayName)
        .setArtist(city)
        .setArtworkUri(if (cleanImageUrl.isBlank()) null else Uri.parse(cleanImageUrl))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()

    val builder = MediaItem.Builder()
        .setMediaId(cleanStreamUrl)
        .setUri(cleanStreamUrl)
        .setMediaMetadata(metadata)

    val lowerUrl = cleanStreamUrl.lowercase()
    if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u")) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }

    return builder.build()
}

private fun JSONObject.str(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

object RadioStore {
    private const val FILE_NAME = "radios.json"

    /** Lee un JSON (lanza excepción si no es un array JSON). Ignora emisoras sin nombre o sin stream. */
    fun parse(json: String): List<Radio> {
        val arr = JSONArray(json.removePrefix("\uFEFF").trim())
        val result = mutableListOf<Radio>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.str("name")
            val url = sanitizeUrl(o.str("streamUrl"))
            if (name.isBlank() || url.isBlank()) continue
            result.add(
                Radio(
                    name = name,
                    streamUrl = url,
                    city = o.str("city"),
                    frequency = o.str("frequency"),
                    imageUrl = sanitizeUrl(o.str("imageUrl"))
                )
            )
        }
        return result.distinctBy { it.streamUrl }
    }

    fun toJson(radios: List<Radio>): String {
        val arr = JSONArray()
        radios.forEach { r ->
            arr.put(
                JSONObject().apply {
                    if (r.city.isNotBlank()) put("city", r.city)
                    if (r.frequency.isNotBlank()) put("frequency", r.frequency)
                    put("name", r.name)
                    put("streamUrl", r.streamUrl)
                    if (r.imageUrl.isNotBlank()) put("imageUrl", r.imageUrl)
                }
            )
        }
        // Android escapa "/" como "\/"; lo deshacemos para que el archivo sea legible
        return arr.toString(2).replace("\\/", "/")
    }

    /** Carga la lista guardada por el usuario; si no existe (o está dañada), la de assets. */
    fun load(context: Context): List<Radio> {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            try {
                return parse(file.readText())
            } catch (_: Exception) {
                // archivo dañado: caemos a la lista inicial
            }
        }
        return try {
            parse(context.assets.open("radios.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Guarda la lista. Devuelve false si no se pudo. */
    fun save(context: Context, radios: List<Radio>): Boolean {
        return try {
            val target = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(toJson(radios))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Añade las emisoras nuevas (por URL de stream) sin duplicar. */
    fun merge(existing: List<Radio>, imported: List<Radio>): List<Radio> =
        existing + imported.filter { new -> existing.none { it.streamUrl == new.streamUrl } }
}
