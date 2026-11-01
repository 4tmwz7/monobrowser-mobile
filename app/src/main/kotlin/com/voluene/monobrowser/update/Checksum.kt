package com.voluene.monobrowser.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Checksum {
    private val HEX64 = Regex("""^[0-9a-fA-F]{64}$""")

    /**
     * Reads a `.sha256` file: either `sha256sum` output (`<hex>  <name>` / `<hex> *<name>`) or a bare hash.
     * When the file names files, the line for [fileName] is used. Returns lowercase hex, or null.
     */
    fun parse(text: String, fileName: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (line in lines) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            val hash = parts[0]
            if (!HEX64.matches(hash)) continue
            val name = parts.getOrNull(1)?.trim()?.removePrefix("*")?.substringAfterLast('/')
            if (name == null || name == fileName) return hash.lowercase()
        }
        return null
    }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use(::sha256Hex)

    fun matches(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(expectedHex.lowercase().toByteArray(), actualHex.lowercase().toByteArray())
}
