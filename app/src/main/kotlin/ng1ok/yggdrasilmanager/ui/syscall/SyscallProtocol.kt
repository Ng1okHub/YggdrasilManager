package ng1ok.yggdrasilmanager.ui.syscall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ng1ok.yggdrasilmanager.data.hidedbg.InstalledApp
import ng1ok.yggdrasilmanager.data.model.Arm64SyscallCatalog
import ng1ok.yggdrasilmanager.data.model.Arm64SyscallEntry
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallBacktrace
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.data.model.SyscallTracePhase

internal sealed interface SyscallClientCommand {
    data class Observe(val rule: SyscallObserveRule) : SyscallClientCommand

    data class Unobserve(val rule: SyscallObserveRule) : SyscallClientCommand

    data object RestartListener : SyscallClientCommand

    data class Config(val backtraceMode: String) : SyscallClientCommand
}

private val protocolJson = Json { ignoreUnknownKeys = true }
private const val GLOBAL_SCOPE_UNAVAILABLE =
    "無法使用全域範圍，否則會觀察到觀察器本身"
private const val ROOT_UID_SCOPE_UNAVAILABLE =
    "無法使用 UID 0，否則會觀察到 root 觀察器本身"

internal fun decodeSyscallClientCommand(text: String): Result<SyscallClientCommand> = runCatching {
    val root = protocolJson.parseToJsonElement(text).jsonObject
    when (val type = root.requiredString("type")) {
        "observe" -> SyscallClientCommand.Observe(
            decodeRule(
                root = root,
                phase = root.requiredPhase("phase"),
                allowObserverScope = false,
            ),
        )
        "unobserve" -> SyscallClientCommand.Unobserve(
            // Delete only uses the selector; ENTER also keeps exit(2) rules valid.
            decodeRule(
                root = root,
                phase = SyscallTracePhase.ENTER,
                // Existing broad/root rules must remain removable.
                allowObserverScope = true,
            ),
        )
        "restartListener" -> SyscallClientCommand.RestartListener
        "config" -> SyscallClientCommand.Config(
            backtraceMode = SyscallBacktrace.normalizeMode(root.requiredString("backtraceMode")),
        )
        else -> throw IllegalArgumentException("未知的訊息類型：$type")
    }
}

