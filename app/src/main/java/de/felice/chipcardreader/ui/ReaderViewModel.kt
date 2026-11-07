package de.felice.chipcardreader.ui

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.felice.chipcardreader.cards.CardDispatcher
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.data.LabelStore
import de.felice.chipcardreader.nfc.toHex

sealed interface ReaderState {
    data object NoNfc : ReaderState
    data object NfcDisabled : ReaderState
    data object Waiting : ReaderState
    data object Reading : ReaderState
    data class Done(val result: CardResult) : ReaderState
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    val labels = LabelStore(app)

    var state: ReaderState by mutableStateOf(ReaderState.Waiting)
        private set

    fun updateNfcAvailability(hasNfc: Boolean, enabled: Boolean) {
        state = when {
            !hasNfc -> ReaderState.NoNfc
            !enabled -> ReaderState.NfcDisabled
            state is ReaderState.NoNfc || state is ReaderState.NfcDisabled -> ReaderState.Waiting
            else -> state
        }
    }

    /** Runs on the NFC binder thread. Compose state is thread-safe to write. */
    fun onTag(tag: Tag) {
        state = ReaderState.Reading
        state = ReaderState.Done(read(tag))
    }

    private fun read(tag: Tag): CardResult {
        val uid = tag.id.toHex(":")
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            return try {
                isoDep.connect()
                isoDep.timeout = 2000
                CardDispatcher.readIsoDep(uid) { isoDep.transceive(it) }
            } catch (e: Exception) {
                // Most common cause: card moved away from the phone too early.
                CardResult.Failure(uid, "Verbindung abgebrochen — Karte ruhig halten. (${e.javaClass.simpleName})")
            } finally {
                runCatching { isoDep.close() }
            }
        }

        val techs = tag.techList.map { it.substringAfterLast('.') }
        val notes = buildList {
            if (MifareClassic.get(tag) != null) {
                add("MIFARE Classic: Daten sind mit Schlüsseln geschützt, die wir nicht haben (und nicht knacken).")
            }
            if (MifareUltralight.get(tag) != null) {
                add("MIFARE Ultralight: oft Einzelfahrscheine oder Eintrittskarten.")
            }
            if (isEmpty()) add("Dieser Kartentyp wird noch nicht unterstützt.")
        }
        return CardResult.Other(uid, techs, notes)
    }
}
