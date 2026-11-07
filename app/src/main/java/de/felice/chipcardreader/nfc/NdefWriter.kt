package de.felice.chipcardreader.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import de.felice.chipcardreader.ndef.VCard
import de.felice.chipcardreader.ndef.WriteRequest

/**
 * Writes NDEF onto stickers. Safety rules:
 *  - NEVER writes to smartcards (IsoDep): tickets, Mensa cards, bank cards.
 *  - Only formats blank Ultralight/NTAG stickers, nothing else.
 *  - Never locks a tag (that would be permanent).
 */
object NdefWriter {

    sealed interface Result {
        data class Success(val bytes: Int, val capacity: Int) : Result
        data class Error(val message: String) : Result
    }

    fun toMessage(request: WriteRequest): NdefMessage = NdefMessage(
        when (request) {
            is WriteRequest.Link -> NdefRecord.createUri(WriteRequest.normalizeUrl(request.url))
            is WriteRequest.Text -> NdefRecord.createTextRecord("de", request.text)
            is WriteRequest.Contact -> NdefRecord.createMime("text/vcard", VCard.build(request).toByteArray())
        },
    )

    fun write(tag: Tag, request: WriteRequest): Result {
        if (IsoDep.get(tag) != null) {
            return Result.Error("Das ist eine Chipkarte (Ticket, Mensa, Bank …). Darauf schreibe ich nicht.")
        }
        val message = toMessage(request)
        val size = message.byteArrayLength

        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> Result.Error("Dieser Tag ist schreibgeschützt.")
                    size > ndef.maxSize -> Result.Error("Zu groß: $size Byte, auf den Tag passen nur ${ndef.maxSize}.")
                    else -> {
                        ndef.writeNdefMessage(message)
                        Result.Success(size, ndef.maxSize)
                    }
                }
            } catch (e: Exception) {
                Result.Error("Schreiben fehlgeschlagen, Tag ruhig halten. (${e.javaClass.simpleName})")
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null && MifareUltralight.get(tag) != null) {
            return try {
                formatable.connect()
                formatable.format(message)
                Result.Success(size, size)
            } catch (e: Exception) {
                Result.Error("Formatieren fehlgeschlagen. (${e.javaClass.simpleName})")
            } finally {
                runCatching { formatable.close() }
            }
        }
        return Result.Error("Dieser Tag unterstützt kein NDEF. Nimm einen NTAG-Sticker (z. B. NTAG213/215).")
    }
}
