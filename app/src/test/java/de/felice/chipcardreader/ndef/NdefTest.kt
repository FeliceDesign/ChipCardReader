package de.felice.chipcardreader.ndef

import de.felice.chipcardreader.nfc.ChipIdentifier
import de.felice.chipcardreader.nfc.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefTest {

    @Test
    fun `decodes compressed uri prefix`() {
        // 0x04 = "https://"
        val payload = byteArrayOf(0x04) + "felice.design".toByteArray()
        assertEquals("https://felice.design", NdefParser.decodeUri(payload))
        val entry = NdefParser.parse(NdefParser.TNF_WELL_KNOWN, "U".toByteArray(), payload)
        assertEquals("🔗 Link", entry.kind)
    }

    @Test
    fun `decodes utf8 text record with language code`() {
        val payload = byteArrayOf(0x02) + "de".toByteArray() + "Hallo Felix 👋".toByteArray()
        assertEquals("Hallo Felix 👋", NdefParser.decodeText(payload))
    }

    @Test
    fun `summarizes vcard`() {
        val vcard = VCard.build(WriteRequest.Contact("Felix, M.", phone = "+49 123", website = "felice.design"))
        assertTrue(vcard.contains("FN:Felix\\, M.\r\n"))
        assertTrue(vcard.contains("URL:https://felice.design"))
        val summary = NdefParser.summarizeVCard(vcard)
        assertEquals("Felix, M.\n☎ +49 123\n🌐 https://felice.design", summary)
    }

    @Test
    fun `finds wifi ssid inside credential`() {
        // 100E (credential) { 1045 SSID "Home", 1027 key "pw" }
        val inner = "1045 0004".hexToBytes() + "Home".toByteArray() + "1027 0002".hexToBytes() + "pw".toByteArray()
        val payload = "100E".hexToBytes() + byteArrayOf(0, inner.size.toByte()) + inner
        assertEquals("Netzwerk: Home\n(mit Passwort)", NdefParser.summarizeWifi(payload))
    }

    @Test
    fun `normalizes urls`() {
        assertEquals("https://example.com", WriteRequest.normalizeUrl(" example.com "))
        assertEquals("mailto:a@b.de", WriteRequest.normalizeUrl("mailto:a@b.de"))
        assertEquals("Bitte einen Link eingeben.", WriteRequest.Link(" ").validate())
        assertNull(WriteRequest.Contact("X").validate())
    }

    @Test
    fun `identifies ntag models`() {
        assertEquals("NTAG215", ChipIdentifier.fromUltralightVersion("0004040201001103".hexToBytes()))
        assertEquals("NTAG213", ChipIdentifier.fromUltralightVersion("0004040201000F03".hexToBytes()))
        assertEquals("MIFARE Classic 1K", ChipIdentifier.classicName(1024))
        assertEquals("NXP", ChipIdentifier.manufacturer("04998A4719904F".hexToBytes()))
    }
}
