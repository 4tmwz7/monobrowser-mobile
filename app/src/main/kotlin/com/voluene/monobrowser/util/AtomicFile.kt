package com.voluene.monobrowser.util

import java.io.File
import java.io.IOException

/**
 * Atomic write (port of the desktop `writeJsonAtomically`): the data goes to a sibling temp file first and is
 * then renamed over the target, so a crash mid-write never leaves a truncated file behind.
 */
fun writeBytesAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
        temp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            // renameTo can refuse to overwrite on some filesystems.
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Cannot rename ${temp.name} to ${target.name}")
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}

fun writeTextAtomically(target: File, text: String) = writeBytesAtomically(target, text.toByteArray(Charsets.UTF_8))
