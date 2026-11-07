package de.felice.chipcardreader.cards.vdv

import java.time.LocalDateTime

/*
 * Field layouts (all numbers big-endian), as documented by
 * github.com/czietz/parse-vdv-chipcard:
 *
 * App statisch (0x81):
 *   4 instance | 2 org | 1 version | 4 validFrom | 4 validUntil
 *
 * Berechtigung statisch (0x83):
 *   4 number | 2 issuer org | 2 product | 2 product org | 2 key org
 *   | 4 validFrom | 4 validUntil
 */

internal data class AppStatic(val instance: Long, val org: Int, val validFrom: LocalDateTime?, val validUntil: LocalDateTime?)

internal data class TicketStatic(
    val number: Long, val issuerOrg: Int, val product: Int, val productOrg: Int,
    val validFrom: LocalDateTime?, val validUntil: LocalDateTime?,
)

internal fun decodeAppStatic(b: ByteArray): AppStatic? {
    if (b.size < 15) return null
    return AppStatic(
        instance = uint(b, 0, 4),
        org = uint(b, 4, 2).toInt(),
        validFrom = dateTimeCompact(uint(b, 7, 4)),
        validUntil = dateTimeCompact(uint(b, 11, 4)),
    )
}

internal fun decodeTicketStatic(b: ByteArray): TicketStatic? {
    if (b.size < 20) return null
    return TicketStatic(
        number = uint(b, 0, 4),
        issuerOrg = uint(b, 4, 2).toInt(),
        product = uint(b, 6, 2).toInt(),
        productOrg = uint(b, 8, 2).toInt(),
        validFrom = dateTimeCompact(uint(b, 12, 4)),
        validUntil = dateTimeCompact(uint(b, 16, 4)),
    )
}

internal fun decodeText(b: ByteArray): String =
    String(b, Charsets.ISO_8859_1).trim { it <= ' ' }

/**
 * "DateTimeCompact": a date+time squeezed into 32 bits.
 *
 *   bits 31-25  year since 1990
 *   bits 24-21  month
 *   bits 20-16  day
 *   bits 15-11  hour
 *   bits 10-5   minute
 *   bits  4-0   second / 2
 */
fun dateTimeCompact(v: Long): LocalDateTime? = runCatching {
    LocalDateTime.of(
        ((v shr 25) and 0x7F).toInt() + 1990,
        ((v shr 21) and 0x0F).toInt(),
        ((v shr 16) and 0x1F).toInt(),
        ((v shr 11) and 0x1F).toInt(),
        ((v shr 5) and 0x3F).toInt(),
        ((v and 0x1F) * 2).toInt(),
    )
}.getOrNull()

private fun uint(b: ByteArray, offset: Int, length: Int): Long {
    var v = 0L
    for (i in offset until offset + length) v = (v shl 8) or (b[i].toLong() and 0xFF)
    return v
}
