package de.felice.chipcardreader.library

import java.net.HttpURLConnection
import java.net.URL

/** Fetches the catalog RSS. Blocking — call from a background thread. */
object CatalogClient {

    sealed interface Result {
        data class Found(val hit: CatalogHit) : Result
        data object NotFound : Result
        data class Failed(val reason: String) : Result
    }

    fun lookup(base: String, barcode: String): Result = try {
        val conn = URL(KohaCatalog.rssUrl(base, barcode)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "ChipCardReader (private Android app)")
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
        try {
            if (conn.responseCode != 200) {
                Result.Failed("Katalog antwortet mit HTTP ${conn.responseCode}")
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                KohaCatalog.parseRss(body).firstOrNull()?.let { Result.Found(it) } ?: Result.NotFound
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Result.Failed("Katalog nicht erreichbar (${e.javaClass.simpleName})")
    }
}
