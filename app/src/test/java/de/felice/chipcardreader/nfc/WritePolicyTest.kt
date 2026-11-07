package de.felice.chipcardreader.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePolicyTest {

    private fun facts(
        smartcard: Boolean = false, v: Boolean = false, ultralight: Boolean = false,
        ndef: Boolean = false, writable: Boolean = false, formatable: Boolean = false,
    ) = WritePolicy.TagFacts(smartcard, v, ultralight, ndef, writable, formatable)

    @Test
    fun `refuses smartcards even if Android calls them formatable`() {
        // This is exactly the D-Ticket / Mensa case that showed "leer und beschreibbar".
        val d = WritePolicy.decide(facts(smartcard = true, formatable = true))
        assertTrue(d is WritePolicy.Decision.Refuse)
    }

    @Test
    fun `refuses mifare classic cards that are only formatable`() {
        assertTrue(WritePolicy.decide(facts(formatable = true)) is WritePolicy.Decision.Refuse)
    }

    @Test
    fun `refuses library labels`() {
        assertTrue(WritePolicy.decide(facts(v = true, ndef = true, writable = true)) is WritePolicy.Decision.Refuse)
    }

    @Test
    fun `formats blank ntag sticker`() {
        assertEquals(WritePolicy.Decision.FormatBlankSticker, WritePolicy.decide(facts(ultralight = true, formatable = true)))
    }

    @Test
    fun `writes ndef sticker but not a locked one`() {
        assertEquals(WritePolicy.Decision.WriteNdef, WritePolicy.decide(facts(ultralight = true, ndef = true, writable = true)))
        assertTrue(WritePolicy.decide(facts(ultralight = true, ndef = true, writable = false)) is WritePolicy.Decision.Refuse)
    }
}

class Iso15693Test {

    @Test
    fun `parses system info with all fields`() {
        // flags 00, info 0F, UID (8), DSFID 00, AFI 07, 27 blocks-1 = 0x1B, blocksize-1 = 03, IC 01
        val r = "00 0F 0102030405060708 00 07 1B 03 01".hexToBytes()
        val info = Iso15693.parseSystemInfo(r)!!
        assertEquals(0x07, info.afi)
        assertEquals(28, info.blockCount)
        assertEquals(4, info.blockSize)
        assertEquals("Bibliothek: im Bestand / gesichert (Gate würde piepen)", Iso15693.afiMeaning(0x07))
    }

    @Test
    fun `error response is null`() {
        assertEquals(null, Iso15693.parseSystemInfo("010F".hexToBytes()))
    }

    @Test
    fun `builds addressed frames with uid`() {
        assertEquals("022B", Iso15693.getSystemInfo().toHex(""))
        assertEquals("2220" + "0102030405060708" + "05", Iso15693.readBlock(5, "0102030405060708".hexToBytes()).toHex(""))
    }

    @Test
    fun `ascii view shows media number`() {
        val info = Iso15693Info(null, null, null, null, null, listOf("11 01 33 34".hexToBytes(), "35 36 00 00".hexToBytes()))
        assertEquals("··3456··", info.ascii)
    }
}
