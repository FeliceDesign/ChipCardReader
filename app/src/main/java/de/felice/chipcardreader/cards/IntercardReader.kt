package de.felice.chipcardreader.cards

import de.felice.chipcardreader.nfc.DesfireClient
import de.felice.chipcardreader.nfc.FileSettings
import java.math.BigDecimal

/**
 * A prepaid system living in one DESFire application.
 * Add a new card system = add one object implementing this.
 */
interface BalanceReader {
    val name: String
    val appId: Int
    fun read(client: DesfireClient): Reading

    data class Reading(val balance: BigDecimal, val lastTransaction: BigDecimal?)

    companion object {
        val ALL: List<BalanceReader> = listOf(IntercardReader, MagnaCartaReader)
    }
}

/**
 * Intercard: the system behind many German Mensa cards (Studierendenwerke).
 *
 * Known layout (documented by the open-source projects MensaGuthaben and
 * Metrodroid, confirmed with a real card in this project):
 *  - application 0x5F8415, file 1 = value file, freely readable
 *  - the value is in tenths of a cent (raw 12340 = 12,34 €)
 *  - "limited credit value" in the file settings = last transaction
 */
object IntercardReader : BalanceReader {
    override val name = "Intercard"
    override val appId = 0x5F8415
    const val BALANCE_FILE = 1

    override fun read(client: DesfireClient): BalanceReader.Reading {
        client.selectApplication(appId)
        val settings = client.getFileSettings(BALANCE_FILE)
        require(settings is FileSettings.Value) { "Datei $BALANCE_FILE ist keine Wertdatei" }
        val raw = client.getValue(BALANCE_FILE)
        return BalanceReader.Reading(
            balance = tenthCentsToEuro(raw),
            lastTransaction = tenthCentsToEuro(settings.limitedCreditValue)
                .takeIf { settings.limitedCreditValue != 0 },
        )
    }

    /** 1 € = 100 ct = 1000 tenth-cents, so move the decimal point 3 places. */
    fun tenthCentsToEuro(raw: Int): BigDecimal = BigDecimal(raw).movePointLeft(3)
}

/**
 * MagnaCarta: another Mensa card system (e.g. some Studierendenwerke).
 *
 * Layout from MensaGuthaben (NOT yet confirmed with a real card here):
 *  - application 0xF080F3, file 2 = data file, freely readable
 *  - bytes 6..7 (big-endian) = balance in cents
 */
object MagnaCartaReader : BalanceReader {
    override val name = "MagnaCarta"
    override val appId = 0xF080F3
    const val BALANCE_FILE = 2

    override fun read(client: DesfireClient): BalanceReader.Reading {
        client.selectApplication(appId)
        val data = client.readData(BALANCE_FILE)
        require(data.size >= 8) { "Datei $BALANCE_FILE zu kurz (${data.size} Bytes)" }
        val cents = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        return BalanceReader.Reading(BigDecimal(cents).movePointLeft(2), lastTransaction = null)
    }
}
