package de.felice.chipcardreader.cards.vdv

import java.time.LocalDate
import java.time.LocalDateTime

data class VdvCard(
    val appInstance: Long?,
    val appOrg: Int?,
    val appValidFrom: LocalDateTime?,
    val appValidUntil: LocalDateTime?,
    val tickets: List<VdvTicket>,
    val log: List<VdvLogEntry> = emptyList(),
    val logRaw: String? = null,
    /** The directory lists a customer-data block (name, birthday). PIN-protected, never read. */
    val hasCustomerData: Boolean = false,
    val rawDirectory: String,
)

/** One event from the card's own logbook. */
data class VdvLogEntry(
    val time: LocalDateTime?,
    /** What kind of event, by the TLV block it was found in. */
    val kind: String,
    val transactionType: Int,
    val terminalType: Int,
    val terminalNumber: Int,
    val terminalOrg: Int,
    val placeType: Int,
    val placeNumber: Int,
    val placeOrg: Int,
)

data class VdvTicket(
    val entitlementNumber: Long?,
    val issuerOrg: Int?,
    val productNumber: Int?,
    val productOrg: Int?,
    val validFrom: LocalDateTime?,
    val validUntil: LocalDateTime?,
    val status: Int?,
    val infoText: String?,
    val raw: String,
    /** Personal tickets (like the D-Ticket) carry the passenger so inspectors can compare with an ID. */
    val passenger: Passenger? = null,
    /** Organisations whose area the ticket was originally sold for. */
    val areaOrgs: List<Int> = emptyList(),
) {
    val productName: String
        get() = DEUTSCHLANDTICKET_PRODUCTS[productNumber]
            ?: infoText?.takeIf { it.isNotBlank() }
            ?: productNumber?.let { "Produkt $it" }
            ?: "Unbekanntes Ticket"

    val isDeutschlandticket get() = productNumber in DEUTSCHLANDTICKET_PRODUCTS

    fun validityAt(now: LocalDateTime): Validity = when {
        validFrom == null || validUntil == null -> Validity.UNKNOWN
        now.isBefore(validFrom) -> Validity.NOT_YET
        now.isAfter(validUntil) -> Validity.EXPIRED
        else -> Validity.VALID
    }

    enum class Validity { VALID, EXPIRED, NOT_YET, UNKNOWN }

    companion object {
        /**
         * Nationwide product numbers for the Deutschlandticket, the same in every
         * Verbund (source: product tables in github.com/TheEnbyperor/zuegli).
         */
        val DEUTSCHLANDTICKET_PRODUCTS = mapOf(
            9999 to "Deutschlandticket",
            9998 to "Deutschlandticket (Jobticket)",
            9997 to "Deutschlandticket (Startkarte)",
            9996 to "Deutschlandticket (Semesterticket)",
            9995 to "Deutschlandticket (Schülerticket)",
        )
    }
}

data class Passenger(
    /** As stored on the card. VDV uses '#' between first and last name. */
    val rawName: String,
    val birthDate: LocalDate?,
    val genderCode: Int,
) {
    val name: String get() = rawName.replace('#', ' ').trim()

    /** "Felix Weber" → "F•••• W••••" (like eTicket.app does). */
    val maskedName: String
        get() = name.split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.first() + "•".repeat((it.length - 1).coerceAtLeast(1)) }
}
