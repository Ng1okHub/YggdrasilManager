package ng1ok.yggdrasilmanager.data.model

import com.topjohnwu.superuser.Shell
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ProcMapEntry(
    val start: Long,
    val end: Long,
    val perms: String,
    val fileOffset: Long,
    val path: String,
) {
    val executable: Boolean get() = 'x' in perms
    val base: Long get() = start - fileOffset
    val name: String
        get() {
            val slash = path.lastIndexOf('/')
            return if (slash >= 0 && slash < path.lastIndex) path.substring(slash + 1) else path
        }
}

data class SymbolizedAddress(
    val pc: Long,
    val module: String?,
    val offset: Long?,
    val path: String?,
) {
    val label: String
        get() = when {
            module != null && offset != null ->
                "$module+0x${java.lang.Long.toUnsignedString(offset, 16)}"
            else -> "0x${java.lang.Long.toUnsignedString(pc, 16)}"
        }
}

fun interface ProcMapsSource {
    fun readMaps(pid: Int): String?
}

data class ProcMapsSnapshot(
    val text: String?,
    val entries: List<ProcMapEntry>,
)

interface ProcMapsSnapshotSource : ProcMapsSource {
    fun readSnapshot(pid: Int): ProcMapsSnapshot

    override fun readMaps(pid: Int): String? = readSnapshot(pid).text
}

class CachingProcMapsSource(
    private val delegate: ProcMapsSource,
    private val ttlNanos: Long = 2_000_000_000L,
    private val maxEntries: Int = 32,
    private val clockNanos: () -> Long = System::nanoTime,
) : ProcMapsSnapshotSource {
    private data class CacheEntry(
        val loadedAtNanos: Long,
        val snapshot: ProcMapsSnapshot,
    )

    private val cache = ConcurrentHashMap<Int, CacheEntry>()

    init {
        require(ttlNanos >= 0) { "ttlNanos must be non-negative" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    override fun readSnapshot(pid: Int): ProcMapsSnapshot {
        if (pid <= 0) return ProcMapsSnapshot(null, emptyList())
        val now = clockNanos()
        cache[pid]?.takeIf { it.isFresh(now) }?.let { return it.snapshot }

        val updated = cache.compute(pid) { _, current ->
            val computeNow = clockNanos()
            if (current != null && current.isFresh(computeNow)) {
                current
            } else {
                val text = delegate.readMaps(pid)
                CacheEntry(
                    loadedAtNanos = clockNanos(),
                    snapshot = ProcMapsSnapshot(text, text?.let(ProcMaps::parse).orEmpty()),
                )
            }
        }!!
        evictOldestEntry(pid)
        return updated.snapshot
    }

    fun invalidate(pid: Int) {
        cache.remove(pid)
    }

    private fun CacheEntry.isFresh(now: Long): Boolean {
        val age = now - loadedAtNanos
        return age >= 0 && age < ttlNanos
    }

    private fun evictOldestEntry(currentPid: Int) {
        if (cache.size <= maxEntries) return
        val oldest = cache.entries
            .asSequence()
            .filter { it.key != currentPid }
            .minByOrNull { it.value.loadedAtNanos }
            ?: return
        cache.remove(oldest.key, oldest.value)
    }
}

/**
 * Read /proc/<pid>/maps.
 *
 * Direct File I/O almost always fails for other apps (UID/SELinux), so we fall
 * back to a root shell via libsu — matching how this manager already elevates.
 */
object FileProcMapsSource : ProcMapsSource {
    override fun readMaps(pid: Int): String? {
        if (pid <= 0) return null
        val path = "/proc/$pid/maps"

        readDirect(path)?.let { return it }
        return readViaRoot(path)
    }

    private fun readDirect(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        val text = file.readText()
        text.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readViaRoot(path: String): String? = runCatching {
        // Root acquisition can prompt, so callers must keep this source off the main thread.
        val shell = Shell.getShell()
        if (!shell.isRoot) return null

        val result = Shell.cmd("cat ${shellQuote(path)}").exec()
        if (!result.isSuccess) return null
        result.out.joinToString("\n").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

object CachedFileProcMapsSource : ProcMapsSnapshotSource {
    private val cache = CachingProcMapsSource(FileProcMapsSource)

    override fun readSnapshot(pid: Int): ProcMapsSnapshot = cache.readSnapshot(pid)
}

object ProcMaps {
    private val lineRegex = Regex(
        """^([0-9a-fA-F]+)-([0-9a-fA-F]+)\s+(\S+)\s+([0-9a-fA-F]+)\s+\S+\s+\S+\s*(.*)$"""
    )

    fun parse(text: String): List<ProcMapEntry> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val match = lineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
            val path = match.groupValues[5].trim()
            ProcMapEntry(
                start = match.groupValues[1].toULong(16).toLong(),
                end = match.groupValues[2].toULong(16).toLong(),
                perms = match.groupValues[3],
                fileOffset = match.groupValues[4].toULong(16).toLong(),
                path = path,
            )
        }.toList()
    }

    fun load(pid: Int, source: ProcMapsSource = FileProcMapsSource): List<ProcMapEntry> {
        val text = source.readMaps(pid) ?: return emptyList()
        return parse(text)
    }

    fun resolveLibraryTarget(
        maps: List<ProcMapEntry>,
        lib: String,
        offset: Long,
    ): Long {
        require(lib.isNotBlank()) { "lib is required" }
        val needle = lib.trim()
        val entry = maps.firstOrNull { entry ->
            entry.path.isNotBlank() && entry.path.contains(needle) && entry.executable
        } ?: maps.firstOrNull { it.path.contains(needle) }
            ?: throw IllegalArgumentException("failed to find library containing '$needle' in maps")
        return entry.base + offset
    }

    fun resolveLibraryTarget(
        pid: Int,
        lib: String,
        offset: Long,
        source: ProcMapsSource = FileProcMapsSource,
    ): Long {
        val maps = load(pid, source)
        require(maps.isNotEmpty()) {
            "failed to read maps for pid=$pid " +
                "(process missing, no root, or SELinux blocked /proc/$pid/maps)"
        }
        return resolveLibraryTarget(maps, lib, offset)
    }

    fun symbolize(maps: List<ProcMapEntry>, pc: Long): SymbolizedAddress {
        val unsignedPc = pc.toULong()
        val entry = maps.firstOrNull { entry ->
            entry.executable &&
                unsignedPc >= entry.start.toULong() &&
                unsignedPc < entry.end.toULong()
        } ?: maps.firstOrNull { entry ->
            unsignedPc >= entry.start.toULong() && unsignedPc < entry.end.toULong()
        }
        if (entry == null || entry.path.isBlank()) {
            return SymbolizedAddress(pc = pc, module = null, offset = null, path = null)
        }
        val offset = (unsignedPc - entry.base.toULong()).toLong()
        return SymbolizedAddress(
            pc = pc,
            module = entry.name.ifBlank { entry.path },
            offset = offset,
            path = entry.path,
        )
    }
}
