package de.felice.chipcardreader.nfc

/**
 * Anything that can send bytes to a card and get bytes back.
 * On a real phone this is IsoDep.transceive(); in tests it's a fake.
 */
fun interface Transceiver {
    fun transceive(command: ByteArray): ByteArray
}

class DesfireException(val status: Int, message: String) : Exception(message)

/**
 * Speaks MIFARE DESFire "native" commands, wrapped in ISO 7816 APDUs:
 *
 *     90 <cmd> 00 00 [Lc <data>] 00
 *
 * The card answers with `<data> 91 <status>`. Status 0xAF means
 * "there's more, ask again with 0xAF".
 *
 * Only reads things that need NO authentication. No keys, no cracking.
 *
 * Application IDs (AIDs) are kept as an Int built from the 3 bytes in the
 * order the card lists them (e.g. bytes 5F 84 15 -> 0x5F8415). Selecting
 * sends them back in exactly that order, so the convention is consistent.
 */
class DesfireClient(private val io: Transceiver) {

    /** Hardware/software version info. Only DESFire chips answer this. */
    fun getVersion(): ByteArray = send(CMD_GET_VERSION)

    fun getApplicationIds(): List<Int> =
        send(CMD_GET_APPLICATION_IDS).toList().chunked(3)
            .filter { it.size == 3 }
            .map { (a, b, c) -> (a.u() shl 16) or (b.u() shl 8) or c.u() }

    fun selectApplication(aid: Int) {
        send(
            CMD_SELECT_APPLICATION,
            byteArrayOf((aid shr 16).toByte(), (aid shr 8).toByte(), aid.toByte()),
        )
    }

    fun getFileIds(): List<Int> = send(CMD_GET_FILE_IDS).map { it.u() }

    fun getFileSettings(fileId: Int): FileSettings =
        FileSettings.parse(send(CMD_GET_FILE_SETTINGS, byteArrayOf(fileId.toByte())))

    /** Value files store a signed 32-bit number, least significant byte first. */
    fun getValue(fileId: Int): Int =
        littleEndianInt(send(CMD_GET_VALUE, byteArrayOf(fileId.toByte())))

    /** Reads a whole standard/backup data file (offset 0, length 0 = "everything"). */
    fun readData(fileId: Int): ByteArray =
        send(CMD_READ_DATA, byteArrayOf(fileId.toByte(), 0, 0, 0, 0, 0, 0))

    /** Reads all records of a linear/cyclic record file. */
    fun readRecords(fileId: Int): ByteArray =
        send(CMD_READ_RECORDS, byteArrayOf(fileId.toByte(), 0, 0, 0, 0, 0, 0))

    private fun send(command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val result = ArrayList<Byte>()
        var response = io.transceive(wrap(command, data))
        while (true) {
            if (response.size < 2 || response[response.size - 2] != 0x91.toByte()) {
                throw DesfireException(-1, "Keine DESFire-Antwort (${response.toHex()})")
            }
            result.addAll(response.copyOfRange(0, response.size - 2).toList())
            when (val status = response.last().u()) {
                STATUS_OK -> return result.toByteArray()
                STATUS_ADDITIONAL_FRAME -> response = io.transceive(wrap(CMD_ADDITIONAL_FRAME))
                else -> throw DesfireException(status, describeStatus(status))
            }
        }
    }

    companion object {
        const val CMD_GET_VERSION = 0x60
        const val CMD_GET_APPLICATION_IDS = 0x6A
        const val CMD_SELECT_APPLICATION = 0x5A
        const val CMD_GET_FILE_IDS = 0x6F
        const val CMD_GET_FILE_SETTINGS = 0xF5
        const val CMD_GET_VALUE = 0x6C
        const val CMD_READ_DATA = 0xBD
        const val CMD_READ_RECORDS = 0xBB
        const val CMD_ADDITIONAL_FRAME = 0xAF

        const val STATUS_OK = 0x00
        const val STATUS_ADDITIONAL_FRAME = 0xAF
        const val STATUS_PERMISSION_DENIED = 0x9D
        const val STATUS_AUTHENTICATION_ERROR = 0xAE

        fun wrap(command: Int, data: ByteArray = ByteArray(0)): ByteArray =
            if (data.isEmpty()) {
                byteArrayOf(0x90.toByte(), command.toByte(), 0, 0, 0)
            } else {
                byteArrayOf(0x90.toByte(), command.toByte(), 0, 0, data.size.toByte()) + data + 0
            }

        fun describeStatus(status: Int): String = when (status) {
            STATUS_PERMISSION_DENIED -> "Zugriff verweigert (braucht Schlüssel)"
            STATUS_AUTHENTICATION_ERROR -> "Authentifizierung nötig"
            0xA0 -> "Applikation nicht gefunden"
            0xF0 -> "Datei nicht gefunden"
            0x1C -> "Befehl unbekannt"
            0x7E -> "Falsche Länge"
            else -> "Fehlercode 0x%02X".format(status)
        }
    }
}

internal fun Byte.u(): Int = toInt() and 0xFF

internal fun littleEndianInt(bytes: ByteArray, offset: Int = 0, length: Int = 4): Int {
    var value = 0
    for (i in length - 1 downTo 0) value = (value shl 8) or bytes[offset + i].u()
    return value
}

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02X".format(it) }
