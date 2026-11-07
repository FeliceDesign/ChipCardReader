package de.felice.chipcardreader.library

import de.felice.chipcardreader.nfc.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iso28560Test {

    /** Real dump of a borrowed book from Stadtbücherei Ravensburg (no personal data). */
    private val book = ("11010132 35303230 30313300 00000000 000000A0 AE444541 42323639 00000000").hexToBytes()

    @Test
    fun `decodes a real library book`() {
        val tag = Iso28560.decode(book)!!
        assertTrue(tag.crcValid)
        assertEquals("Ausleihbares Medium", tag.usageName)
        assertEquals("25020013", tag.itemId)
        assertEquals("DE-AB269", tag.libraryId)
        assertEquals(1, tag.partsInSet)
    }

    @Test
    fun `decodes a patron card`() {
        // Synthetic card (a real card number is personal data, so it stays out of the repo).
        val card = book.copyOf().apply {
            this[0] = 0x81.toByte()
            "12345678901".toByteArray().copyInto(this, 3)
        }
        val crc = Iso28560.crc(card)
        card[19] = crc.toByte(); card[20] = (crc shr 8).toByte()
        val tag = Iso28560.decode(card)!!
        assertTrue(tag.isPatronCard)
        assertTrue(tag.crcValid)
        assertEquals("12345678901", tag.itemId)
    }

    @Test
    fun `detects a broken checksum`() {
        val broken = book.copyOf().apply { this[5] = '9'.code.toByte() }
        assertFalse(Iso28560.decode(broken)!!.crcValid)
    }

    @Test
    fun `ignores unrelated data`() {
        assertNull(Iso28560.decode(ByteArray(32)))
        assertNull(Iso28560.decode(ByteArray(10)))
    }
}
