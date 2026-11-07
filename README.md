# ChipCardReader

Privates Android-NFC-Tool: Fahrkarten und Mensa-Guthaben lesen, beliebige Tags analysieren, NFC-Sticker beschreiben.

**Internet:** Die App geht nur online, um Buchtitel im öffentlichen Katalog einer Bücherei nachzuschlagen. Gesendet wird dabei nur die Mediennummer.

**Grundregel:** Die App liest nur, was eine Karte *ohne Schlüssel* freiwillig hergibt.
Keine Authentifizierung, kein Schlüsselraten. Geschrieben wird nur auf NFC-Sticker, nie auf Chipkarten, und nie wird ein Tag dauerhaft gesperrt. (§ 202a StGB lässt grüßen.)

## Was geht (v0.7)

| Karte | Status |
|---|---|
| **eTicket-Chipkarten (VDV-KA)**, z. B. Deutschlandticket | ✅ Tickets mit Gültigkeit + Resttagen, Verbund-/Produktnamen selbst vergeben (getestet mit 3 echten Karten) |
| Mensa-Karten mit **Intercard**-System (DESFire, App `0x5F8415`) | ✅ Guthaben + letzte Buchung (mit echter Karte getestet) |
| Mensa-Karten mit **MagnaCarta**-System (DESFire, App `0xF080F3`) | 🧪 Guthaben, nach MensaGuthaben-Doku, noch ohne echte Karte getestet |
| Jede andere **MIFARE DESFire** | 🔍 Explorer: listet Apps/Dateien, zeigt frei lesbare Bytes |
| **Jeder Tag** | ✅ Chipmodell (NTAG213/215/216, Ultralight, Classic 1K/4K …), Hersteller, Speicher, UID, ATQA/SAK |
| **NDEF-Inhalt** (Sticker) | ✅ Links, Text, Kontakte (vCard), WLAN-Name, App-Links anzeigen |
| **Bibliotheks-RFID (ISO 28560-3, „Dänisches Modell“)** | ✅ Medien- bzw. Ausweisnummer, Bibliothek, ausgeliehen/gesichert, Prüfsumme. Mit echten Tags der Stadtbücherei Ravensburg getestet |
| **Buchtitel aus dem Katalog** (Koha-Büchereien) | 🧪 Sucht die Mediennummer per RSS im Online-Katalog. Ravensburg ist vorbelegt, andere Adressen lassen sich eintragen. Fallback: „Im Katalog öffnen“. Noch nicht gegen den echten Server getestet |
| Andere **ISO-15693**-Tags | 🔍 AFI, Speicherblöcke als Hex + Text. Nur lesen, nie schreiben |
| **NDEF schreiben** | ✅ Link, Text, Kontakt auf Sticker schreiben, mit Größenanzeige |
| VDV-Karte: **„Was weiß meine Karte über mich?“** | 🧪 Logbuch (Zeit, Ort-Nr., Gerät), Hinweis auf PIN-geschützte Kundendaten. Logbuch-Format aus czietz, noch nicht mit echter Karte bestätigt |
| **Auto-Öffnen** | ✅ App startet beim Antippen einer Karte (Link-Sticker öffnen weiterhin den Browser) |
| Ausweis, Bankkarte (ISO 7816) | ⏳ noch nicht |

## Architektur

```
MainActivity          NFC Reader Mode, reicht Tags an das ViewModel weiter
ui/ReaderViewModel    Android-Tag -> IsoDep -> CardDispatcher, hält den UI-State
ui/MainScreen         Tabs „Lesen“ / „Schreiben“, Schreibformular
ui/CardScreen         Lesen-Ansicht (Tickets, Guthaben, Datenschutz, Chip-Info, NDEF)
ndef/Ndef             NDEF-Records lesen, vCard bauen (reines Kotlin)
nfc/TagInspector      Chiptyp, Speicher, NDEF eines beliebigen Tags (Android)
nfc/NdefWriter        Sticker beschreiben
nfc/WritePolicy       DIE eine Regel, ob geschrieben werden darf (UI + Writer nutzen sie)
nfc/Iso15693          NFC-V-Befehle und -Parser
library/Iso28560      Bibliotheks-Datenmodell (Nummer, Bibliothek, CRC-Prüfung)
library/KohaCatalog   Katalog-URL + RSS-Parser (reines Kotlin); CatalogClient holt die Daten
nfc/ChipIdentifier    GET_VERSION/Größe → Modellname
cards/CardDispatcher  "Was ist das für eine Karte?" (reines Kotlin, testbar)
cards/vdv/            VDV-KA: SELECT, TLV-Verzeichnis, GET DATA, Datumsformat
cards/IntercardReader Guthaben-Systeme (BalanceReader: Intercard, MagnaCarta)
data/LabelStore       Selbst vergebene Namen für Verbund-/Produktnummern
cards/DesfireExplorer Dump von allem frei Lesbaren
nfc/DesfireClient     DESFire-Befehle (APDUs bauen, 0xAF-Folgeframes, Statuscodes)
nfc/FileSettings      Dateieinstellungen + Zugriffsrechte parsen
nfc/Iso7816           ISO-7816-APDUs (SELECT, GET DATA, Statuswörter)
nfc/BerTlv            BER-TLV-Parser (Tag-Length-Value-Bäume)
```

Alles unter `cards/` und `nfc/` kennt keine Android-Klassen, nur das `Transceiver`-Interface.
Deshalb laufen die Tests mit einer Fake-Karte auf dem PC.

## Bauen

```bash
./gradlew testDebugUnitTest   # Tests
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

Braucht ein Android SDK (Platform 35), z. B. über Android Studio.

## Quellen für Kartenformate

- Intercard-Layout: [MensaGuthaben](https://github.com/JakobWenzel/MensaGuthaben), [Metrodroid](https://github.com/metrodroid/metrodroid) (Format übernommen, Code neu geschrieben)
- VDV-KA (Deutschlandticket): Befehle, Tags und Feldlayout aus [czietz/parse-vdv-chipcard](https://github.com/czietz/parse-vdv-chipcard) (MIT), AID bestätigt durch [zuegli](https://github.com/TheEnbyperor/zuegli), Produktnummern 9995–9999 aus dessen Produkttabellen. Die offizielle Spezifikation ist nicht öffentlich.
- Warum keine eingebaute Verbund-Liste? Die VDV-Organisationsnummern sind nicht öffentlich. Ein falscher Name wäre schlimmer als eine ehrliche Nummer, deshalb vergibst du Namen selbst (Tippen auf ✎).
- Personalisierte Tickets (z. B. D-Ticket) enthalten Name, Geburtsdatum und Geschlecht frei lesbar im Ticket selbst (TLV-EFS, Tag 0xDB), damit Kontrolleure offline prüfen können. Die App zeigt den Namen standardmäßig maskiert. Der separate Kundendaten-Block ist laut czietz PIN-geschützt und wird nicht gelesen.
