package ng1ok.yggdrasilmanager.data.selftest

/** Per-section state shown next to each feature chip. */
enum class SelfTestStatus {
    NOT_RUN,
    QUEUED,
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED,
    ERROR,
}

/** Visual classification of one line of the self-test's stdout. */
enum class SelfTestLogKind {
    PLAIN,
    INFO,
    SECTION,
    PASS,
    FAIL,
    SUMMARY,
    ERROR,
}

data class SelfTestLogLine(
    val text: String,
    val kind: SelfTestLogKind,
)

enum class SelfTestRootState {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
}

/** Aggregated result of one [SelfTestRunner.run] call. */
data class SelfTestRunOutcome(
    val exitCode: Int,
    val checks: Int,
    val failed: Int,
    val statuses: Map<String, SelfTestStatus>,
    val fatal: String? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
    val durationMs: Long = 0L,
) {
    val success: Boolean
        get() = error == null && !cancelled && fatal == null && failed == 0 && exitCode == 0
}

/**
 * Turns the self-test's line-oriented output into statuses.
 *
 * The binary prints exactly these shapes:
 *   `== feature: <id> ==`
 *   `== <id>: SKIPPED (needs root) ==`
 *   `[ ok ] <id>: <check>`
 *   `[FAIL] <id>: <check> (<detail>)`
 *   `summary: <n> checks, <m> failed`
 *   `FATAL: ...` / `ALL PASS` / `RESULT: FAIL (<n>)`
 *
 * The summary rewrites [checks]/[failed] with the binary's own count so a
 * truncated parser can never disagree with the process exit code.
 */
class SelfTestOutputParser {
    private val statuses = linkedMapOf<String, SelfTestStatus>()
    private val failedSections = mutableSetOf<String>()
    private var currentSection: String? = null

    var checks: Int = 0
        private set
    var failed: Int = 0
        private set
    var fatal: String? = null
        private set

    fun accept(rawLine: String): SelfTestLogKind {
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        return when {
            trimmed.startsWith(FEATURE_PREFIX) && trimmed.endsWith(" ==") -> {
                val id = trimmed.removePrefix(FEATURE_PREFIX).removeSuffix(" ==").trim()
                beginSection(id)
                SelfTestLogKind.SECTION
            }

            trimmed.startsWith("==") && trimmed.contains(SKIPPED_MARKER) -> {
                val id = trimmed.removePrefix("==").substringBefore(':').trim()
                finalizeCurrentSection()
                if (id.isNotEmpty()) statuses[id] = SelfTestStatus.SKIPPED
                SelfTestLogKind.INFO
            }

            trimmed.startsWith(PASS_PREFIX) -> {
                recordCheck(trimmed.removePrefix(PASS_PREFIX), passed = true)
                SelfTestLogKind.PASS
            }

            trimmed.startsWith(FAIL_PREFIX) -> {
                recordCheck(trimmed.removePrefix(FAIL_PREFIX), passed = false)
                SelfTestLogKind.FAIL
            }

            trimmed.startsWith(SUMMARY_PREFIX) -> {
                SUMMARY_REGEX.matchEntire(trimmed)?.let { match ->
                    checks = match.groupValues[1].toIntOrNull() ?: checks
                    failed = match.groupValues[2].toIntOrNull() ?: failed
                }
                finalizeCurrentSection()
                SelfTestLogKind.SUMMARY
            }

            trimmed.startsWith("FATAL:") -> {
                fatal = trimmed
                SelfTestLogKind.ERROR
            }

            trimmed == "ALL PASS" || trimmed.startsWith("RESULT: FAIL") -> {
                finalizeCurrentSection()
                SelfTestLogKind.SUMMARY
            }

            trimmed.startsWith("==") -> SelfTestLogKind.SECTION
            else -> SelfTestLogKind.PLAIN
        }
    }

    /** Finalizes the last section when the process has exited. */
    fun finish() {
        finalizeCurrentSection()
    }

    fun statusesSnapshot(): Map<String, SelfTestStatus> = statuses.toMap()

    private fun beginSection(id: String) {
        finalizeCurrentSection()
        if (id.isEmpty()) return
        currentSection = id
        statuses[id] = SelfTestStatus.RUNNING
    }

    private fun recordCheck(rest: String, passed: Boolean) {
        val section = rest.substringBefore(':', missingDelimiterValue = "").trim()
        if (section.isEmpty()) return
        checks++
        if (passed) {
            if (statuses[section] != SelfTestStatus.FAILED) {
                statuses[section] = SelfTestStatus.PASSED
            }
        } else {
            failed++
            failedSections += section
            statuses[section] = SelfTestStatus.FAILED
        }
    }

    private fun finalizeCurrentSection() {
        val id = currentSection ?: return
        statuses[id] = when {
            id in failedSections -> SelfTestStatus.FAILED
            statuses[id] == SelfTestStatus.RUNNING -> SelfTestStatus.PASSED
            else -> statuses[id] ?: SelfTestStatus.PASSED
        }
        currentSection = null
    }

    private companion object {
        const val FEATURE_PREFIX = "== feature: "
        const val SKIPPED_MARKER = ": SKIPPED"
        const val PASS_PREFIX = "[ ok ] "
        const val FAIL_PREFIX = "[FAIL] "
        const val SUMMARY_PREFIX = "summary: "
        val SUMMARY_REGEX = Regex("""summary:\s*(\d+)\s+checks,\s*(\d+)\s+failed""")
    }
}
