package de.felice.chipcardreader.nfc

/**
 * ISO 15693 ("NFC-V", "vicinity cards"): the chip type used for library
 * books, ski passes and logistics labels. Works at a few cm on a phone,
 * but up to ~1 m on a big antenna like the library's self-checkout table.
 *
 * Pure parsing helpers, testable without Android.
 */
data class Iso15693Info(
    val dsfid: Int?,
    /** "Application Family Identifier": what the tag is used for / its state. */
    val afi: Int?,
    val blockCount: Int?,
    val blockSize: Int?,
    val icReference: Int?,
    val blocks: List<ByteArray>,
) {
    val data: ByteArray get() = blocks.fold(ByteArray(0)) { acc, b -> acc + b }

    /** Readable characters, e.g. the library's media number, dots for the rest. */
    val ascii: String
        get() = data.map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '·' }
            .joinToString("")
}

object Iso15693 {

    const val FLAG_HIGH_DATA_RATE = 0x02
    const val FLAG_ADDRESSED = 0x20
    const val CMD_READ_SINGLE_BLOCK = 0x20
    const val CMD_GET_SYSTEM_INFO = 0x2B

    /** Command frames. "Addressed" variants include the UID (some tags insist). */
    fun getSystemInfo(uid: ByteArray? = null) = frame(CMD_GET_SYSTEM_INFO, uid)
    fun readBlock(block: Int, uid: ByteArray? = null) = frame(CMD_READ_SINGLE_BLOCK, uid, byteArrayOf(block.toByte()))

    private fun frame(cmd: Int, uid: ByteArray?, params: ByteArray = ByteArray(0)): ByteArray {
        val flags = FLAG_HIGH_DATA_RATE or if (uid != null) FLAG_ADDRESSED else 0
        return byteArrayOf(flags.toByte(), cmd.toByte()) + (uid ?: ByteArray(0)) + params
    }

    /** First response byte = flags; bit 0 set = error. */
    fun isError(response: ByteArray) = response.isEmpty() || response[0].toInt() and 0x01 != 0

    /**
     * System info response:
     *   [flags][infoFlags][UID 8][DSFID?][AFI?][blocks-1, (blockSize-1)&0x1F ?][IC ref?]
     * infoFlags bit 0 = DSFID present, 1 = AFI, 2 = memory size, 3 = IC reference.
     */
    fun parseSystemInfo(r: ByteArray): Iso15693Info? {
        if (isError(r) || r.size < 10) return null
        val info = r[1].toInt() and 0xFF
        var i = 10
        fun next(): Int? = if (i < r.size) r[i++].toInt() and 0xFF else null
        val dsfid = if (info and 0x01 != 0) next() else null
        val afi = if (info and 0x02 != 0) next() else null
        var blockCount: Int? = null
        var blockSize: Int? = null
        if (info and 0x04 != 0) {
            blockCount = next()?.plus(1)
            blockSize = next()?.let { (it and 0x1F) + 1 }
        }
        val ic = if (info and 0x08 != 0) next() else null
        return Iso15693Info(dsfid, afi, blockCount, blockSize, ic, emptyList())
    }

    /**
     * AFI values used by libraries (ISO 28560-1). The self-checkout table flips
     * this byte when you borrow a book, so the security gate stays quiet.
     */
    fun afiMeaning(afi: Int): String? = when (afi) {
        0x07 -> "Bibliothek: im Bestand / gesichert (Gate würde piepen)"
        0xC2 -> "Bibliothek: ausgeliehen / entsichert"
        0x00 -> "nicht gesetzt"
        else -> null
    }
}
