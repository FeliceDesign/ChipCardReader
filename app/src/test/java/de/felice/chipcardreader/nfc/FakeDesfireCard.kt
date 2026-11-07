package de.felice.chipcardreader.nfc

/**
 * Pretends to be a DESFire card: a map from "command APDU (hex)" to
 * "response (hex)". Anything unknown answers 91 1C (illegal command).
 */
class FakeDesfireCard(private val script: Map<String, List<String>>) : Transceiver {
    private val queues = script.mapValues { ArrayDeque(it.value) }.toMutableMap()
    val sent = mutableListOf<String>()

    override fun transceive(command: ByteArray): ByteArray {
        val key = command.toHex("")
        sent += key
        val queue = queues[key]
        val answer = when {
            queue == null -> "911C"
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
        return answer.hexToBytes()
    }
}

fun String.hexToBytes(): ByteArray =
    replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
