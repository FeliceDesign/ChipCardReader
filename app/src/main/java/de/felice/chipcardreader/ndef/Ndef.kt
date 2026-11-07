package de.felice.chipcardreader.ndef

import de.felice.chipcardreader.nfc.toHex

/**
 * NDEF = "NFC Data Exchange Format": the standard way to put a link, text,
 * contact or Wi-Fi login on an NFC sticker so that ANY phone understands it.
 *
 * A message is a list of records. Each record has:
 *  - TNF  (Type Name Format): what kind of "type" follows
 *         1 = NFC Forum well-known ("U" = link, "T" = text)
 *         2 = MIME type ("text/vcard", ...)
 *         4 = external ("android.com:pkg")
 *  - type
 *  - payload (the actual bytes)
 *
 * This file is pure Kotlin (no Android) so it can be unit-tested.
 */
data class NdefEntry(val kind: String, val value: String)

object NdefParser {

    const val TNF_EMPTY = 0
    const val TNF_WELL_KNOWN = 1
    const val TNF_MIME = 2
    const val TNF_ABSOLUTE_URI = 3
    const val TNF_EXTERNAL = 4

    fun parse(tnf: Int, type: ByteArray, payload: ByteArray): NdefEntry {
        val typeStr = String(type, Charsets.US_ASCII)
        return when {
            tnf == TNF_EMPTY -> NdefEntry("Leer", "—")
            tnf == TNF_WELL_KNOWN && typeStr == "U" -> NdefEntry("🔗 Link", decodeUri(payload))
            tnf == TNF_WELL_KNOWN && typeStr == "T" -> NdefEntry("📝 Text", decodeText(payload))
            tnf == TNF_WELL_KNOWN && typeStr == "Sp" -> NdefEntry("🪧 Smart Poster", payload.toHex())
            tnf == TNF_ABSOLUTE_URI -> NdefEntry("🔗 Link", typeStr)
            tnf == TNF_MIME && typeStr.lowercase() in VCARD_TYPES ->
                NdefEntry("👤 Kontakt", summarizeVCard(String(payload, Charsets.UTF_8)))
            tnf == TNF_MIME && typeStr == WIFI_TYPE -> NdefEntry("📶 WLAN", summarizeWifi(payload))
            tnf == TNF_MIME && typeStr.startsWith("text/") ->
                NdefEntry("📄 $typeStr", String(payload, Charsets.UTF_8))
            tnf == TNF_EXTERNAL && typeStr == "android.com:pkg" ->
                NdefEntry("📱 Öffnet App", String(payload, Charsets.UTF_8))
            else -> NdefEntry("Daten ($typeStr)", payload.toHex())
        }
    }

    /** Links are compressed: the first byte replaces a common prefix. */
    fun decodeUri(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefix = URI_PREFIXES.getOrElse(payload[0].toInt() and 0xFF) { "" }
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    /** Text record: [status][language code][text]. Status bit 7 = UTF-16, bits 0-5 = language length. */
    fun decodeText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt() and 0xFF
        val langLength = status and 0x3F
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val start = 1 + langLength
        if (start > payload.size) return ""
        return String(payload, start, payload.size - start, charset)
    }

    fun summarizeVCard(vcard: String): String =
        vcard.lines()
            .map { it.trim() }
            .mapNotNull { line ->
                val key = line.substringBefore(':').substringBefore(';').uppercase()
                val value = line.substringAfter(':', "").replace("\\,", ",").replace("\\;", ";")
                when (key) {
                    "FN" -> value
                    "TEL" -> "☎ $value"
                    "EMAIL" -> "✉ $value"
                    "URL" -> "🌐 $value"
                    "ORG" -> "🏢 $value"
                    else -> null
                }
            }
            .joinToString("\n")
            .ifEmpty { vcard }

    /**
     * Wi-Fi Simple Config: nested 2-byte-type / 2-byte-length TLVs.
     * 0x1045 = SSID (network name), 0x1027 = password.
     */
    fun summarizeWifi(payload: ByteArray): String {
        val ssid = findWscAttribute(payload, 0x1045)?.let { String(it, Charsets.UTF_8) }
        val hasKey = (findWscAttribute(payload, 0x1027)?.size ?: 0) > 0
        return "Netzwerk: ${ssid ?: "?"}" + if (hasKey) "\n(mit Passwort)" else ""
    }

    private fun findWscAttribute(data: ByteArray, wanted: Int): ByteArray? {
        var i = 0
        while (i + 4 <= data.size) {
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            val start = i + 4
            if (start + len > data.size) return null
            val value = data.copyOfRange(start, start + len)
            if (type == wanted) return value
            if (type == 0x100E) findWscAttribute(value, wanted)?.let { return it } // "Credential" container
            i = start + len
        }
        return null
    }

    const val WIFI_TYPE = "application/vnd.wfa.wsc"
    private val VCARD_TYPES = setOf("text/vcard", "text/x-vcard")

    /** NFC Forum URI record prefix table. */
    val URI_PREFIXES = listOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
        "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
        "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://",
        "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:",
        "urn:nfc:",
    )
}

/** What the user wants to write onto a sticker. */
sealed interface WriteRequest {
    data class Link(val url: String) : WriteRequest
    data class Text(val text: String) : WriteRequest
    data class Contact(
        val name: String,
        val phone: String = "",
        val email: String = "",
        val website: String = "",
        val organisation: String = "",
    ) : WriteRequest

    /** Human-readable problem with the input, or null if it's fine. */
    fun validate(): String? = when (this) {
        is Link -> if (url.isBlank()) "Bitte einen Link eingeben." else null
        is Text -> if (text.isBlank()) "Bitte einen Text eingeben." else null
        is Contact -> if (name.isBlank()) "Bitte mindestens einen Namen eingeben." else null
    }

    companion object {
        /** People type "example.com" — make it a real link. */
        fun normalizeUrl(input: String): String {
            val t = input.trim()
            return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t)) t else "https://$t"
        }
    }
}

object VCard {
    /** vCard 3.0, the format every phone's contact app understands. */
    fun build(c: WriteRequest.Contact): String = buildString {
        fun line(s: String) = append(s).append("\r\n")
        line("BEGIN:VCARD")
        line("VERSION:3.0")
        val name = escape(c.name.trim())
        line("N:$name;;;;")
        line("FN:$name")
        if (c.organisation.isNotBlank()) line("ORG:${escape(c.organisation.trim())}")
        if (c.phone.isNotBlank()) line("TEL:${escape(c.phone.trim())}")
        if (c.email.isNotBlank()) line("EMAIL:${escape(c.email.trim())}")
        if (c.website.isNotBlank()) line("URL:${escape(WriteRequest.normalizeUrl(c.website))}")
        line("END:VCARD")
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
}
