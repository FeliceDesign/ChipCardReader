# ChipCardReader

Private Android-App zum Auslesen von NFC-Chipkarten: Mensa-Guthaben, Gültigkeiten, Rohdaten.

**Grundregel:** Die App liest nur, was eine Karte *ohne Schlüssel* freiwillig hergibt.
Keine Authentifizierung, kein Schlüsselraten, kein Schreiben. (§ 202a StGB lässt grüßen.)

## Was geht (v0.1)

| Karte | Status |
|---|---|
| Mensa-Karten mit **Intercard**-System (DESFire, App `0x5F8415`) | ✅ Guthaben + letzte Buchung |
| Jede andere **MIFARE DESFire** (z. B. Deutschlandticket-Chipkarte) | 🔍 Explorer: listet Apps/Dateien, zeigt frei lesbare Bytes |
| MIFARE Classic / Ultralight | ℹ️ nur Erkennung + UID |
| Ausweis, Bankkarte (ISO 7816) | ⏳ noch nicht |

## Architektur

```
MainActivity          NFC Reader Mode, reicht Tags an das ViewModel weiter
ui/ReaderViewModel    Android-Tag -> IsoDep -> CardDispatcher, hält den UI-State
ui/CardScreen         Compose-UI
cards/CardDispatcher  "Was ist das für eine Karte?" (reines Kotlin, testbar)
cards/IntercardReader Mensa-Format
cards/DesfireExplorer Dump von allem frei Lesbaren
nfc/DesfireClient     DESFire-Befehle (APDUs bauen, 0xAF-Folgeframes, Statuscodes)
nfc/FileSettings      Dateieinstellungen + Zugriffsrechte parsen
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
- VDV-KA (Deutschlandticket): Spezifikation bei [eTicket Deutschland](https://www.eticket-deutschland.de/)
