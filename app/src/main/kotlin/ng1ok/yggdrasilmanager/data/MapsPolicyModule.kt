package ng1ok.yggdrasilmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ng1ok.yggdrasilmanager.data.model.MapsMutationResult
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsRuleDraft
import ng1ok.yggdrasilmanager.data.model.MapsScope
import ng1ok.yggdrasilmanager.data.model.MapsScopeTarget

/**
 * Owns Maps policy semantics above the local/root process adapter seam.
 *
 * The module serializes mutations and reads the authoritative scope after every
 * successful mutation, so callers never have to maintain an optimistic policy.
 */
internal class MapsPolicyModule(
    private val operationsProvider: () -> MapsOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Reads the current policy for [target]. */
    suspend fun read(target: MapsScopeTarget): Result<MapsScope> = withContext(ioDispatcher) {
        mutex.withLock {
            readLocked(target)
        }
    }

    /** Adds [draft], reporting mutation failure separately from the scope readback. */
    suspend fun add(target: MapsScopeTarget, draft: MapsRuleDraft): Result<MapsMutationResult> =
        mutate(target) {
            operationsProvider().addRule(target.pidOrNull(), draft.toRule())
        }

    /** Adds [rule], reporting mutation failure separately from the scope readback. */
    suspend fun add(target: MapsScopeTarget, rule: MapsRule): Result<MapsMutationResult> =
        mutate(target) {
            validateRule(rule)
            operationsProvider().addRule(target.pidOrNull(), rule)
        }

    /** Removes [rule], reporting mutation failure separately from the scope readback. */
    suspend fun remove(target: MapsScopeTarget, rule: MapsRule): Result<MapsMutationResult> =
        mutate(target) {
            validateRule(rule)
            operationsProvider().removeRule(target.pidOrNull(), rule)
        }

    /** Clears [target], reporting mutation failure separately from the scope readback. */
    suspend fun clear(target: MapsScopeTarget): Result<MapsMutationResult> =
        mutate(target) {
            operationsProvider().clear(target.pidOrNull(), allScopes = false)
        }

    /** Clears every scope, reporting mutation failure separately from the global readback. */
    suspend fun clearAllScopes(): Result<MapsMutationResult> =
        mutate(MapsScopeTarget.Global) {
            operationsProvider().clear(pid = null, allScopes = true)
        }

    private suspend fun mutate(
        target: MapsScopeTarget,
        operation: suspend () -> Int,
    ): Result<MapsMutationResult> = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val result = operation()
                if (result != 0) {
                    Result.failure(RuntimeException("prctl errno ${-result}"))
                } else {
                    Result.success(MapsMutationResult(readback = readLocked(target)))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
    }

    private suspend fun readLocked(target: MapsScopeTarget): Result<MapsScope> = try {
        val scope = operationsProvider().get(target.pidOrNull())
            ?: return Result.failure(IllegalStateException("Maps 規則查詢失敗"))
        require(scope.global == (target === MapsScopeTarget.Global)) {
            "Maps 查詢回傳了錯誤的 scope"
        }
        Result.success(scope)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun MapsRuleDraft.toRule(): MapsRule {
        val isAddressRange = matchKind == MapsPolicy.MATCH_ADDR_RANGE
        return MapsRule(
            action = action,
            matchKind = matchKind,
            matchPerms = matchPerms.trim().ifEmpty { null },
            setPerms = setPerms.trim().ifEmpty { null },
            addrStart = if (isAddressRange) parseAddress(startAddress) else 0L,
            addrEnd = if (isAddressRange) parseAddress(endAddress) else 0L,
            pattern = pattern,
        ).also(::validateRule)
    }

    private fun parseAddress(text: String): Long {
        if (text.isBlank()) return 0L
        val normalized = text.trim().removePrefix("0x").removePrefix("0X")
        return normalized.toULongOrNull(16)?.toLong()
            ?: throw IllegalArgumentException("位址必須是十六進位")
    }

    private fun validateRule(rule: MapsRule) {
        require(rule.action == MapsPolicy.ACTION_HIDE || rule.action == MapsPolicy.ACTION_REWRITE_PERMS) {
            "不支援的 Maps 動作"
        }
        require(rule.matchKind in MapsPolicy.MATCH_ANY..MapsPolicy.MATCH_ADDR_RANGE) {
            "不支援的 Maps 匹配類型"
        }
        require(rule.pattern.length <= MapsPolicy.MAX_PATTERN_LENGTH && '\u0000' !in rule.pattern) {
            "路徑樣式過長或包含 NUL 字元"
        }
        if (rule.matchKind == MapsPolicy.MATCH_ANY || rule.matchKind == MapsPolicy.MATCH_ADDR_RANGE) {
            require(rule.matchKind == MapsPolicy.MATCH_ADDR_RANGE || rule.pattern.isEmpty()) {
                "此匹配類型不使用路徑樣式"
            }
        } else {
            require(rule.pattern.isNotEmpty()) { "路徑匹配需要填寫路徑樣式" }
        }
        if (rule.matchKind == MapsPolicy.MATCH_ADDR_RANGE) {
            require(rule.addrStart >= 0 && rule.addrEnd > rule.addrStart) {
                "位址範圍不能為空"
            }
        }
        rule.matchPerms?.let(::validatePermissions)
        if (rule.action == MapsPolicy.ACTION_REWRITE_PERMS) {
            requireNotNull(rule.setPerms) { "改寫權限時必須填寫設定權限" }
            validatePermissions(rule.setPerms)
        } else {
            require(rule.setPerms == null) { "隱藏動作不能設定權限" }
        }
    }

    private fun validatePermissions(value: String) {
        require(value.length == 3 && value.all { it == 'r' || it == 'w' || it == 'x' || it == '-' }) {
            "權限必須是三個 rwx 或 - 字元"
        }
    }
}
