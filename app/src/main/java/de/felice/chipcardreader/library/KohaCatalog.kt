package de.felice.chipcardreader.library

import java.net.URLEncoder

/**
 * Koha: open-source library system, used e.g. by Stadtbücherei Ravensburg.
 * Its public catalog (OPAC) can search by barcode (index "bc") and can answer
 * as RSS: a stable, machine-readable format, unlike the HTML page whose
 * design can change any time.
 *
 * Pure Kotlin (URL building + RSS parsing) so it's unit-testable.
 */
data class CatalogHit(val title: String, val details: String?, val link: String?)

object KohaCatalog {

    /** Machine-readable search (RSS). */
    fun rssUrl(base: String, barcode: String) = searchUrl(base, barcode) + "&format=rss"

    /** Human search page — the fallback "Im Katalog öffnen" button. */
    fun searchUrl(base: String, barcode: String) =
        base.trimEnd('/') + "/cgi-bin/koha/opac-search.pl?idx=bc&q=" + URLEncoder.encode(barcode, "UTF-8")

    /** Very small RSS reader: we only need <item><title|link|description>. */
    fun parseRss(xml: String): List<CatalogHit> =
        Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = tag(item, "title")?.let(::clean)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogHit(
                title = title,
                details = tag(item, "description")?.let(::clean)?.takeIf { it.isNotBlank() }?.take(300),
                link = tag(item, "link")?.let(::clean),
            )
        }.toList()

    private fun tag(xml: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    /** Unwrap CDATA, decode entities, strip HTML, collapse whitespace. */
    internal fun clean(s: String): String {
        var t = s.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        t = decodeEntities(t)              // description is HTML inside XML → decode first …
        t = t.replace(Regex("<[^>]+>"), " ") // … then strip the HTML tags
        t = decodeEntities(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntities(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    /**
     * Catalogs we know. DE-AB269 = the ID on real Stadtbücherei Ravensburg tags
     * scanned in this project; the catalog address is from the library's own
     * 2025 newsletter. Users can change or add addresses in the app.
     */
    val KNOWN = mapOf("DE-AB269" to "https://sb-ravensburg.lmscloud.net")
}
