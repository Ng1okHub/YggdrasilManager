package ng1ok.yggdrasilmanager.data.selftest

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface SelfTestRunner {
    /** The bundled binary is arm64 only. */
    val supported: Boolean

    suspend fun rootState(): SelfTestRootState

    /**
     * Runs [features] and streams the binary's output through [onLine].
     *
     * Selecting every feature maps to one unfiltered run (the binary's own
     * "run everything" mode); a subset runs one process per section so each
     * section keeps an independent result and can be cancelled between runs.
     */
    suspend fun run(
        features: List<SelfTestFeature>,
        onLine: (String, SelfTestLogKind) -> Unit,
        onStatuses: (Map<String, SelfTestStatus>) -> Unit = {},
    ): SelfTestRunOutcome

    fun requestCancel()
}

/**
 * Deploys yggdrasil_test.cpp's compiled binary to /data/local/tmp and runs it
 * through `su -c`, capturing stdout line by line.
 *
 * A standalone root process is required: the syscall/stramp/freeze sections
 * manipulate credentials and freeze thread groups, and the test forks its own
 * fixture children. Running it inside the Manager's app process (or inside the
 * libsu root service JVM) would change what those checks observe.
 */
class YggdrasilSelfTestRunner(context: Context) : SelfTestRunner {
    private val appContext = context.applicationContext

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var cachedShell: Shell? = null

    @Volatile
    private var cachedSuPath: String? = null

    override val supported: Boolean
        get() = Build.SUPPORTED_ABIS.contains(ARM64_ABI)

    override suspend fun rootState(): SelfTestRootState = withContext(Dispatchers.IO) {
        try {
            if (rootShell().isRoot) SelfTestRootState.GRANTED else SelfTestRootState.DENIED
        } catch (_: Exception) {
            SelfTestRootState.DENIED
        }
    }

    override suspend fun run(
        features: List<SelfTestFeature>,
        onLine: (String, SelfTestLogKind) -> Unit,
        onStatuses: (Map<String, SelfTestStatus>) -> Unit,
    ): SelfTestRunOutcome = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        if (!supported) {
            return@withContext failure(
                "此裝置架構不受支援：模組自測程式僅提供 arm64-v8a。",
                startedAt,
            )
        }
        if (features.isEmpty()) {
            return@withContext failure("請先選擇至少一個測試項目。", startedAt)
        }

        val shell = try {
            rootShell()
        } catch (e: Exception) {
            return@withContext failure("無法啟動 Root Shell：${e.message}", startedAt)
        }
        if (!shell.isRoot) {
            return@withContext failure("需要 Root 權限才能執行模組自測。", startedAt)
        }

        val suPath = resolveSuPath(shell)
        cancelRequested = false
        try {
            deploy(shell, suPath)
        } catch (e: Exception) {
            return@withContext failure("無法部署自測程式：${e.message}", startedAt)
        }

        val runAll = features.size == SelfTestFeature.entries.size
        val filters: List<String?> = if (runAll) listOf(null) else features.map { it.id }
        val statuses = linkedMapOf<String, SelfTestStatus>()
        var checks = 0
        var failed = 0
        var exitCode = 0
        var fatal: String? = null

        for ((index, filter) in filters.withIndex()) {
            if (cancelRequested) break

            if (filter != null) {
                statuses[filter] = SelfTestStatus.RUNNING
                onStatuses(statuses.toMap())
            }

            val parser = SelfTestOutputParser()
            val exit = try {
                runSection(suPath, filter, parser, onLine) { snapshot ->
                    val merged = LinkedHashMap(statuses)
                    merged.putAll(snapshot)
                    onStatuses(merged)
                }
            } catch (e: IOException) {
                if (cancelRequested) break
                return@withContext failure("無法執行自測程式：${e.message}", startedAt)
            }

            parser.finish()
            val snapshot = parser.statusesSnapshot()
            if (filter == null) {
                statuses.putAll(snapshot)
            } else {
                statuses[filter] = snapshot[filter]
                    ?: if (exit == 0) SelfTestStatus.PASSED else SelfTestStatus.ERROR
            }
            onStatuses(statuses.toMap())

            checks += parser.checks
            failed += parser.failed
            if (exit != 0) exitCode = exit
            parser.fatal?.let { fatal = it }

            if (filters.size > 1 && index != filters.lastIndex && !cancelRequested) {
                // Separate individually-run sections in the console.
                onLine("", SelfTestLogKind.PLAIN)
            }
        }

        if (cancelRequested) {
            cleanupAfterCancel(shell)
            features.forEach { feature ->
                if (statuses[feature.id] == null ||
                    statuses[feature.id] == SelfTestStatus.RUNNING ||
                    statuses[feature.id] == SelfTestStatus.QUEUED
                ) {
                    statuses[feature.id] = SelfTestStatus.NOT_RUN
                }
            }
        } else if (fatal != null) {
            features.forEach { feature ->
                if (statuses[feature.id] == null || statuses[feature.id] == SelfTestStatus.RUNNING) {
                    statuses[feature.id] = SelfTestStatus.ERROR
                }
            }
        }
        onStatuses(statuses.toMap())

