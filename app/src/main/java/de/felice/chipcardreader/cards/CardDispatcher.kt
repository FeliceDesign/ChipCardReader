package de.felice.chipcardreader.cards

import de.felice.chipcardreader.nfc.DesfireClient
import de.felice.chipcardreader.nfc.DesfireException
import de.felice.chipcardreader.nfc.Transceiver

/**
 * Decides what kind of card this is and which reader handles it.
 * Pure Kotlin (no Android classes) so it can be unit-tested.
 */
object CardDispatcher {

    fun readIsoDep(uid: String, io: Transceiver): CardResult {
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
