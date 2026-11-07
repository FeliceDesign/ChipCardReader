package de.felice.chipcardreader.cards

import de.felice.chipcardreader.nfc.DesfireClient
import de.felice.chipcardreader.nfc.DesfireException
import de.felice.chipcardreader.nfc.FileSettings
import de.felice.chipcardreader.nfc.toHex

/**
 * Walks through a DESFire card and collects everything that is readable
 * WITHOUT a key. This is how you'd figure out a new card format
 * (e.g. your Deutschlandticket): scan, look at the bytes, compare.
 *
 * It never tries to authenticate or guess keys.
 */
object DesfireExplorer {
    private const val MAX_APPS = 28 // DESFire limit per card

    fun explore(client: DesfireClient, appIds: List<Int>): DesfireDump {
        val version = runCatching { client.getVersion().toHex() }.getOrNull()
        val apps = appIds.take(MAX_APPS).map { aid -> exploreApp(client, aid) }
        return DesfireDump(version, apps)
    }

    private fun exploreApp(client: DesfireClient, aid: Int): AppDump = try {
        client.selectApplication(aid)
        val files = client.getFileIds().map { fileId -> exploreFile(client, fileId) }
        AppDump(aid, files)
    } catch (e: DesfireException) {
        AppDump(aid, emptyList(), error = e.message)
    }

    private fun exploreFile(client: DesfireClient, fileId: Int): FileDump {
        val settings = try {
            client.getFileSettings(fileId)
        } catch (e: DesfireException) {
            return FileDump(fileId, "?", false, "Einstellungen nicht lesbar: ${e.message}")
        }
        val free = settings.accessRights.freelyReadable
        val content = if (!free) {
            "🔒 geschützt"
        } else try {
            when (settings) {
                is FileSettings.Data -> client.readData(fileId).toHex()
                is FileSettings.Record -> client.readRecords(fileId).toHex()
                is FileSettings.Value -> "Wert: ${client.getValue(fileId)}"
                is FileSettings.Unknown -> "—"
            }
        } catch (e: DesfireException) {
            "Lesen fehlgeschlagen: ${e.message}"
        }
        return FileDump(fileId, settings.typeName, free, content)
    }
}
