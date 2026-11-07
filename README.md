# ChipCardReader

Private Android-App zum Auslesen von NFC-Chipkarten: Mensa-Guthaben, Gültigkeiten, Rohdaten.

**Grundregel:** Die App liest nur, was eine Karte *ohne Schlüssel* freiwillig hergibt.
Keine Authentifizierung, kein Schlüsselraten, kein Schreiben. (§ 202a StGB lässt grüßen.)

## Was geht (v0.2)

| Karte | Status |
|---|---|
| **eTicket-Chipkarten (VDV-KA)**, z. B. Deutschlandticket | ✅ Tickets mit Gültigkeit (Beginn/Ende), Produkt, Karten-Gültigkeit |
| Mensa-Karten mit **Intercard**-System (DESFire, App `0x5F8415`) | ✅ Guthaben + letzte Buchung |
| Jede andere **MIFARE DESFire** | 🔍 Explorer: listet Apps/Dateien, zeigt frei lesbare Bytes |
| MIFARE Classic / Ultralight | ℹ️ nur Erkennung + UID |
| Ausweis, Bankkarte (ISO 7816) | ⏳ noch nicht |

## Architektur

```
MainActivity          NFC Reader Mode, reicht Tags an das ViewModel weiter
ui/ReaderViewModel    Android-Tag -> IsoDep -> CardDispatcher, hält den UI-State
ui/CardScreen         Compose-UI
cards/CardDispatcher  "Was ist das für eine Karte?" (reines Kotlin, testbar)
cards/vdv/            VDV-KA: SELECT, TLV-Verzeichnis, GET DATA, Datumsformat
cards/IntercardReader Mensa-Format
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
- Kundendaten (Name, Geburtsdatum) auf VDV-Karten sind PIN-geschützt und werden bewusst nicht gelesen.