        SelfTestRunOutcome(
            exitCode = if (cancelRequested) -1 else exitCode,
            checks = checks,
            failed = failed,
            statuses = statuses.toMap(),
            fatal = fatal,
            cancelled = cancelRequested,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    override fun requestCancel() {
        cancelRequested = true
        activeProcess?.destroyForcibly()
    }

    private suspend fun rootShell(): Shell {
        cachedShell?.takeIf { it.isAlive }?.let { return it }
        val shell = withTimeoutOrNull(SHELL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Shell> { continuation ->
                Shell.getShell { shell ->
                    if (continuation.isActive) continuation.resume(shell)
                }
            }
        } ?: throw IOException("Root Shell 啟動逾時")
        cachedShell = shell
        return shell
    }

    private fun resolveSuPath(shell: Shell): String {
        cachedSuPath?.let { return it }
        val resolved = runCatching {
            val job = shell.newJob()
            // Shell.newJob() returns a bare JobImpl whose output list is null
            // unless to() is supplied; Shell.cmd() would install a NOPList.
            job.to(mutableListOf<String>())
            job.add("command -v su 2>/dev/null")
            job.exec().out.firstOrNull { it.trim().startsWith("/") }?.trim()
        }.getOrNull() ?: "su"
        cachedSuPath = resolved
        return resolved
    }

    /**
     * Writes the asset into /data/local/tmp through a root `cat` process.
     * Piping the bytes through stdin avoids relying on SELinux permitting the
     * root domain to read app-private storage. The size check catches a
     * truncated transfer, and the executable bit is applied by a separate
     * root command so the data transfer has exactly one job: forwarding
     * stdin to the file.
     */
    private fun deploy(shell: Shell, suPath: String) {
        val payload = appContext.assets.open(ASSET_PATH).use { it.readBytes() }
        val process = ProcessBuilder(
            suPath,
            "-c",
            "cat > '$DEVICE_PATH'",
        ).redirectErrorStream(true).start()

        try {
            process.outputStream.use { output -> output.write(payload) }
            if (!process.waitFor(DEPLOY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("部署逾時")
            }
            if (process.exitValue() != 0) {
                val detail = runCatching {
                    process.inputStream.bufferedReader().use { it.readText() }.trim()
                }.getOrDefault("")
                throw IOException(
                    "部署失敗（exit ${process.exitValue()}）" + detail.take(200),
                )
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        val verify = runCatching {
            val job = shell.newJob()
            job.to(mutableListOf<String>())
            job.add("chmod 755 '$DEVICE_PATH' && stat -c %s '$DEVICE_PATH'")
            job.exec()
        }.getOrNull()
        val deployedSize = verify?.out?.firstNotNullOfOrNull { it.trim().toLongOrNull() }
        if (verify == null || !verify.isSuccess || deployedSize != payload.size.toLong()) {
            throw IOException(
                "部署內容不完整（${deployedSize ?: "未知"}/${payload.size} 位元組）",
            )
        }
    }

    private fun runSection(
        suPath: String,
        filter: String?,
        parser: SelfTestOutputParser,
        onLine: (String, SelfTestLogKind) -> Unit,
        onStatuses: (Map<String, SelfTestStatus>) -> Unit,
    ): Int {
        val command = if (filter == null) DEVICE_PATH else "$DEVICE_PATH $filter"
        val process = ProcessBuilder(suPath, "-c", command)
            .redirectErrorStream(true)
            .start()
        activeProcess = process

        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val kind = parser.accept(line)
                    onLine(line, kind)
                    onStatuses(parser.statusesSnapshot())
                }
            }
            return process.waitFor()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            activeProcess = null
        }
    }

    /**
     * The test only resets module state on its own exit paths. After a kill,
     * reap forked fixture children (`killall` matches their inherited comm)
     * and run the binary with a filter that matches no section: main still
     * executes reset_module_state() for every invocation.
     */
    private fun cleanupAfterCancel(shell: Shell) {
        runCatching {
            val reap = shell.newJob()
            reap.add("killall yggdrasil_test 2>/dev/null")
            reap.exec()
        }
        runCatching {
            val reset = shell.newJob()
            reset.add("$DEVICE_PATH $CLEANUP_FILTER")
            reset.exec()
        }
    }

    private fun failure(message: String, startedAt: Long) = SelfTestRunOutcome(
        exitCode = -1,
        checks = 0,
        failed = 0,
        statuses = emptyMap(),
        error = message,
        durationMs = SystemClock.elapsedRealtime() - startedAt,
    )

    private companion object {
        const val ARM64_ABI = "arm64-v8a"
        const val ASSET_PATH = "yggdrasil_test/arm64-v8a/yggdrasil_test"
        const val DEVICE_PATH = "/data/local/tmp/yggdrasil_test"
        const val CLEANUP_FILTER = "__yggmanager_cleanup__"
        const val SHELL_TIMEOUT_MS = 15_000L
        const val DEPLOY_TIMEOUT_SECONDS = 10L
    }
}
