package de.felice.chipcardreader.ui

import android.app.Application
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.felice.chipcardreader.library.CatalogClient
import de.felice.chipcardreader.library.Iso28560
import de.felice.chipcardreader.library.LibraryTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import de.felice.chipcardreader.cards.CardDispatcher
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.data.LabelStore
import de.felice.chipcardreader.ndef.WriteRequest
import de.felice.chipcardreader.nfc.NdefWriter
import de.felice.chipcardreader.nfc.TagInspector
import de.felice.chipcardreader.nfc.TagSummary

/** One scan: the generic tag info plus whatever we understood of its content. */
data class ScanResult(val tag: TagSummary, val content: CardResult?, val library: LibraryTag? = null)

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Done(val result: CatalogClient.Result) : CatalogState
}

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

    /** Catalog lookups by media number (cached while the app runs). */
    val catalog = mutableStateMapOf<String, CatalogState>()

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
        val scan = read(tag)
        state = ReaderState.Done(scan)
        scan.library?.takeUnless { it.isPatronCard }?.let { lookUp(it) }
    }

    /** Ask the library's catalog for the title. Runs in the background. */
    fun lookUp(tag: LibraryTag, force: Boolean = false) {
        val base = labels.catalog(tag.libraryId) ?: return
        val current = catalog[tag.itemId]
        if (!force && (current is CatalogState.Loading ||
                (current as? CatalogState.Done)?.result is CatalogClient.Result.Found)) return
        catalog[tag.itemId] = CatalogState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            catalog[tag.itemId] = CatalogState.Done(CatalogClient.lookup(base, tag.itemId))
        }
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
        val library = summary.iso15693?.let { Iso28560.decode(it.data) }
        return ScanResult(summary, content, library)
    }
}
