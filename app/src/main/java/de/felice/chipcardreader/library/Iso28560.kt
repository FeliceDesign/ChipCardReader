package de.felice.chipcardreader.library

/**
 * ISO 28560-3, the "Danish data model": how many libraries (including
 * Stadtbücherei Ravensburg) lay out the first 32 bytes of their RFID labels.
 *
 *   byte 0      high nibble = usage type (1 = medium, 8 = library card, …)
 *               low nibble  = data model version (1)
 *   byte 1      number of parts in the set (e.g. 3 for a 3-CD box)
 *   byte 2      this part's number
 *   bytes 3-18  primary item ID: media number / card number, ASCII, 0-padded
 *   bytes 19-20 CRC-16, low byte first
 *   bytes 21-22 country of the owning library ("DE")
 *   bytes 23-31 library ID (here a DBS-style number, e.g. "AB269")
 *
 * The CRC was verified against real tags from this project:
 * CRC-16/CCITT (poly 0x1021, init 0xFFFF) over bytes 0-18 + 21-31 + two 0x00.
 */
data class LibraryTag(
    val usageType: Int,
    val version: Int,
    val partsInSet: Int,
    val partNumber: Int,
    val itemId: String,
    val country: String,
    val library: String,
    val crcValid: Boolean,
) {
    val libraryId: String get() = if (library.isEmpty()) country else "$country-$library"
    val isPatronCard: Boolean get() = usageType == 8

    val usageName: String
        get() = when (usageType) {
            0 -> "Medium in Bearbeitung"
            1 -> "Ausleihbares Medium"
            2 -> "Präsenzbestand (nicht ausleihbar)"
            7 -> "Ausgesondertes Medium"
            8 -> "Leserausweis"
            else -> "Typ $usageType"
        }
}

object Iso28560 {

    fun decode(data: ByteArray): LibraryTag? {
        if (data.size < 32) return null
        val version = data[0].toInt() and 0x0F
        if (version != 1) return null // only model version 1 is known

        val storedCrc = (data[19].toInt() and 0xFF) or ((data[20].toInt() and 0xFF) shl 8)
        val crcValid = crc(data) == storedCrc

        val itemId = ascii(data, 3, 16)
        // A random tag with version nibble 1 would almost never have a valid
        // CRC; if it's wrong AND the ID isn't printable, it's not a library tag.
        if (!crcValid && itemId.isEmpty()) return null

        return LibraryTag(
            usageType = (data[0].toInt() and 0xF0) shr 4,
            version = version,
            partsInSet = data[1].toInt() and 0xFF,
            partNumber = data[2].toInt() and 0xFF,
            itemId = itemId,
            country = ascii(data, 21, 2),
            library = ascii(data, 23, 9),
            crcValid = crcValid,
        )
    }

    fun crc(data: ByteArray): Int {
        var c = 0xFFFF
        val bytes = data.copyOfRange(0, 19) + data.copyOfRange(21, 32) + byteArrayOf(0, 0)
        for (b in bytes) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF
            }
        }
        return c
    }

    /** ASCII up to the first 0x00, only printable characters. */
    private fun ascii(b: ByteArray, offset: Int, length: Int): String =
        b.copyOfRange(offset, offset + length)
            .takeWhile { it != 0.toByte() }
            .map { (it.toInt() and 0xFF).toChar() }
            .filter { it in ' '..'~' }
            .joinToString("")
            .trim()
}
