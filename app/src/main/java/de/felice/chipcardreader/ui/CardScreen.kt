package de.felice.chipcardreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.felice.chipcardreader.cards.AppDump
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.cards.DesfireDump
import de.felice.chipcardreader.cards.vdv.VdvCard
import de.felice.chipcardreader.cards.vdv.Passenger
import de.felice.chipcardreader.cards.vdv.VdvTicket
import de.felice.chipcardreader.data.LabelStore
import de.felice.chipcardreader.nfc.Iso15693
import de.felice.chipcardreader.nfc.Iso15693Info
import de.felice.chipcardreader.nfc.NdefInfo
import de.felice.chipcardreader.nfc.WritePolicy
import de.felice.chipcardreader.nfc.toHex
import de.felice.chipcardreader.nfc.TagSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Content of the "Lesen" tab. Items go into the parent LazyColumn. */
fun LazyListScope.readTab(state: ReaderState, labels: LabelStore, onOpenNfcSettings: () -> Unit, onEdit: (LabelEdit) -> Unit) {
    when (state) {
        ReaderState.NoNfc -> item { Hint("Dieses Gerät hat kein NFC. Sad.") }
        ReaderState.NfcDisabled -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("NFC ist ausgeschaltet.")
                Button(onClick = onOpenNfcSettings) { Text("NFC-Einstellungen öffnen") }
            }
        }
        ReaderState.Waiting -> item {
            Hint("Halte eine Karte oder einen NFC-Sticker an die Rückseite des Handys.")
        }
        is ReaderState.Done -> {
            state.scan.content?.let { result(it, labels, onEdit) }
            state.scan.tag.ndef?.let { item { NdefCard(it) } }
            state.scan.tag.iso15693?.let { item { Iso15693Card(it) } }
            item { TagInfoCard(state.scan.tag) }
            item {
                Text(
                    "Nächste Karte einfach dranhalten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the rename dialog is editing. */
class LabelEdit(val title: String, val current: String?, val save: (String) -> Unit)

private fun LazyListScope.result(result: CardResult, labels: LabelStore, onEdit: (LabelEdit) -> Unit) {
    when (result) {
        is CardResult.Ticket -> {
            if (result.card.tickets.isEmpty()) item { Hint("Keine Fahrkarte auf dieser Karte gespeichert.") }
            items(result.card.tickets) { TicketCard(it, labels, onEdit) }
            item { PrivacyCard(result.card, labels) }
            item { CardInfo(result.card, labels, onEdit) }
            item {
                Details {
                    Mono("UID ${result.uid}")
                    result.card.tickets.forEach { t ->
                        Mono("Berechtigung Nr. ${t.entitlementNumber} · Status 0x%02X".format(t.status ?: 0))
                    }
                    Mono(result.card.rawDirectory)
                    result.card.tickets.forEach { Mono(it.raw) }
                    result.card.logRaw?.let { Mono("Logbuch:\n$it") }
                }
            }
        }
        is CardResult.Balance -> {
            item { BalanceCard(result) }
            item { Details { Mono("UID ${result.uid}"); result.dump?.let { DumpView(it) } } }
        }
        is CardResult.Desfire -> {
            item {
                InfoCard(
                    "Unbekannte DESFire-Karte",
                    if (result.dump.applications.isEmpty()) "Die Karte zeigt ohne Schlüssel nichts von sich."
                    else "Format unbekannt. In den Details siehst du, was frei lesbar ist.",
                )
            }
            item { Details { Mono("UID ${result.uid}"); DumpView(result.dump) } }
        }
        is CardResult.Other -> {
            item { InfoCard("Karte nicht unterstützt", result.notes.joinToString("\n")) }
            item {
                Details {
                    Mono("UID ${result.uid}\nTechnik: ${result.technologies.joinToString()}")
                    result.debug?.let { Mono(it) }
                }
            }
        }
        is CardResult.Failure -> item { InfoCard("Fehler", result.message) }
    }
}

// --- Tickets -----------------------------------------------------------------

@Composable
private fun TicketCard(ticket: VdvTicket, labels: LabelStore, onEdit: (LabelEdit) -> Unit) {
    val now = LocalDateTime.now()
    val validity = ticket.validityAt(now)
    val org = ticket.productOrg
    val nr = ticket.productNumber
    val customName = if (org != null && nr != null) labels.product(org, nr) else null

    val container = when (validity) {
        VdvTicket.Validity.VALID -> MaterialTheme.colorScheme.primaryContainer
        VdvTicket.Validity.EXPIRED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Editable(
                text = customName ?: ticket.productName,
                style = { Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                enabled = org != null && nr != null,
            ) {
                onEdit(LabelEdit("Produkt $nr benennen", customName) { labels.setProduct(org!!, nr!!, it) })
            }
            org?.let { OrgLine("Verbund", it, labels, onEdit) }
            Text(validityText(validity, ticket.validUntil, now), style = MaterialTheme.typography.titleMedium)
            ticket.validFrom?.let {
                Text("seit ${it.format(DATE)}", style = MaterialTheme.typography.bodySmall)
            }
            ticket.passenger?.let { PassengerLine(it) }
            ticket.areaOrgs.filter { it != org }.forEach { OrgLine("Geltungsbereich", it, labels, onEdit) }
        }
    }
}

@Composable
private fun CardInfo(card: VdvCard, labels: LabelStore, onEdit: (LabelEdit) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Chipkarte", style = MaterialTheme.typography.titleSmall)
            Text("gültig bis ${card.appValidUntil?.format(DATE) ?: "?"}")
            card.appOrg?.let { OrgLine("Herausgeber", it, labels, onEdit) }
        }
    }
}

@Composable
private fun OrgLine(prefix: String, org: Int, labels: LabelStore, onEdit: (LabelEdit) -> Unit) {
    val name = labels.org(org)
    Editable(
        text = "$prefix: ${name ?: "Nr. $org"}",
        style = { Text(it) },
    ) {
        onEdit(LabelEdit("Organisation $org benennen", name) { labels.setOrg(org, it) })
    }
}

private fun validityText(v: VdvTicket.Validity, until: LocalDateTime?, now: LocalDateTime): String = when (v) {
    VdvTicket.Validity.VALID -> {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), until!!.toLocalDate())
        "✅ gültig bis ${until.format(DATE)} · " + when (days) {
            0L -> "nur noch heute"
            1L -> "noch 1 Tag"
            else -> "noch ${NumberFormat.getIntegerInstance(Locale.GERMANY).format(days)} Tage"
        }
    }
    VdvTicket.Validity.EXPIRED -> "❌ abgelaufen am ${until?.format(DATE)}"
    VdvTicket.Validity.NOT_YET -> "⏳ noch nicht gültig"
    VdvTicket.Validity.UNKNOWN -> "❔ Gültigkeit unbekannt"
}

