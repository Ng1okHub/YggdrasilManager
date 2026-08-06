package ng1ok.yggdrasilmanager.ui.syscall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.data.model.SyscallTracePhase

internal sealed interface SyscallClientCommand {
    data class Observe(val rule: SyscallObserveRule) : SyscallClientCommand

    data class Unobserve(val rule: SyscallObserveRule) : SyscallClientCommand

    data object RestartListener : SyscallClientCommand
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
    return SyscallObserveRule(syscallNr, scope, targetId, phase)
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
): String = buildJsonObject {
    put("type", "state")
    put("listener", buildJsonObject {
        put("state", listenerStatus.state.wireName)
        put("error", listenerStatus.error?.let(::JsonPrimitive) ?: JsonNull)
    })
    put("rules", buildJsonArray {
        rules.forEach { rule ->
            add(buildJsonObject {
                put("syscallNr", rule.syscallNr)
                put("name", ng1ok.yggdrasilmanager.data.model.Arm64SyscallCatalog.nameFor(rule.syscallNr))
                put("scope", rule.scope.wireName)
                put("targetId", rule.targetId)
                put("phase", rule.phase.wireName)
            })
        }
    })
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
    if (event.phase == SyscallEvent.PHASE_EXIT) {
        put("returnBefore", event.returnBefore.toHexValue())
        put("return", event.returnAfter.toHexValue())
    } else {
        put("returnBefore", JsonNull)
        put("return", JsonNull)
    }
}.toString()

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
