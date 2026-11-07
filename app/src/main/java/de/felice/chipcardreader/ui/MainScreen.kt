package de.felice.chipcardreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.felice.chipcardreader.ndef.WriteRequest
import de.felice.chipcardreader.nfc.NdefWriter

@Composable
fun MainScreen(viewModel: ReaderViewModel, onOpenNfcSettings: () -> Unit) {
    val state = viewModel.state
    val writeState = viewModel.writeState
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LabelEdit?>(null) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state) {
        if (state is ReaderState.Done) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            tab = 0 // something was read → show it
        }
    }
    LaunchedEffect(writeState) {
        if (writeState is WriteState.Finished) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "ChipCardReader",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Lesen") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Schreiben") })
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == 0) {
                        readTab(state, viewModel.labels, onOpenNfcSettings) { editing = it }
                    } else {
                        writeTab(writeState, viewModel::armWrite, viewModel::cancelWrite)
                    }
                }
            }
        }
        editing?.let { edit ->
            LabelDialog(edit, onDismiss = { editing = null }) { name ->
                edit.save(name)
                editing = null
            }
        }
    }
}

// --- Schreiben -----------------------------------------------------------------

private enum class WriteKind(val label: String) { LINK("🔗 Link"), TEXT("📝 Text"), CONTACT("👤 Kontakt") }

private fun LazyListScope.writeTab(
    writeState: WriteState,
    onArm: (WriteRequest) -> Unit,
    onCancel: () -> Unit,
) {
    when (writeState) {
        is WriteState.Armed -> item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("📲 Jetzt Sticker dranhalten …", style = MaterialTheme.typography.titleLarge)
                    Text("Bestehender Inhalt wird überschrieben. Chipkarten werden nie beschrieben.")
                    OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                }
            }
        }
        is WriteState.Finished -> item {
            val ok = writeState.result as? NdefWriter.Result.Success
            InfoCard(
                if (ok != null) "✅ Geschrieben" else "❌ Nicht geschrieben",
                if (ok != null) "${ok.bytes} von ${ok.capacity} Byte belegt."
                else (writeState.result as NdefWriter.Result.Error).message,
            )
        }
        WriteState.Idle -> Unit
    }
    if (writeState !is WriteState.Armed) item { WriteForm(onArm) }
}

@Composable
private fun WriteForm(onArm: (WriteRequest) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(WriteKind.LINK) }
    var url by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var org by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var website by rememberSaveable { mutableStateOf("") }

    val request: WriteRequest = when (kind) {
        WriteKind.LINK -> WriteRequest.Link(url)
        WriteKind.TEXT -> WriteRequest.Text(text)
        WriteKind.CONTACT -> WriteRequest.Contact(name, phone, email, website, org)
    }
    val problem = request.validate()
    val size = if (problem == null) runCatching { NdefWriter.toMessage(request).byteArrayLength }.getOrNull() else null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WriteKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
            }
        }
        when (kind) {
            WriteKind.LINK -> Field("Link, z. B. felice.design", url, KeyboardType.Uri) { url = it }
            WriteKind.TEXT -> OutlinedTextField(
                text, { text = it }, label = { Text("Text") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            WriteKind.CONTACT -> {
                Field("Name *", name) { name = it }
                Field("Firma / Beruf", org) { org = it }
                Field("Telefon", phone, KeyboardType.Phone) { phone = it }
                Field("E-Mail", email, KeyboardType.Email) { email = it }
                Field("Website", website, KeyboardType.Uri) { website = it }
            }
        }
        size?.let {
            Text(
                "$it Byte · " + when {
                    it <= 144 -> "passt auf jeden NTAG213"
                    it <= 504 -> "braucht mind. NTAG215"
                    it <= 888 -> "braucht NTAG216"
                    else -> "zu groß für normale Sticker"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { onArm(request) }, enabled = problem == null, modifier = Modifier.fillMaxWidth()) {
            Text("Auf Sticker schreiben")
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}
