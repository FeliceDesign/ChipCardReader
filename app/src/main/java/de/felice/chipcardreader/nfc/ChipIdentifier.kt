package de.felice.chipcardreader.nfc

/**
 * Turns low-level chip answers into human names ("NTAG215", "MIFARE Classic 1K").
 * Pure Kotlin so it's testable.
 */
object ChipIdentifier {

    /**
     * Answer to GET_VERSION (0x60) on NTAG / Ultralight EV1 — 8 bytes:
     *   [00 header][vendor][type][subtype][major][minor][storage][protocol]
     * The storage byte tells the model.
     */
    fun fromUltralightVersion(v: ByteArray): String? {
        if (v.size < 8 || v[1].u() != 0x04) return null // 0x04 = NXP
        val type = v[2].u()
        val storage = v[6].u()
        return when (type) {
            0x04 -> when (storage) { // NTAG
                0x0F -> "NTAG213"
                0x11 -> "NTAG215"
                0x13 -> "NTAG216"
                0x0B -> "NTAG210"
                0x0E -> "NTAG212"
                else -> "NTAG (unbekanntes Modell)"
            }
            0x03 -> when (storage) { // Ultralight
                0x0B -> "MIFARE Ultralight EV1 (48 Byte)"
                0x0E -> "MIFARE Ultralight EV1 (128 Byte)"
                else -> "MIFARE Ultralight (EV1/Nano)"
            }
            else -> null
        }
    }

    /** Usable user memory in bytes, by model. */
    fun userMemory(name: String): Int? = when (name) {
        "NTAG213" -> 144
        "NTAG215" -> 504
        "NTAG216" -> 888
        "NTAG210" -> 48
        "NTAG212" -> 128
        else -> null
    }

    fun classicName(sizeBytes: Int): String = when (sizeBytes) {
        320 -> "MIFARE Classic Mini"
        1024 -> "MIFARE Classic 1K"
        2048 -> "MIFARE Classic 2K"
        4096 -> "MIFARE Classic 4K"
        else -> "MIFARE Classic"
    }

    /** First UID byte = manufacturer (ISO 7816-6). Only for 7-byte UIDs; 4-byte ones are often random. */
    fun manufacturer(uid: ByteArray): String? = when {
        uid.size == 4 && uid[0].u() == 0x08 -> "Zufalls-UID (ändert sich bei jedem Antippen)"
        uid.size < 7 -> null
        else -> when (uid[0].u()) {
            0x02 -> "STMicroelectronics"
            0x04 -> "NXP"
            0x05 -> "Infineon"
            0x07 -> "Texas Instruments"
            0x16 -> "EM Microelectronic"
            0x33 -> "AMIC"
            else -> null
        }
    }
}
