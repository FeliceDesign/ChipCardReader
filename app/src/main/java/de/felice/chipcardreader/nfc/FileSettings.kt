package de.felice.chipcardreader.nfc

/**
 * Answer of DESFire "GetFileSettings":
 *
 *     [type] [comm] [access rights: 2 bytes] [type-specific...]
 *
 * Access rights are 4 nibbles (Read, Write, ReadWrite, ChangeRights).
 * Each nibble is a key number: 0x0–0xD = needs that key,
 * 0xE = FREE for everyone, 0xF = never.
 */
sealed class FileSettings(open val accessRights: AccessRights) {

    data class Data(
        override val accessRights: AccessRights,
        val backup: Boolean,
        val size: Int,
    ) : FileSettings(accessRights)

    data class Value(
        override val accessRights: AccessRights,
        val lowerLimit: Int,
        val upperLimit: Int,
        /** Intercard abuses this field to remember the last top-up/payment. */
        val limitedCreditValue: Int,
        val limitedCreditEnabled: Boolean,
    ) : FileSettings(accessRights)

    data class Record(
        override val accessRights: AccessRights,
        val cyclic: Boolean,
        val recordSize: Int,
        val maxRecords: Int,
        val currentRecords: Int,
    ) : FileSettings(accessRights)

    data class Unknown(override val accessRights: AccessRights, val type: Int) :
        FileSettings(accessRights)

    val typeName: String
        get() = when (this) {
            is Data -> if (backup) "Backup-Datei" else "Datendatei"
            is Value -> "Wertdatei"
            is Record -> if (cyclic) "Zyklische Records" else "Lineare Records"
            is Unknown -> "Unbekannt (0x%02X)".format(type)
        }

    companion object {
        fun parse(b: ByteArray): FileSettings {
            require(b.size >= 4) { "FileSettings zu kurz: ${b.toHex()}" }
            val rights = AccessRights(b[2].u() or (b[3].u() shl 8))
            return when (val type = b[0].u()) {
                0x00, 0x01 -> Data(rights, backup = type == 0x01, size = littleEndianInt(b, 4, 3))
                0x02 -> Value(
                    rights,
                    lowerLimit = littleEndianInt(b, 4),
                    upperLimit = littleEndianInt(b, 8),
                    limitedCreditValue = littleEndianInt(b, 12),
                    limitedCreditEnabled = b.size > 16 && b[16].u() != 0,
                )
                0x03, 0x04 -> Record(
                    rights,
                    cyclic = type == 0x04,
                    recordSize = littleEndianInt(b, 4, 3),
                    maxRecords = littleEndianInt(b, 7, 3),
                    currentRecords = littleEndianInt(b, 10, 3),
                )
                else -> Unknown(rights, type)
            }
        }
    }
}

@JvmInline
value class AccessRights(val raw: Int) {
    val read get() = (raw shr 12) and 0xF
    val write get() = (raw shr 8) and 0xF
    val readWrite get() = (raw shr 4) and 0xF
    val change get() = raw and 0xF

    /** True if anyone may read this file without a key. */
    val freelyReadable get() = read == FREE || readWrite == FREE

    companion object {
        const val FREE = 0xE
    }
}
