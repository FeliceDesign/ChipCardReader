package de.felice.chipcardreader.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesfireClientTest {

    @Test
    fun `wraps commands without data`() {
        assertEquals("906A000000", DesfireClient.wrap(0x6A).toHex(""))
    }

    @Test
    fun `wraps commands with data`() {
        assertEquals("905A0000035F841500", DesfireClient.wrap(0x5A, "5F8415".hexToBytes()).toHex(""))
    }

    @Test
    fun `parses application ids`() {
        val card = FakeDesfireCard(mapOf("906A000000" to listOf("5F8415 000001 9100")))
        assertEquals(listOf(0x5F8415, 0x000001), DesfireClient(card).getApplicationIds())
    }

    @Test
    fun `follows additional frames`() {
        val card = FakeDesfireCard(
            mapOf(
                "9060000000" to listOf("0401010100180591AF"),
                "90AF000000" to listOf("0401010103180591AF", "04B2C3D4E5F6809100"),
            ),
        )
        val version = DesfireClient(card).getVersion()
        assertEquals(7 + 7 + 7, version.size)
        assertEquals(listOf("9060000000", "90AF000000", "90AF000000"), card.sent)
    }

    @Test(expected = DesfireException::class)
    fun `permission denied throws`() {
        val card = FakeDesfireCard(mapOf("906C00000101 00".replace(" ", "") to listOf("919D")))
        DesfireClient(card).getValue(1)
    }

    @Test
    fun `value is signed little endian`() {
        val card = FakeDesfireCard(mapOf("906C0000010100" to listOf("FEFFFFFF9100")))
        assertEquals(-2, DesfireClient(card).getValue(1))
    }

    @Test
    fun `parses value file settings`() {
        // type=02, comm=00, rights=0x10E0 -> bytes E0 10 (read=1, write=0, rw=E, change=0)
        val s = FileSettings.parse(
            "02 00 E010 00000000 A0860100 B80B0000 01".hexToBytes(),
        ) as FileSettings.Value
        assertEquals(100_000, s.upperLimit)
        assertEquals(3000, s.limitedCreditValue)
        assertTrue(s.limitedCreditEnabled)
        assertEquals(1, s.accessRights.read)
        assertEquals(0xE, s.accessRights.readWrite)
        assertTrue(s.accessRights.freelyReadable)
    }

    @Test
    fun `protected file is not freely readable`() {
        val s = FileSettings.parse("00 03 1100 200000".hexToBytes()) as FileSettings.Data
        assertEquals(32, s.size)
        assertFalse(s.accessRights.freelyReadable)
    }

    @Test
    fun `hex helper roundtrip`() {
        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte()), "0AFF".hexToBytes())
    }
}
