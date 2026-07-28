package ng1ok.yggdrasilmanager.ui.hwbp

import com.topjohnwu.superuser.Shell

internal fun interface HwbpPackagePidResolver {
    /** Resolves an app's main process package name to a currently running PID. */
    fun resolve(packageName: String): Int
}

internal object RootHwbpPackagePidResolver : HwbpPackagePidResolver {
    override fun resolve(packageName: String): Int {
        val normalized = normalizePackageName(packageName)
        val shell = Shell.getShell()
        if (!shell.isRoot) {
            throw IllegalStateException("Root access is required to resolve package '$normalized'")
        }

        val result = Shell.cmd("pidof ${shellQuote(normalized)}").exec()
        val pids = result.out.asSequence()
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .mapNotNull(String::toIntOrNull)
            .filter { it > 0 }
            .toList()
        if (!result.isSuccess || pids.isEmpty()) {
            throw IllegalArgumentException("Package '$normalized' is not running")
        }
        if (pids.size > 1) {
            throw IllegalArgumentException(
                "Package '$normalized' has multiple running processes; use its PID instead"
            )
        }
        return pids.single()
    }
}

internal fun normalizePackageName(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty() && normalized.length <= 255) {
        "packageName is required"
    }
    require(normalized.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
        "packageName is invalid"
    }
    require('.' in normalized && !normalized.startsWith('.') && !normalized.endsWith('.')) {
        "packageName is invalid"
    }
    return normalized
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
