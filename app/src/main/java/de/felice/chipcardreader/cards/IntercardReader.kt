package de.felice.chipcardreader.cards

import de.felice.chipcardreader.nfc.DesfireClient
import de.felice.chipcardreader.nfc.FileSettings
import java.math.BigDecimal

/**
 * Intercard: the system behind many German Mensa cards (Studierendenwerke).
 *
 * Known layout (documented by the open-source projects MensaGuthaben and
 * Metrodroid):
 *  - application 0x5F8415, file 1 = value file, freely readable
 *  - the value is in tenths of a cent (raw 12340 = 12,34 €)
 *  - "limited credit value" in the file settings = last transaction
 */
object IntercardReader {
    const val APP_ID = 0x5F8415
    const val BALANCE_FILE = 1
    const val NAME = "Intercard (Mensa)"

    fun matches(appIds: List<Int>) = APP_ID in appIds

    fun read(client: DesfireClient): Reading {
        client.selectApplication(APP_ID)
        val settings = client.getFileSettings(BALANCE_FILE)
        require(settings is FileSettings.Value) { "Datei $BALANCE_FILE ist keine Wertdatei" }
        val raw = client.getValue(BALANCE_FILE)
        return Reading(
            balance = tenthCentsToEuro(raw),
            lastTransaction = tenthCentsToEuro(settings.limitedCreditValue)
                .takeIf { settings.limitedCreditValue != 0 },
        )
    }

    data class Reading(val balance: BigDecimal, val lastTransaction: BigDecimal?)

    /** 1 € = 100 ct = 1000 tenth-cents, so move the decimal point 3 places. */
    fun tenthCentsToEuro(raw: Int): BigDecimal = BigDecimal(raw).movePointLeft(3)
}
