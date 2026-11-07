package de.felice.chipcardreader.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import de.felice.chipcardreader.ndef.NdefEntry
import de.felice.chipcardreader.ndef.NdefParser

/** Everything generic about a tag, whatever is on it. */
data class TagSummary(
    val uid: String,
    val chip: String,
    val manufacturer: String?,
    val technologies: List<String>,
    val atqa: String?,
    val sak: String?,
    val memoryBytes: Int?,
    val ndef: NdefInfo?,
    /** Same rule the writer uses, so "beschreibbar" is never a lie. */
    val writeDecision: WritePolicy.Decision,
    val iso15693: Iso15693Info?,
)

data class NdefInfo(
    val type: String,
    val capacity: Int,
    val usedBytes: Int,
    val writable: Boolean,
    val entries: List<NdefEntry>,
)

/**
 * Reads the "outside" of any tag: what chip, how much memory, NDEF content.
 * Each tech is connected and closed one after another (only one at a time
 * may be connected).
 */
object TagInspector {

    fun inspect(tag: Tag): TagSummary {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val nfcA = NfcA.get(tag)
        val ndef = readNdef(tag)

        var memory: Int? = null
        val chip = when {
            IsoDep.get(tag) != null -> "Smartcard (ISO 14443-4)"
            MifareClassic.get(tag) != null -> MifareClassic.get(tag).let {
                memory = it.size
                ChipIdentifier.classicName(it.size)
            }
            MifareUltralight.get(tag) != null -> identifyUltralight(MifareUltralight.get(tag)).also {
                memory = ChipIdentifier.userMemory(it)
            }
            NfcV.get(tag) != null -> "ISO 15693 (NFC-V)"
            NfcF.get(tag) != null -> "FeliCa (NFC-F)"
            nfcA != null -> "NFC-A Tag"
            else -> "Unbekannter Tag"
        }

        return TagSummary(
            uid = tag.id.toHex(":"),
            chip = chip,
            manufacturer = ChipIdentifier.manufacturer(tag.id),
            technologies = techs,
            atqa = nfcA?.atqa?.toHex(""),
            sak = nfcA?.let { "%02X".format(it.sak) },
            memoryBytes = memory,
            ndef = ndef,
            writeDecision = WritePolicy.decide(facts(tag)),
            iso15693 = NfcV.get(tag)?.let(::readIso15693),
        )
    }

    /** Facts for WritePolicy, straight from what Android saw at discovery (no I/O). */
    fun facts(tag: Tag): WritePolicy.TagFacts {
        val ndef = Ndef.get(tag)
        return WritePolicy.TagFacts(
            isSmartcard = IsoDep.get(tag) != null,
            isIso15693 = NfcV.get(tag) != null,
            isUltralight = MifareUltralight.get(tag) != null,
            hasNdef = ndef != null,
            ndefWritable = ndef?.isWritable == true,
            formatable = NdefFormatable.get(tag) != null,
        )
    }

    /** System info + up to 128 blocks. Read-only: we never write ISO 15693 tags. */
    private fun readIso15693(v: NfcV): Iso15693Info? = try {
        v.connect()
        val uid = v.tag.id
        val infoResponse = v.transceive(Iso15693.getSystemInfo()).takeUnless(Iso15693::isError)
            ?: v.transceive(Iso15693.getSystemInfo(uid))
        val info = Iso15693.parseSystemInfo(infoResponse)
            ?: Iso15693Info(null, null, null, null, null, emptyList())
        val blocks = mutableListOf<ByteArray>()
        for (n in 0 until minOf(info.blockCount ?: 128, 128)) {
            val r = runCatching { v.transceive(Iso15693.readBlock(n)) }.getOrNull()
            if (r == null || Iso15693.isError(r)) break // locked/protected or end of memory
            blocks += r.copyOfRange(1, r.size)
        }
        info.copy(blocks = blocks)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { v.close() }
    }

    private fun readNdef(tag: Tag): NdefInfo? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message: NdefMessage? = ndef.ndefMessage
            NdefInfo(
                type = ndef.type.substringAfterLast('.'),
                capacity = ndef.maxSize,
                usedBytes = message?.byteArrayLength ?: 0,
                writable = ndef.isWritable,
                entries = message?.records.orEmpty().map {
                    NdefParser.parse(it.tnf.toInt(), it.type, it.payload)
                },
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun identifyUltralight(ul: MifareUltralight): String {
        val fallback = if (ul.type == MifareUltralight.TYPE_ULTRALIGHT_C) "MIFARE Ultralight C" else "MIFARE Ultralight"
        return try {
            ul.connect()
            ChipIdentifier.fromUltralightVersion(ul.transceive(byteArrayOf(0x60))) ?: fallback
        } catch (e: Exception) {
            fallback // old Ultralights don't know GET_VERSION
        } finally {
            runCatching { ul.close() }
        }
    }
}
