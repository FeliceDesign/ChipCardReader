package de.felice.chipcardreader.cards

import de.felice.chipcardreader.cards.vdv.VdvKaReader
import de.felice.chipcardreader.nfc.DesfireClient
import de.felice.chipcardreader.nfc.DesfireException
import de.felice.chipcardreader.nfc.Iso7816
import de.felice.chipcardreader.nfc.Transceiver

/**
 * Decides what kind of card this is and which reader handles it.
 * Pure Kotlin (no Android classes) so it can be unit-tested.
 */
object CardDispatcher {

    fun readIsoDep(uid: String, io: Transceiver): CardResult {
        // 1) Try the eTicket standard first. It's an ISO 7816 application, so it
        //    can live on DESFire cards AND on Java cards that aren't DESFire.
        //    A failed SELECT doesn't change anything on the card.
        val vdv = VdvKaReader(Iso7816(io))
        val ticket = try {
            vdv.read()
        } catch (e: IllegalArgumentException) {
            return CardResult.Failure(uid, "eTicket erkannt, aber Daten nicht lesbar: ${e.message}")
        }
        if (ticket != null) return CardResult.Ticket(uid, ticket)

        // 2) Native DESFire (Mensa cards etc.)
        val client = DesfireClient(io)
        val appIds = try {
            client.getApplicationIds()
        } catch (e: DesfireException) {
            // Not a DESFire (or it hides its app list). Could be an ID card,
            // bank card, etc. — those speak ISO 7816 with different commands.
            return CardResult.Other(
                uid,
                listOf("ISO-DEP (ISO 14443-4)"),
                listOf(
                    "Keine DESFire-Karte (oder App-Liste geschützt).",
                    "Ausweis, Bankkarte & Co. werden noch nicht unterstützt.",
                    "Debug: eTicket-SELECT → SW ${vdv.selectStatus}, DESFire → ${e.message}",
                ),
            )
        }

        val dump = DesfireExplorer.explore(client, appIds)

        if (IntercardReader.matches(appIds)) {
            return try {
                val reading = IntercardReader.read(client)
                CardResult.Balance(uid, IntercardReader.NAME, reading.balance, reading.lastTransaction, dump)
            } catch (e: Exception) {
                CardResult.Failure(uid, "Intercard erkannt, aber Guthaben nicht lesbar: ${e.message}")
            }
        }
        return CardResult.Desfire(uid, dump)
    }
}
