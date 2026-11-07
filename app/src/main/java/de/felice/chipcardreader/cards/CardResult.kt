package de.felice.chipcardreader.cards

import java.math.BigDecimal

/** Everything the UI can show after a card was tapped. */
sealed interface CardResult {
    val uid: String

    /** A prepaid card (e.g. Mensa) where we could read the balance. */
    data class Balance(
        override val uid: String,
        val system: String,
        val balance: BigDecimal,
        val lastTransaction: BigDecimal?,
        val dump: DesfireDump?,
    ) : CardResult

    /** A DESFire card we don't understand (yet) — show what's freely readable. */
    data class Desfire(override val uid: String, val dump: DesfireDump) : CardResult

    /** Some other chip (bank card, ID card, MIFARE Classic, ...). */
    data class Other(
        override val uid: String,
        val technologies: List<String>,
        val notes: List<String>,
    ) : CardResult

    data class Failure(override val uid: String, val message: String) : CardResult
}

data class DesfireDump(
    val version: String?,
    val applications: List<AppDump>,
)

data class AppDump(
    val aid: Int,
    val files: List<FileDump>,
    val error: String? = null,
) {
    val aidHex get() = "%06X".format(aid)
}

data class FileDump(
    val fileId: Int,
    val type: String,
    val freelyReadable: Boolean,
    /** Hex data, value, or error text. */
    val content: String,
)
