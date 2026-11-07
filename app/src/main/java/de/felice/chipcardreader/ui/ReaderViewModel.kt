package de.felice.chipcardreader.ui

import android.app.Application
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import de.felice.chipcardreader.cards.CardDispatcher
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.data.LabelStore
import de.felice.chipcardreader.ndef.WriteRequest
import de.felice.chipcardreader.nfc.NdefWriter
import de.felice.chipcardreader.nfc.TagInspector
import de.felice.chipcardreader.nfc.TagSummary

/** One scan: the generic tag info plus whatever we understood of its content. */
data class ScanResult(val tag: TagSummary, val content: CardResult?)

sealed interface ReaderState {
    data object NoNfc : ReaderState
    data object NfcDisabled : ReaderState
    data object Waiting : ReaderState
    data class Done(val scan: ScanResult) : ReaderState
}

sealed interface WriteState {
    data object Idle : WriteState
    /** Waiting for the user to hold a sticker to the phone. */
    data class Armed(val request: WriteRequest) : WriteState
    data class Finished(val result: NdefWriter.Result) : WriteState
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    val labels = LabelStore(app)

    var state: ReaderState by mutableStateOf(ReaderState.Waiting)
        private set

    var writeState: WriteState by mutableStateOf(WriteState.Idle)
        private set

    fun updateNfcAvailability(hasNfc: Boolean, enabled: Boolean) {
        state = when {
            !hasNfc -> ReaderState.NoNfc
            !enabled -> ReaderState.NfcDisabled
            state is ReaderState.NoNfc || state is ReaderState.NfcDisabled -> ReaderState.Waiting
            else -> state
        }
    }

    fun armWrite(request: WriteRequest) { writeState = WriteState.Armed(request) }
    fun cancelWrite() { writeState = WriteState.Idle }

    /**
     * Runs on a background thread (NFC binder thread or our own).
     * If a write is armed, the tag gets written instead of read.
     */
    fun onTag(tag: Tag) {
        val armed = writeState as? WriteState.Armed
        if (armed != null) {
            writeState = WriteState.Finished(NdefWriter.write(tag, armed.request))
            return
        }
        state = ReaderState.Done(read(tag))
    }

    private fun read(tag: Tag): ScanResult {
        // 1) the generic "outside": chip type, memory, NDEF
        val summary = TagInspector.inspect(tag)
        // 2) the "inside" of smartcards: tickets, Mensa balance
        val content = IsoDep.get(tag)?.let { isoDep ->
            try {
                isoDep.connect()
                isoDep.timeout = 2000
                CardDispatcher.readIsoDep(summary.uid) { isoDep.transceive(it) }
            } catch (e: Exception) {
                // Most common cause: card moved away from the phone too early.
                CardResult.Failure(summary.uid, "Verbindung abgebrochen, Karte ruhig halten. (${e.javaClass.simpleName})")
            } finally {
                runCatching { isoDep.close() }
            }
        } ?: if (MifareClassic.get(tag) != null && summary.ndef == null) {
            CardResult.Other(
                summary.uid,
                summary.technologies,
                listOf("MIFARE Classic: Die Daten sind mit Schlüsseln geschützt, die wir nicht haben (und nicht knacken)."),
            )
        } else null
        return ScanResult(summary, content)
    }
}
