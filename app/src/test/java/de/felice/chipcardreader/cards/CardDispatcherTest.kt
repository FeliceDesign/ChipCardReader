package de.felice.chipcardreader.cards

import de.felice.chipcardreader.nfc.FakeDesfireCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CardDispatcherTest {

    private val mensaCard = FakeDesfireCard(
        mapOf(
            "9060000000" to listOf("04010101001805 9100"),
            "906A000000" to listOf("5F8415 9100"),
            "905A0000035F841500" to listOf("9100"),
            "906F000000" to listOf("01 9100"),
            // value file, free read (RW=E), limited credit = 3000 (= 3,00 €)
            "90F5000001 0100".replace(" ", "") to listOf("02 00 E010 00000000 A0860100 B80B0000 01 9100"),
            // 12340 tenth-cents = 12,34 €
            "906C0000010100" to listOf("34300000 9100"),
        ).mapValues { (_, v) -> v.map { it.replace(" ", "") } },
    )

    @Test
    fun `reads intercard balance`() {
        val result = CardDispatcher.readIsoDep("AA:BB", mensaCard)
        assertTrue("got $result", result is CardResult.Balance)
        result as CardResult.Balance
        assertEquals(0, BigDecimal("12.34").compareTo(result.balance))
        assertEquals(0, BigDecimal("3.00").compareTo(result.lastTransaction))
        assertEquals("Wert: 12340", result.dump!!.applications.single().files.single().content)
    }

    @Test
    fun `non desfire card is reported as other`() {
        val card = FakeDesfireCard(mapOf("906A000000" to listOf("6E00")))
        val result = CardDispatcher.readIsoDep("AA", card)
        assertTrue(result is CardResult.Other)
    }

    @Test
    fun `tenth cents conversion`() {
        assertEquals(0, BigDecimal("0.5").compareTo(IntercardReader.tenthCentsToEuro(500)))
    }
}

class MagnaCartaTest {
    @Test
    fun `reads magnacarta balance from file 2 bytes 6-7`() {
        val card = FakeDesfireCard(
            mapOf(
                "906A000000" to listOf("F080F39100"),
                "905A000003F080F300" to listOf("9100"),
                // bytes 6..7 = 0x04D2 = 1234 cents
                "90BD0000070200000000000000" to listOf("00000000000004D29100"),
            ),
        )
        val result = CardDispatcher.readIsoDep("AA", card) as CardResult.Balance
        assertEquals("MagnaCarta", result.system)
        assertEquals(0, BigDecimal("12.34").compareTo(result.balance))
    }
}