private fun decodeRule(
    root: JsonObject,
    phase: SyscallTracePhase,
    allowObserverScope: Boolean,
): SyscallObserveRule {
    val syscallNr = root["syscallNr"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("必須提供系統呼叫編號")
    val scope = when (root.requiredString("scope").lowercase()) {
        SyscallScope.GLOBAL.wireName -> {
            require(allowObserverScope) { GLOBAL_SCOPE_UNAVAILABLE }
            SyscallScope.GLOBAL
        }
        SyscallScope.TGID.wireName -> SyscallScope.TGID
        SyscallScope.TID.wireName -> SyscallScope.TID
        SyscallScope.UID.wireName -> SyscallScope.UID
        else -> throw IllegalArgumentException("範圍必須為 global、tgid、tid 或 uid")
    }
    val targetId = if (scope == SyscallScope.GLOBAL) {
        root["targetId"]?.jsonPrimitive?.longOrNull?.also {
            require(it == 0L) { "全域範圍的目標 ID 必須為 0" }
        } ?: 0L
    } else {
        root["targetId"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("必須提供目標 ID")
    }
    require(
        allowObserverScope || scope != SyscallScope.UID || targetId != 0L
    ) { ROOT_UID_SCOPE_UNAVAILABLE }
    val captureBacktrace = root["backtrace"]?.jsonPrimitive?.booleanOrNull
        ?: root["captureBacktrace"]?.jsonPrimitive?.booleanOrNull
        ?: false
    val stackSizeElement = root["backtraceStackSize"]
    require(captureBacktrace || stackSizeElement == null) {
        "backtraceStackSize requires backtrace"
    }
    val backtraceStackSize = if (captureBacktrace) {
        stackSizeElement?.jsonPrimitive?.intOrNull
            ?: SyscallBacktrace.DEFAULT_STACK_SIZE
    } else {
        0
    }
    return SyscallObserveRule(
        syscallNr = syscallNr,
        scope = scope,
        targetId = targetId,
        phase = phase,
        captureBacktrace = captureBacktrace,
        backtraceStackSize = backtraceStackSize,
    )
}

private fun JsonObject.requiredString(name: String): String {
    val label = when (name) {
        "type" -> "訊息類型"
        "scope" -> "範圍"
        "phase" -> "觀察階段"
        else -> name
    }
    return this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("必須提供$label")
}

private fun JsonObject.requiredPhase(name: String): SyscallTracePhase = when (
    requiredString(name).lowercase()
) {
    SyscallTracePhase.ENTER.wireName -> SyscallTracePhase.ENTER
    SyscallTracePhase.EXIT.wireName -> SyscallTracePhase.EXIT
    SyscallTracePhase.BOTH.wireName -> SyscallTracePhase.BOTH
    else -> throw IllegalArgumentException("觀察階段必須為 enter、exit 或 both")
}

internal fun encodeSyscallState(
    rules: List<SyscallObserveRule>,
    listenerStatus: SyscallListenerStatus = SyscallListenerStatus(),
    catalog: List<Arm64SyscallEntry> = emptyList(),
    backtraceMode: String = SyscallBacktrace.MODE_FP,
): String = buildJsonObject {
    put("type", "state")
    put("backtraceMode", SyscallBacktrace.normalizeMode(backtraceMode))
    put("listener", buildJsonObject {
        put("state", listenerStatus.state.wireName)
        put("error", listenerStatus.error?.let(::JsonPrimitive) ?: JsonNull)
    })
    put("syscallCatalog", buildJsonArray {
        catalog.forEach { entry ->
            add(buildJsonObject {
                put("nr", entry.nr)
                put("name", entry.name)
                put("availability", entry.availability.wireName)
                put("schema", entry.hasArgumentSchema)
                put("enterOnly", entry.enterOnly)
            })
        }
    })
    put("rules", buildJsonArray {
        rules.forEach { rule ->
            add(buildJsonObject {
                put("syscallNr", rule.syscallNr)
                put("name", Arm64SyscallCatalog.nameFor(rule.syscallNr))
                put("scope", rule.scope.wireName)
                put("targetId", rule.targetId)
                put("phase", rule.phase.wireName)
                put("backtrace", rule.captureBacktrace)
                put("backtraceStackSize", rule.backtraceStackSize)
            })
        }
    })
}.toString()

internal fun encodeInstalledApps(apps: List<InstalledApp>): String = buildJsonObject {
    put("apps", buildJsonArray {
        apps.forEach { app ->
            add(buildJsonObject {
                put("packageName", app.packageName)
                put("label", app.label)
                put("uid", app.uid)
                put("isSystem", app.isSystem)
            })
        }
    })
}.toString()

internal fun encodeInstalledAppsError(error: Throwable): String = buildJsonObject {
    put(
        "error",
        error.message?.takeIf(String::isNotBlank) ?: "無法讀取已安裝套件",
    )
}.toString()

internal fun encodeSyscallEvent(event: SyscallEvent): String = buildJsonObject {
    put("type", "event")
    put("seq", event.seq.toHexValue())
    put("dropped", event.dropped.toHexValue())
    put("callId", event.callId.toHexValue())
    put("phase", event.phaseName)
    put("tid", event.tid)
    put("tgid", event.tgid)
    put("comm", event.comm)
    put("syscallNr", event.syscallNr)
    put("name", event.syscallName)
    put("scope", scopeName(event.ruleFlags, event.targetId))
    put("targetId", event.targetId)
    put("eventFlags", event.eventFlags)
    put("relayDropped", event.relayDropped.toHexValue())
    put(
        "afterDropped",
        event.eventFlags and SyscallEvent.FLAG_AFTER_DROPPED != 0 || event.relayDropped > 0,
    )
    put("userCookie", event.userCookie.toHexValue())
    put("argMask", event.argMask.toHexValue())
    put("argsBefore", buildJsonArray {
        event.argsBefore.forEach { add(JsonPrimitive(it.toHexValue())) }
    })
    put("args", buildJsonArray {
        event.argsAfter.forEach { add(JsonPrimitive(it.toHexValue())) }
    })
    put("captures", buildJsonArray {
        event.captures.forEach { capture ->
            add(buildJsonObject {
                put("argIndex", capture.argIndex)
                put("kind", capture.kindName)
                put("schema", capture.schemaName)
                put("phase", capture.phaseName)
                put("source", capture.pointerSourceName)
                put("address", capture.address.toHexValue())
                put("capturedLength", capture.capturedLength)
                put("originalLength", capture.originalLength)
                put("status", capture.statusName)
                put("value", capture.value?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    })
    if (event.phase == SyscallEvent.PHASE_EXIT) {
        put("returnBefore", event.returnBefore.toHexValue())
        put("return", event.returnAfter.toHexValue())
    } else {
        put("returnBefore", JsonNull)
        put("return", JsonNull)
    }
    val backtrace = event.backtrace
    if (backtrace != null) {
        put("backtraceMode", backtrace.mode)
        put("backtraceNote", backtrace.note?.let(::JsonPrimitive) ?: JsonNull)
        put("backtrace", buildJsonObject {
            put("flags", backtrace.flags)
            put("seq", backtrace.seq.toHexValue())
            put("dropped", backtrace.dropped.toHexValue())
            put("callId", backtrace.callId.toHexValue())
            put("phase", backtrace.phaseName())
            put("tid", backtrace.tid)
            put("tgid", backtrace.tgid)
            put("syscallNr", backtrace.syscallNr)
            put("stackAddr", backtrace.stackAddr.toHexValue())
            put("stackRequestedSize", backtrace.stackRequestedSize)
            put("stackCopiedSize", backtrace.stackCopiedSize)
        })
        put("backtraceFrames", buildJsonArray {
            backtrace.frames.forEach { frame ->
                add(buildJsonObject {
                    put("index", frame.index)
                    put("pc", frame.pc.toHexValue())
                    put("label", frame.label)
                    put("module", frame.module?.let(::JsonPrimitive) ?: JsonNull)
                    put("offset", frame.offset?.toHexValue()?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
    } else {
        put("backtraceMode", JsonNull)
        put("backtraceNote", JsonNull)
        put("backtrace", JsonNull)
        put("backtraceFrames", buildJsonArray { })
    }
}.toString()

private fun SyscallBacktrace.phaseName(): String = when (phase) {
    SyscallEvent.PHASE_ENTER -> "enter"
    SyscallEvent.PHASE_EXIT -> "exit"
    else -> "unknown"
}

internal fun encodeSyscallAck(result: Result<Unit>): String = buildJsonObject {
    put("type", "ack")
    put("ok", result.isSuccess)
    put("error", result.exceptionOrNull()?.message?.let(::JsonPrimitive) ?: JsonNull)
}.toString()

private fun scopeName(flags: Int, targetId: Long): String = when {
    flags and SyscallObserveRule.FLAG_TARGET_UID != 0 -> SyscallScope.UID.wireName
    flags and SyscallObserveRule.FLAG_SINGLE_THREAD != 0 -> SyscallScope.TID.wireName
    targetId == 0L -> SyscallScope.GLOBAL.wireName
    else -> SyscallScope.TGID.wireName
}

private fun Long.toHexValue(): String =
    "0x${java.lang.Long.toUnsignedString(this, 16)}"
