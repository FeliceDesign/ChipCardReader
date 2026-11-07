package de.felice.chipcardreader

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import de.felice.chipcardreader.ui.CardScreen
import de.felice.chipcardreader.ui.ReaderViewModel

/**
 * Uses NFC "reader mode": while this screen is open, the phone hands every
 * tapped card straight to us (no system popup, no NDEF parsing).
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: ReaderViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            CardScreen(
                state = viewModel.state,
                labels = viewModel.labels,
                onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        viewModel.updateNfcAvailability(hasNfc = adapter != null, enabled = adapter?.isEnabled == true)
        adapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /** Called on a background thread — that's fine, card I/O blocks anyway. */
    override fun onTagDiscovered(tag: Tag) {
        viewModel.onTag(tag)
    }
}
