package de.felice.chipcardreader.nfc

/**
 * ISO 7816-4: the "standard language" of smart cards (ID cards, bank cards,
 * VDV-KA tickets). A command (APDU) looks like:
 *
 *     CLA INS P1 P2 [Lc data] [Le]
 *
 * and every answer ends with a 2-byte status word (SW). 90 00 = OK.
 */
data class ResponseApdu(val data: ByteArray, val sw: Int) {
    val isSuccess get() = sw == 0x9000
    val swHex get() = "%04X".format(sw)
}

class Iso7816(private val io: Transceiver) {

    fun send(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray = ByteArray(0), le: Int? = 0): ResponseApdu {
        val apdu = buildApdu(cla, ins, p1, p2, data, le)
        var response = parse(io.transceive(apdu))

        // 6C xx = "wrong Le, ask again with Le = xx"
        if (response.sw shr 8 == 0x6C) {
            response = parse(io.transceive(buildApdu(cla, ins, p1, p2, data, response.sw and 0xFF)))
        }
        // 61 xx = "xx more bytes waiting, fetch them with GET RESPONSE"
        var collected = response.data
        while (response.sw shr 8 == 0x61) {
            response = parse(io.transceive(buildApdu(0x00, 0xC0, 0x00, 0x00, ByteArray(0), response.sw and 0xFF)))
            collected += response.data
        }
        return ResponseApdu(collected, response.sw)
    }

    /** SELECT an application by its name (AID). */
    fun selectByAid(aid: ByteArray, p2: Int = 0x00): ResponseApdu = send(0x00, 0xA4, 0x04, p2, aid)

    companion object {
        fun buildApdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray, le: Int?): ByteArray {
            var apdu = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
            if (data.isNotEmpty()) apdu += byteArrayOf(data.size.toByte()) + data
            if (le != null) apdu += le.toByte()
            return apdu
        }

        fun parse(raw: ByteArray): ResponseApdu {
            require(raw.size >= 2) { "Antwort zu kurz" }
            val sw = (raw[raw.size - 2].u() shl 8) or raw.last().u()
            return ResponseApdu(raw.copyOfRange(0, raw.size - 2), sw)
        }
    }
}
