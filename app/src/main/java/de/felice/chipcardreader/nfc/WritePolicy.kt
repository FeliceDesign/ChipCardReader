package de.felice.chipcardreader.nfc

/**
 * THE one rule for "may this app write to this tag?".
 * Used by the UI (to show "beschreibbar") AND by NdefWriter (to actually write),
 * so the two can never disagree again.
 *
 * Note: Android's NdefFormatable only means "this chip TYPE could hold NDEF".
 * Android says that for DESFire and MIFARE Classic cards too, even though
 * their keys make formatting impossible. So we don't trust it alone.
 */
object WritePolicy {

    data class TagFacts(
        val isSmartcard: Boolean, // IsoDep: tickets, Mensa, bank cards
        val isIso15693: Boolean,  // NfcV: library books, logistics labels
        val isUltralight: Boolean, // NTAG / Ultralight stickers
        val hasNdef: Boolean,
        val ndefWritable: Boolean,
        val formatable: Boolean,
    )

    sealed interface Decision {
        data object WriteNdef : Decision
        data object FormatBlankSticker : Decision
        data class Refuse(val reason: String) : Decision
    }

    fun decide(t: TagFacts): Decision = when {
        t.isSmartcard -> Decision.Refuse("Das ist eine Chipkarte (Ticket, Mensa, Bank …). Darauf schreibe ich nicht.")
        t.isIso15693 -> Decision.Refuse("ISO-15693-Etikett (z. B. Bibliotheksbuch). Darauf schreibe ich nicht.")
        t.hasNdef && t.ndefWritable -> Decision.WriteNdef
        t.hasNdef -> Decision.Refuse("Dieser Tag ist schreibgeschützt.")
        t.formatable && t.isUltralight -> Decision.FormatBlankSticker
        else -> Decision.Refuse("Dieser Tag unterstützt kein NDEF. Nimm einen NTAG-Sticker (z. B. NTAG213/215).")
    }
}
