package de.felice.chipcardreader.cards.vdv

import de.felice.chipcardreader.cards.CardDispatcher
import de.felice.chipcardreader.cards.CardResult
import de.felice.chipcardreader.nfc.BerTlv
import de.felice.chipcardreader.nfc.FakeDesfireCard
import de.felice.chipcardreader.nfc.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VdvKaReaderTest {

    @Test
    fun `decodes DateTimeCompact`() {
        val dt = LocalDateTime.of(2026, 11, 7, 18, 46, 30)
        assertEquals(dt, dateTimeCompact(encode(dt)))
    }

    @Test
    fun `invalid DateTimeCompact is null`() {
        assertNull(dateTimeCompact(0)) // month 0 doesn't exist
    }

    @Test
    fun `reads a deutschlandticket`() {
        val result = CardDispatcher.readIsoDep("04:99", ticketCard())
        assertTrue("got $result", result is CardResult.Ticket)
        val card = (result as CardResult.Ticket).card
        assertEquals(6072, card.appOrg)
        assertEquals(LocalDateTime.of(2024, 1, 1, 0, 0), card.appValidFrom)

        val ticket = card.tickets.single()
        assertTrue(ticket.isDeutschlandticket)
        assertEquals("Deutschlandticket", ticket.productName)
        assertEquals(123456L, ticket.entitlementNumber)
        assertEquals(LocalDateTime.of(2026, 11, 30, 23, 59, 58), ticket.validUntil)
        assertEquals(0x01, ticket.status)
        assertEquals("FELIX MUSTERMANN", ticket.passenger!!.name)
        assertEquals("F•••• M•••••••••", ticket.passenger!!.maskedName)
        assertEquals(java.time.LocalDate.of(1999, 2, 1), ticket.passenger!!.birthDate)
        assertEquals(listOf(6291), ticket.areaOrgs)
        assertEquals(VdvTicket.Validity.VALID, ticket.validityAt(LocalDateTime.of(2026, 11, 7, 12, 0)))
        assertEquals(VdvTicket.Validity.EXPIRED, ticket.validityAt(LocalDateTime.of(2026, 12, 1, 0, 0)))
    }

    @Test
    fun `card without VDV-KA falls through to DESFire`() {
        val card = FakeDesfireCard(mapOf("906A000000" to listOf("9100")))
        val result = CardDispatcher.readIsoDep("AA", card)
        assertTrue("got $result", result is CardResult.Desfire)
    }

    @Test
    fun `tlv parser handles nesting and long lengths`() {
        val long = ByteArray(200) { 7 }
        val data = tlv(0xE8, tlv(0x83, byteArrayOf(1, 2)) + tlv(0xC7, long))
        val root = BerTlv.parse(data).single()
        assertEquals(2, root.children!!.size)
        assertEquals(200, root.child(0xC7)!!.value.size)
        assertEquals("0102", root.findAll(0x83).single().value.toHex(""))
    }

    @Test
    fun `reads the logbook`() {
        val result = CardDispatcher.readIsoDep("04:99", ticketCard(withLog = true)) as CardResult.Ticket
        val entry = result.card.log.single()
        assertEquals("Fahrt / Kontrolle", entry.kind)
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 15, 0), entry.time)
        assertEquals(4711, entry.placeNumber)
        assertEquals(6291, entry.placeOrg)
        assertTrue(result.card.hasCustomerData)
    }

    @Test
    fun `retries select with P2=00 after 6A86`() {
        val io = de.felice.chipcardreader.nfc.FakeDesfireCard(
            mapOf(
                "00A4040C0CD2760001354B414E4D30310000" to listOf("6A86"),
                "00A404000CD2760001354B414E4D30310000" to listOf("6A82"),
            ),
        )
        val reader = VdvKaReader(de.felice.chipcardreader.nfc.Iso7816(io))
        assertNull(reader.read())
        assertEquals("6A86, mit P2=00 → 6A82", reader.selectStatus)
    }

    // --- helpers -----------------------------------------------------------

    private fun ticketCard(withLog: Boolean = false): FakeDesfireCard {
        val appStatic = int(1, 4) + int(6072, 2) + int(0x12, 1) +
            int(encode(LocalDateTime.of(2024, 1, 1, 0, 0)), 4) +
            int(encode(LocalDateTime.of(2030, 12, 31, 0, 0)), 4)
        val directory = tlv(
            0xE0,
            tlv(0xE2, tlv(0x81, appStatic) + tlv(0xC0, byteArrayOf(1))) +
                (if (withLog) tlv(0xE4, tlv(0xC0, byteArrayOf(2))) + tlv(0xE7, tlv(0xC0, byteArrayOf(3))) else ByteArray(0)) +
                tlv(0xE9, tlv(0xC0, byteArrayOf(1))),
        )
        val ticketStatic = int(123456, 4) + int(6072, 2) + int(9999, 2) + int(6072, 2) + int(6072, 2) +
            int(encode(LocalDateTime.of(2026, 11, 1, 0, 0)), 4) +
            int(encode(LocalDateTime.of(2026, 11, 30, 23, 59, 58)), 4)
        // TLV-EFS: DB = passenger (gender, BCD birthday, name), DC = area list
        val efs = tlv(0xDB, byteArrayOf(1, 0x19, 0x99.toByte(), 0x02, 0x01) + "FELIX#MUSTERMANN".toByteArray(Charsets.ISO_8859_1)) +
            tlv(0xDC, byteArrayOf(0x01) + int(6291, 2))
        val ticket = tlv(
            0xE8,
            tlv(0xEA, tlv(0x83, ticketStatic) + tlv(0x84, byteArrayOf(1, 0)) + tlv(0x85, efs)) +
                tlv(0xC7, "Deutschlandticket".toByteArray()),
        )
        // 27-byte "Allgemeine Transaktionsdaten" inside a trip transaction (F1)
        val logData = int(1, 2) + int(99, 4) + int(5, 3) + int(6291, 2) + int(7, 1) + int(321, 2) +
            int(6291, 2) + int(encode(LocalDateTime.of(2026, 11, 3, 8, 15, 0)), 4) + int(1, 1) +
            int(4711, 3) + int(6291, 2) + int(0x05, 1)
        val log = tlv(0xE3, tlv(0xF1, tlv(0x89, logData)))
        return FakeDesfireCard(
            mapOf(
                "10CA01F002E30200" to listOf(log.toHex("") + "9000"),
                "00CA01F002E30200" to listOf("9000"),
                "00A4040C0CD2760001354B414E4D30310000" to listOf(directory.toHex("") + "9000"),
                "10CA01F002E80100" to listOf(ticket.toHex("") + "9000"),
                "00CA01F002E80100" to listOf("9000"),
            ),
        )
    }

    private fun encode(dt: LocalDateTime): Long =
        ((dt.year - 1990).toLong() shl 25) or (dt.monthValue.toLong() shl 21) or
            (dt.dayOfMonth.toLong() shl 16) or (dt.hour.toLong() shl 11) or
            (dt.minute.toLong() shl 5) or (dt.second / 2).toLong()

    private fun int(v: Long, n: Int) = ByteArray(n) { i -> (v shr (8 * (n - 1 - i))).toByte() }
    private fun int(v: Int, n: Int) = int(v.toLong(), n)

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val len = if (value.size < 0x80) byteArrayOf(value.size.toByte())
        else byteArrayOf(0x81.toByte(), value.size.toByte())
        return byteArrayOf(tag.toByte()) + len + value
    }
}
