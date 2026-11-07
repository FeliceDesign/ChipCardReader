package de.felice.chipcardreader.nfc

/**
 * BER-TLV = Tag, Length, Value. The "folder structure" of smart card data:
 *
 *     E8 0A  83 04 11 22 33 44  84 02 01 00
 *     ^tag ^len  ^child 1          ^child 2
 *
 * If bit 0x20 of the tag is set, the value is "constructed", i.e. contains
 * more TLVs (a folder). Otherwise it's raw bytes (a file).
 */
data class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>?) {

    val isConstructed get() = children != null

    /** Depth-first search through this TLV and everything inside it. */
    fun findAll(tag: Int): List<Tlv> = buildList {
        if (this@Tlv.tag == tag) add(this@Tlv)
        children?.forEach { addAll(it.findAll(tag)) }
    }

    fun child(tag: Int): Tlv? = children?.firstOrNull { it.tag == tag }

    fun childrenWith(tag: Int): List<Tlv> = children?.filter { it.tag == tag }.orEmpty()

    /** Indented human-readable dump, for the "Rohdaten" view. */
    fun pretty(indent: Int = 0, names: Map<Int, String> = emptyMap()): String = buildString {
        val pad = "  ".repeat(indent)
        val name = names[tag]?.let { " $it" } ?: ""
        if (children != null) {
            append("$pad%02X$name\n".format(tag))
            children.forEach { append(it.pretty(indent + 1, names)) }
        } else {
            append("$pad%02X$name: ${value.toHex()}\n".format(tag))
        }
    }

    // ByteArray has identity equals; make data class comparisons sane.
    override fun equals(other: Any?) =
        other is Tlv && tag == other.tag && value.contentEquals(other.value) && children == other.children

    override fun hashCode() = 31 * tag + value.contentHashCode()
}

object BerTlv {

    /**
     * Tags that have the "constructed" bit but are NOT folders in VDV-KA
     * (quirk documented by github.com/czietz/parse-vdv-chipcard).
     */
    private val PRIMITIVE_DESPITE_BIT = setOf(0xED)

    fun parse(data: ByteArray): List<Tlv> = parseRange(data, 0, data.size)
        ?: throw IllegalArgumentException("Kein gültiges TLV: ${data.toHex()}")

    /** Returns null if the bytes don't form valid TLVs (so callers can fall back). */
    private fun parseRange(data: ByteArray, from: Int, to: Int): List<Tlv>? {
        val result = mutableListOf<Tlv>()
        var i = from
        while (i < to) {
            // Padding bytes (00 / FF) between TLVs are allowed by BER.
            if (data[i] == 0x00.toByte() || data[i] == 0xFF.toByte()) { i++; continue }

            // Tag: 1 byte, or more if the low 5 bits are all set.
            val firstTagByte = data[i].u()
            var tag = data[i++].u()
            if (tag and 0x1F == 0x1F) {
                do {
                    if (i >= to) return null
                    val b = data[i++].u()
                    tag = (tag shl 8) or b
                } while (b and 0x80 != 0)
            }

            // Length: 1 byte, or 0x81/0x82 + 1/2 length bytes.
            if (i >= to) return null
            var length = data[i++].u()
            if (length and 0x80 != 0) {
                val n = length and 0x7F
                if (n == 0 || n > 3 || i + n > to) return null
                length = 0
                repeat(n) { length = (length shl 8) or data[i++].u() }
            }
            if (i + length > to) return null

            val value = data.copyOfRange(i, i + length)
            val looksConstructed = firstTagByte and 0x20 != 0 && tag !in PRIMITIVE_DESPITE_BIT
            val children = if (looksConstructed) parseRange(data, i, i + length) else null
            result += Tlv(tag, value, children)
            i += length
        }
        return result
    }
}