/** Masked by default, so screenshots don't leak your name. Tap to reveal. */
@Composable
private fun PassengerLine(p: Passenger) {
    var revealed by remember { mutableStateOf(false) }
    val name = if (revealed) p.name else p.maskedName
    val birth = p.birthDate?.let { if (revealed) it.format(DATE) else "••.••.${it.year}" }
    Text(
        "👤 $name" + (birth?.let { " · geb. $it" } ?: "") + if (revealed) "  🙈" else "  👁",
        modifier = Modifier.clickable { revealed = !revealed },
    )
}

// --- "Was weiß meine Karte über mich?" ---------------------------------------

@Composable
private fun PrivacyCard(card: VdvCard, labels: LabelStore) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🕵️ Was weiß deine Karte über dich?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Das hier kann jedes NFC-Handy ohne Schlüssel lesen:",
                style = MaterialTheme.typography.bodySmall,
            )
            card.appInstance?.let {
                Text("🪪 Kartennummer $it, eindeutig und mit deinem Abo verknüpft")
            }
            Text("🎫 ${card.tickets.size} Ticket(s) mit Produkt und Gültigkeit")
            if (card.log.isEmpty()) {
                Text("📓 Logbuch: nichts frei lesbar")
            } else {
                Text("📓 Logbuch: ${card.log.size} Einträge")
                card.log.forEach { e ->
                    val place = labels.org(e.placeOrg)?.let { "$it, " } ?: "Org ${e.placeOrg}, "
                    Text(
                        "${e.time?.format(DATE_TIME) ?: "?"} · ${e.kind}\n" +
                            "   Ort ${e.placeNumber} ($place Typ ${e.placeType}) · Gerät ${e.terminalType}/${e.terminalNumber}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val passenger = card.tickets.firstNotNullOfOrNull { it.passenger }
            if (passenger != null) {
                Text(
                    "👤 Name, Geburtsdatum und Geschlecht stehen IM TICKET, frei lesbar. " +
                        "Damit Kontrolleure ein personalisiertes Ticket mit dem Ausweis vergleichen können, ohne online zu sein.",
                )
            }
            if (card.hasCustomerData) {
                Text(
                    "🔒 Zusätzlich gibt es einen Kundendaten-Block. Der ist laut Open-Source-Doku PIN-geschützt " +
                        "und wird von dieser App nicht gelesen.",
                )
            }
            Text(
                "Orts- und Gerätenummern sind interne Nummern des Verkehrsunternehmens. " +
                    "Ohne deren Liste weiß man nur, DASS etwas passiert ist, nicht genau wo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Generic tag info & NDEF ----------------------------------------------------

@Composable
private fun TagInfoCard(tag: TagSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Chip", style = MaterialTheme.typography.titleSmall)
            Text(tag.chip, style = MaterialTheme.typography.titleMedium)
            tag.manufacturer?.let { Text("Hersteller: $it", style = MaterialTheme.typography.bodySmall) }
            tag.memoryBytes?.let { Text("Speicher: $it Byte", style = MaterialTheme.typography.bodySmall) }
            Text(
                when (tag.writeDecision) {
                    WritePolicy.Decision.FormatBlankSticker -> "✍️ Leerer Sticker, beschreibbar (Tab „Schreiben“)"
                    WritePolicy.Decision.WriteNdef -> "✍️ Beschreibbar (Tab „Schreiben“)"
                    is WritePolicy.Decision.Refuse -> "🔒 Wird von dieser App nicht beschrieben"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Mono(
                "UID ${tag.uid}" +
                    (tag.atqa?.let { "\nATQA $it · SAK ${tag.sak}" } ?: "") +
                    "\n${tag.technologies.joinToString()}",
            )
        }
    }
}

@Composable
private fun Iso15693Card(info: Iso15693Info) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📚 ISO-15693-Etikett", style = MaterialTheme.typography.titleMedium)
            info.afi?.let { afi ->
                Text("AFI 0x%02X".format(afi) + (Iso15693.afiMeaning(afi)?.let { " · $it" } ?: ""))
            }
            if (info.blockCount != null && info.blockSize != null) {
                Text(
                    "${info.blockCount} Blöcke à ${info.blockSize} Byte, ${info.blocks.size} davon frei lesbar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (info.blocks.isNotEmpty()) {
                Text("Als Text (Mediennummern sind oft lesbar):", style = MaterialTheme.typography.bodySmall)
                Mono(info.ascii)
                Mono(info.blocks.mapIndexed { i, b -> "%02d: %s".format(i, b.toHex()) }.joinToString("\n"))
            } else {
                Text("Speicher nicht frei lesbar.")
            }
        }
    }
}

@Composable
private fun NdefCard(ndef: NdefInfo) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Inhalt (NDEF)", style = MaterialTheme.typography.titleSmall)
            if (ndef.entries.isEmpty()) Text("Leer")
            ndef.entries.forEach { e ->
                Text(e.kind, fontWeight = FontWeight.Bold)
                SelectionContainer { Text(e.value) }
            }
            Text(
                "${ndef.usedBytes} von ${ndef.capacity} Byte belegt · " +
                    if (ndef.writable) "beschreibbar" else "schreibgeschützt 🔒",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// --- Balance -----------------------------------------------------------------

@Composable
private fun BalanceCard(result: CardResult.Balance) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Guthaben · ${result.system}", style = MaterialTheme.typography.titleMedium)
            Text(euro(result.balance), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            result.lastTransaction?.let { Text("Letzte Buchung: ${euro(it)}") }
        }
    }
}

// --- Generic pieces ----------------------------------------------------------

@Composable
private fun Editable(
    text: String,
    style: @Composable (String) -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(if (enabled) Modifier.clickable(onClick = onClick) else Modifier) {
        style(if (enabled) "$text ✎" else text)
    }
}

@Composable
fun LabelDialog(edit: LabelEdit, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(edit.current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(edit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name") })
                Text("Leer lassen, um den Namen zu entfernen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}

/** Collapsible "Technische Details" section — nerd stuff stays out of the way. */
@Composable
private fun Details(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }) {
            Text(if (open) "▾ Technische Details" else "▸ Technische Details")
        }
        if (open) content()
    }
}

@Composable
private fun DumpView(dump: DesfireDump) {
    dump.version?.let { Mono("Version: $it") }
    dump.applications.forEach { AppCard(it) }
}

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
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
internal fun Mono(text: String) = SelectionContainer {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun euro(value: BigDecimal): String =
    NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value.setScale(2, RoundingMode.DOWN))
