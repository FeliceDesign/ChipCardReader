package de.felice.chipcardreader.cards.vdv

import de.felice.chipcardreader.nfc.BerTlv
import de.felice.chipcardreader.nfc.Iso7816
import de.felice.chipcardreader.nfc.ResponseApdu
import de.felice.chipcardreader.nfc.Tlv
import de.felice.chipcardreader.nfc.toHex

/**
 * VDV-Kernapplikation (VDV-KA): the German eTicket standard, used for the
 * Deutschlandticket on chip cards.
 *
 * The spec isn't public. Everything here follows what open-source projects
 * figured out by reading their own cards:
 *  - github.com/czietz/parse-vdv-chipcard (MIT) — commands, tags, field layout
 *  - github.com/TheEnbyperor/zuegli         — confirms the AID
 *
 * Flow:
 *  1. SELECT the KA application → card answers with the "Applikationsverzeichnis"
 *     (a table of contents with pointers)
 *  2. For every "Berechtigung" (ticket) pointer: GET DATA → ticket TLV
 *
 * Only reads what the card gives out without keys. Customer data (name,
 * birthday) is PIN-protected and deliberately NOT read.
 */
class VdvKaReader(private val card: Iso7816) {

    /** Status word of the SELECT, handy for debugging unknown cards. */
    var selectStatus: String? = null
        private set

    /** Returns null if the card has no VDV-KA application. */
    fun read(): VdvCard? {
        val select = card.selectByAid(AID, p2 = 0x0C)
        selectStatus = select.swHex
        if (!select.isSuccess || select.data.isEmpty()) return null

        val root = BerTlv.parse(select.data)
        val directory = root.firstOrNull { it.tag == Tags.APPLIKATIONSVERZEICHNIS }
            ?: return null

        val appStatic = directory.findAll(Tags.APPLIKATIONSDATEN_STATISCH).firstOrNull()?.value
        val app = appStatic?.let(::decodeAppStatic)

        val tickets = directory.childrenWith(Tags.VERZ_BERECHTIGUNG).mapNotNull { entry ->
            val pointer = entry.child(Tags.ZEIGER)?.value?.firstOrNull() ?: return@mapNotNull null
            readTicket(entry, pointer.toInt() and 0xFF)
        }

        return VdvCard(
            appInstance = app?.instance,
            appOrg = app?.org,
            appValidFrom = app?.validFrom,
            appValidUntil = app?.validUntil,
            tickets = tickets,
            rawDirectory = root.joinToString("") { it.pretty(names = Tags.NAMES) },
        )
    }

    private fun readTicket(directoryEntry: Tlv, pointer: Int): VdvTicket {
        val response = readChained(Tags.BERECHTIGUNG, pointer)
        val tlvs = if (response.isSuccess) runCatching { BerTlv.parse(response.data) }.getOrDefault(emptyList()) else emptyList()
        // Search the ticket data first, then the directory entry, for each field.
        val all = tlvs + directoryEntry
        fun find(tag: Int) = all.firstNotNullOfOrNull { it.findAll(tag).firstOrNull() }?.value

        val static = find(Tags.BERECHTIGUNG_STATISCH)?.let(::decodeTicketStatic)
        return VdvTicket(
            entitlementNumber = static?.number,
            issuerOrg = static?.issuerOrg,
            productNumber = static?.product,
            productOrg = static?.productOrg,
            validFrom = static?.validFrom,
            validUntil = static?.validUntil,
            status = find(Tags.BERECHTIGUNG_DYNAMISCH)?.firstOrNull()?.let { it.toInt() and 0xFF },
            infoText = find(Tags.INFOTEXT)?.let(::decodeText),
            raw = if (response.isSuccess) tlvs.joinToString("") { it.pretty(names = Tags.NAMES) }
            else "GET DATA fehlgeschlagen (SW ${response.swHex})",
        )
    }

    /**
     * GET DATA with "command chaining": data can be longer than one 256-byte
     * answer. CLA 0x10 = "more is coming", the final CLA 0x00 ends the chain.
     */
    private fun readChained(tag: Int, pointer: Int): ResponseApdu {
        val request = byteArrayOf(tag.toByte(), pointer.toByte())
        var data = ByteArray(0)
        var last: ResponseApdu
        while (true) {
            last = card.send(0x10, 0xCA, 0x01, 0xF0, request)
            data += last.data
            if (!last.isSuccess) return ResponseApdu(data, last.sw)
            if (last.data.size < 256) break
        }
        card.send(0x00, 0xCA, 0x01, 0xF0, request) // closes the chain, no new data
        return ResponseApdu(data, last.sw)
    }

    companion object {
        /** "D276000135" (registered German RID) + "KANM01" + 00 */
        val AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x01, 0x35,
            0x4B, 0x41, 0x4E, 0x4D, 0x30, 0x31, 0x00,
        )
    }
}

/** Tag numbers, named like in the (non-public) VDV-KA spec. */
object Tags {
    const val APPLIKATIONSVERZEICHNIS = 0xE0
    const val VERZ_APPLIKATIONSDATEN = 0xE2
    const val APPLIKATIONSDATEN_STATISCH = 0x81
    const val BERECHTIGUNG = 0xE8
    const val VERZ_BERECHTIGUNG = 0xE9
    const val BERECHTIGUNG_STATISCH = 0x83
    const val BERECHTIGUNG_DYNAMISCH = 0x84
    const val ZEIGER = 0xC0
    const val INFOTEXT = 0xC7

    val NAMES = mapOf(
        0xE0 to "Applikationsverzeichnis",
        0xE1 to "Applikationsdaten",
        0xE2 to "Verz. Applikationsdaten",
        0x80 to "App dynamisch",
        0x81 to "App statisch",
        0xEE to "App separate Daten",
        0x91 to "Schlüsselversionen",
        0x99 to "Ausgabetransaktion",
        0xE3 to "Applikationslogbuch",
        0xE4 to "Verz. Logbuch",
        0xE6 to "Kundendaten",
        0xE7 to "Verz. Kundendaten",
        0xE8 to "Berechtigung",
        0xE9 to "Verz. Berechtigung",
        0x83 to "Berechtigung statisch",
        0x84 to "Berechtigung dynamisch",
        0xEA to "Berechtigung separate Daten",
        0x85 to "Produktspezifisch",
        0xEB to "Schlüsselregister",
        0xEC to "Verz. Schlüsselregister",
        0xC0 to "Zeiger",
        0xC3 to "Letzte Transaktion",
        0xC7 to "Infotext",
        0x90 to "Prioritäten",
    )
}
