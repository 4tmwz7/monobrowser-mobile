package com.voluene.monobrowser.data

import com.voluene.monobrowser.util.writeBytesAtomically
import com.voluene.monobrowser.util.writeTextAtomically
import java.io.File

/** Files in the app's private storage: `session.json` and one WebView state blob per tab (`tabstate/<id>.bin`). */
class SessionStore(private val dir: File) {
    private val sessionFile = File(dir, "session.json")
    private val stateDir = File(dir, "tabstate")

    fun load(): SessionData? =
        runCatching { SessionCodec.decode(sessionFile.readText()) }.getOrNull()

    fun save(data: SessionData) {
        runCatching { writeTextAtomically(sessionFile, SessionCodec.encode(data)) }
    }

    fun readTabState(id: Long): ByteArray? = runCatching { File(stateDir, "$id.bin").readBytes() }.getOrNull()

    fun writeTabState(id: Long, bytes: ByteArray) {
        runCatching { writeBytesAtomically(File(stateDir, "$id.bin"), bytes) }
    }

    fun deleteTabState(id: Long) {
        File(stateDir, "$id.bin").delete()
    }

    /** Removes state blobs of tabs that no longer exist. */
    fun pruneTabStates(keep: Set<Long>) {
        stateDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".bin").toLongOrNull()
            if (id == null || id !in keep) file.delete()
        }
    }
}
