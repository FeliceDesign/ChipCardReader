package de.felice.chipcardreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.felice.chipcardreader.cards.AppDump
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.cards.DesfireDump
import de.felice.chipcardreader.cards.vdv.VdvTicket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CardScreen(state: ReaderState, onOpenNfcSettings: () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ChipCardReader", style = MaterialTheme.typography.headlineMedium)
                when (state) {
                    ReaderState.NoNfc -> Hint("Dieses Gerät hat kein NFC. Sad.")
                    ReaderState.NfcDisabled -> {
                        Hint("NFC ist ausgeschaltet.")
                        Button(onClick = onOpenNfcSettings) { Text("NFC-Einstellungen öffnen") }
                    }
                    ReaderState.Waiting -> Hint("Halte eine Karte an die Rückseite des Handys.")
                    ReaderState.Reading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    is ReaderState.Done -> ResultView(state.result)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun ResultView(result: CardResult) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (result) {
                        is CardResult.Balance -> {
                            Text(result.system, style = MaterialTheme.typography.titleMedium)
                            Text(euro(result.balance), style = MaterialTheme.typography.displayMedium)
                            result.lastTransaction?.let { Text("Letzte Buchung: ${euro(it)}") }
                        }
                        is CardResult.Ticket -> {
                            Text("eTicket (VDV-KA)", style = MaterialTheme.typography.titleMedium)
                            val c = result.card
                            if (c.tickets.isEmpty()) Text("Keine Fahrberechtigung auf der Karte.")
                            Text("Karte gültig: ${range(c.appValidFrom, c.appValidUntil)}")
                            c.appOrg?.let { Text("Herausgeber: Organisation $it") }
                        }
                        is CardResult.Desfire -> {
                            Text("MIFARE DESFire", style = MaterialTheme.typography.titleMedium)
                            if (result.dump.applications.isEmpty()) {
                                Text("Die Karte zeigt keine Applikationen ohne Schlüssel. Hier ist nichts frei lesbar.")
                            } else {
                                Text("Format unbekannt — unten siehst du, was frei lesbar ist.")
                            }
                        }
                        is CardResult.Other -> {
                            Text(result.technologies.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                            result.notes.forEach { Text(it) }
                        }
                        is CardResult.Failure -> {
                            Text("Fehler", style = MaterialTheme.typography.titleMedium)
                            Text(result.message)
                        }
                    }
                    Text("UID: ${result.uid}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (result is CardResult.Ticket) {
            items(result.card.tickets) { TicketCard(it) }
            item { Text("Rohdaten", style = MaterialTheme.typography.titleMedium) }
            item { Mono(result.card.rawDirectory) }
            items(result.card.tickets) { Mono(it.raw) }
        }
        val dump: DesfireDump? = when (result) {
            is CardResult.Balance -> result.dump
            is CardResult.Desfire -> result.dump
            else -> null
        }
        if (dump != null) {
            item { Text("Rohdaten", style = MaterialTheme.typography.titleMedium) }
            dump.version?.let { v -> item { Mono("Version: $v") } }
            items(dump.applications, key = { it.aid }) { app -> AppCard(app) }
        }
    }
}

@Composable
private fun TicketCard(ticket: VdvTicket) {
    val validity = ticket.validityAt(LocalDateTime.now())
    val (badge, color) = when (validity) {
        VdvTicket.Validity.VALID -> "✅ gültig" to MaterialTheme.colorScheme.primary
        VdvTicket.Validity.EXPIRED -> "❌ abgelaufen" to MaterialTheme.colorScheme.error
        VdvTicket.Validity.NOT_YET -> "⏳ noch nicht gültig" to MaterialTheme.colorScheme.tertiary
        VdvTicket.Validity.UNKNOWN -> "❔ unbekannt" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ticket.productName, style = MaterialTheme.typography.titleLarge)
            Text(badge, color = color, style = MaterialTheme.typography.titleMedium)
            Text("Gültig: ${range(ticket.validFrom, ticket.validUntil)}")
            ticket.infoText?.takeIf { it.isNotBlank() && it != ticket.productName }?.let { Text(it) }
            Text(
                "Produkt ${ticket.productNumber ?: "?"} · Org ${ticket.productOrg ?: "?"} · " +
                    "Nr. ${ticket.entitlementNumber ?: "?"}" +
                    (ticket.status?.let { " · Status 0x%02X".format(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun range(from: LocalDateTime?, until: LocalDateTime?): String =
    "${from?.format(DATE) ?: "?"} – ${until?.format(DATE) ?: "?"}"

@Composable
private fun AppCard(app: AppDump) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("App 0x${app.aidHex}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            app.error?.let { Text(it) }
            app.files.forEach { f ->
                Mono("Datei ${f.fileId} · ${f.type}${if (f.freelyReadable) " · frei" else ""}\n${f.content}")
            }
        }
    }
}

@Composable
private fun Mono(text: String) = SelectionContainer {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

private fun euro(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value.setScale(2, RoundingMode.DOWN))
