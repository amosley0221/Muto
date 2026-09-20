package dev.muto.app.data

import dev.muto.core.filter.DomainSet
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Keeps each subscribed list on disk in the form the matcher actually uses.
 *
 * Re-parsing a 200,000-line hosts file every time the tunnel comes up would add seconds to the
 * start of protection, on the main path, for no benefit - the text does not change between
 * downloads. So a download is parsed once and the resulting hashes are written out; starting up
 * is then a sequential read of a few hundred kilobytes.
 */
class CompiledListStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    /** A compiled list: the domains it blocks, and the exceptions it carves out of them. */
    data class Compiled(val blocked: DomainSet, val allowed: DomainSet) {
        val entryCount: Int get() = blocked.size + allowed.size
    }

    fun write(id: String, blocked: List<String>, allowed: List<String>): Compiled {
        val compiled = Compiled(DomainSet.of(blocked), DomainSet.of(allowed))
        val blockedHashes = compiled.blocked.toHashArray()
        val allowedHashes = compiled.allowed.toHashArray()

        // Write to a sibling and rename, so a process death mid-write cannot leave a truncated
        // file that would silently drop half a list on the next start.
        val temp = File(directory, "$id.tmp")
        DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(blockedHashes.size)
            out.writeInt(allowedHashes.size)
            for (hash in blockedHashes) out.writeLong(hash)
            for (hash in allowedHashes) out.writeLong(hash)
        }
        if (!temp.renameTo(fileFor(id))) {
            temp.delete()
            throw IOException("Could not replace the compiled list for $id")
        }
        return compiled
    }

    /** Returns null when there is no cache yet, or when what is there is not readable. */
    fun read(id: String): Compiled? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != FORMAT_VERSION) return null
                val blockedCount = input.readInt()
                val allowedCount = input.readInt()
                if (blockedCount < 0 || allowedCount < 0) return null
                val blocked = LongArray(blockedCount) { input.readLong() }
                val allowed = LongArray(allowedCount) { input.readLong() }
                Compiled(DomainSet.fromHashArray(blocked), DomainSet.fromHashArray(allowed))
            }
        } catch (e: IOException) {
            // A corrupt cache is not worth recovering; the next refresh rebuilds it.
            file.delete()
            null
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    fun sizeOnDisk(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(id: String) = File(directory, "$id.bin")

    private companion object {
        /** "MUTO" so a stray file is recognisable and a mismatch fails fast. */
        const val MAGIC = 0x4D55544F
        const val FORMAT_VERSION = 1
    }
}
