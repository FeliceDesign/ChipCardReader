package de.felice.chipcardreader

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import de.felice.chipcardreader.ui.MainScreen
import de.felice.chipcardreader.ui.ReaderViewModel
import kotlin.concurrent.thread

/**
 * Two ways a tag reaches us:
 *  1. App is open → NFC "reader mode" hands every tag straight to us.
 *  2. App is closed → Android starts us via the TECH_DISCOVERED intent filter
 *     in the manifest ("auto-open"), with the tag inside the Intent.
 */
class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val viewModel: ReaderViewModel by viewModels()
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            MainScreen(
                viewModel = viewModel,
                onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
            )
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action !in NFC_ACTIONS) return
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        // Card I/O blocks, so never on the UI thread.
        thread { viewModel.onTag(tag) }
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
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
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

    private companion object {
        val NFC_ACTIONS = setOf(
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED,
        )
    }
}
