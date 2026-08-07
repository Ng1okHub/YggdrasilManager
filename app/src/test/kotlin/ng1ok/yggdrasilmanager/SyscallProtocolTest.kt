package ng1ok.yggdrasilmanager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ng1ok.yggdrasilmanager.data.model.SyscallCapture
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import ng1ok.yggdrasilmanager.data.model.SyscallListenerState
import ng1ok.yggdrasilmanager.data.model.SyscallListenerStatus
import ng1ok.yggdrasilmanager.data.model.SyscallObserveRule
import ng1ok.yggdrasilmanager.data.model.SyscallScope
import ng1ok.yggdrasilmanager.data.model.SyscallTracePhase
import ng1ok.yggdrasilmanager.ui.syscall.SyscallClientCommand
import ng1ok.yggdrasilmanager.ui.syscall.decodeSyscallClientCommand
import ng1ok.yggdrasilmanager.ui.syscall.encodeSyscallAck
import ng1ok.yggdrasilmanager.ui.syscall.encodeSyscallEvent
import ng1ok.yggdrasilmanager.ui.syscall.encodeSyscallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyscallProtocolTest {
    private val json = Json

    @Test
    fun observeAndLegacyUnobserveDecodeIntoTheExpectedSelectors() {
        val observe = decodeSyscallClientCommand(
            """{"type":"observe","syscallNr":63,"scope":"tid","targetId":1234,"phase":"both"}""",
        ).getOrThrow() as SyscallClientCommand.Observe

        assertEquals(63, observe.rule.syscallNr)
        assertEquals(SyscallScope.TID, observe.rule.scope)
        assertEquals(1234L, observe.rule.targetId)
        assertEquals(SyscallTracePhase.BOTH, observe.rule.phase)
        assertEquals(
            SyscallObserveRule.FLAG_SINGLE_THREAD or
                SyscallObserveRule.FLAG_TRACE_ENTER or
                SyscallObserveRule.FLAG_TRACE_EXIT,
            observe.rule.configureFlags,
        )

        val unobserve = decodeSyscallClientCommand(
            """{"type":"unobserve","syscallNr":93,"scope":"global"}""",
        ).getOrThrow() as SyscallClientCommand.Unobserve

        assertEquals(93, unobserve.rule.syscallNr)
        assertEquals(SyscallScope.GLOBAL, unobserve.rule.scope)
        assertEquals(0L, unobserve.rule.targetId)
        assertEquals(SyscallTracePhase.ENTER, unobserve.rule.phase)

        val rootUidUnobserve = decodeSyscallClientCommand(
            """{"type":"unobserve","syscallNr":64,"scope":"uid","targetId":0}""",
        ).getOrThrow() as SyscallClientCommand.Unobserve

        assertEquals(64, rootUidUnobserve.rule.syscallNr)
        assertEquals(SyscallScope.UID, rootUidUnobserve.rule.scope)
        assertEquals(0L, rootUidUnobserve.rule.targetId)
        assertEquals(SyscallTracePhase.ENTER, rootUidUnobserve.rule.phase)

        assertEquals(
            SyscallClientCommand.RestartListener,
            decodeSyscallClientCommand("""{"type":"restartListener"}""").getOrThrow(),
        )
    }

    @Test
    fun observeRejectsScopesThatWouldTraceTheObserver() {
        assertEquals(
            "無法使用全域範圍，否則會觀察到觀察器本身",
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"global","phase":"enter"}""",
            ).exceptionOrNull()?.message,
        )
        assertEquals(
            "無法使用 UID 0，否則會觀察到 root 觀察器本身",
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"uid","targetId":0,"phase":"exit"}""",
            ).exceptionOrNull()?.message,
        )

        assertEquals(
            "Process（TGID）必須介於 1 至 2147483647 之間",
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"tgid","targetId":0,"phase":"enter"}""",
            ).exceptionOrNull()?.message,
        )
        assertEquals(
            "UID 必須介於 0 至 4294967295 之間",
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"uid","targetId":-1,"phase":"enter"}""",
            ).exceptionOrNull()?.message,
        )
        assertEquals(
            "全域範圍的目標 ID 必須為 0",
            decodeSyscallClientCommand(
                """{"type":"unobserve","syscallNr":64,"scope":"global","targetId":7}""",
            ).exceptionOrNull()?.message,
        )
    }

    @Test
    fun exitAndExitGroupOnlyPermitEnterTracing() {
        listOf(93, 94).forEach { syscallNr ->
            assertEquals(
                "exit 與 exit_group 僅支援進入階段的觀察",
                decodeSyscallClientCommand(
                    """{"type":"observe","syscallNr":$syscallNr,"scope":"tgid","targetId":1234,"phase":"exit"}""",
                ).exceptionOrNull()?.message,
            )
            assertEquals(
                "exit 與 exit_group 僅支援進入階段的觀察",
                decodeSyscallClientCommand(
                    """{"type":"observe","syscallNr":$syscallNr,"scope":"tgid","targetId":1234,"phase":"both"}""",
                ).exceptionOrNull()?.message,
            )
        }

        assertTrue(
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":93,"scope":"tgid","targetId":1234,"phase":"enter"}""",
            ).isSuccess,
        )
    }

    @Test
    fun uidUsesTheFullUnsigned32BitRangeWhileProcessTargetsStaySignedInt() {
        val maxUid = SyscallObserveRule.MAX_UID
        val uidRule = decodeSyscallClientCommand(
            """{"type":"observe","syscallNr":64,"scope":"uid","targetId":$maxUid,"phase":"enter"}""",
        ).getOrThrow() as SyscallClientCommand.Observe

        assertEquals(maxUid, uidRule.rule.targetId)
        assertEquals(
            "UID 必須介於 0 至 4294967295 之間",
            decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"uid","targetId":${maxUid + 1},"phase":"enter"}""",
            ).exceptionOrNull()?.message,
        )

        listOf(SyscallScope.TGID, SyscallScope.TID).forEach { scope ->
            val scopeLabel = if (scope == SyscallScope.TGID) {
                "Process（TGID）"
            } else {
                "Thread（TID）"
            }
            val maxProcessRule = decodeSyscallClientCommand(
                """{"type":"observe","syscallNr":64,"scope":"${scope.wireName}","targetId":${SyscallObserveRule.MAX_PROCESS_ID},"phase":"enter"}""",
            )
            assertTrue(maxProcessRule.isSuccess)
            assertEquals(
                "${scopeLabel}必須介於 1 至 2147483647 之間",
                decodeSyscallClientCommand(
                    """{"type":"observe","syscallNr":64,"scope":"${scope.wireName}","targetId":${SyscallObserveRule.MAX_PROCESS_ID + 1},"phase":"enter"}""",
                ).exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun stateEventAndAckUseThePublishedWireShape() {
        val state = json.parseToJsonElement(
            encodeSyscallState(
                listOf(
                    SyscallObserveRule(64, SyscallScope.GLOBAL, 0, SyscallTracePhase.BOTH),
                    SyscallObserveRule(63, SyscallScope.UID, SyscallObserveRule.MAX_UID, SyscallTracePhase.ENTER),
                ),
                SyscallListenerStatus(
                    state = SyscallListenerState.FAILED,
                    error = "系統呼叫監聽器已停止（errno 32）",
                ),
                supportedSyscalls = listOf(64, 56, 64, -1, 460),
            ),
        ).jsonObject
        val global = state.getValue("rules").jsonArray[0].jsonObject

        assertEquals("state", state.getValue("type").jsonPrimitive.content)
        assertEquals(64, global.getValue("syscallNr").jsonPrimitive.int)
        assertEquals("write", global.getValue("name").jsonPrimitive.content)
        assertEquals("global", global.getValue("scope").jsonPrimitive.content)
        assertEquals(0L, global.getValue("targetId").jsonPrimitive.long)
        assertEquals("both", global.getValue("phase").jsonPrimitive.content)
        val supportedSyscalls = state.getValue("supportedSyscalls").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(56, 64),
            supportedSyscalls.map { it.getValue("nr").jsonPrimitive.int },
        )
        assertEquals(
            listOf("openat", "write"),
            supportedSyscalls.map { it.getValue("name").jsonPrimitive.content },
        )
        val listener = state.getValue("listener").jsonObject
        assertEquals("failed", listener.getValue("state").jsonPrimitive.content)
        assertEquals(
            "系統呼叫監聽器已停止（errno 32）",
            listener.getValue("error").jsonPrimitive.content,
        )
        val uid = state.getValue("rules").jsonArray[1].jsonObject
        assertEquals(SyscallObserveRule.MAX_UID, uid.getValue("targetId").jsonPrimitive.long)

        val captureData = byteArrayOf(
            'o'.code.toByte(),
            'k'.code.toByte(),
            '\n'.code.toByte(),
            0xff.toByte(),
        )
        val capturePayload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(40)
            put(1)
            put(SyscallCapture.KIND_BYTES.toByte())
            put(SyscallEvent.PHASE_EXIT.toByte())
            put(SyscallCapture.POINTER_EFFECTIVE.toByte())
            putShort(SyscallCapture.SCHEMA_NONE.toShort())
            putInt(SyscallCapture.STATUS_TRUNCATED)
            putInt(8)
            putInt(captureData.size)
            putInt(0)
            putLong(-1)
            put(captureData)
        }.array()
        val event = json.parseToJsonElement(
            encodeSyscallEvent(
                SyscallEvent(
                    seq = -1L,
                    dropped = 2L,
                    callId = Long.MIN_VALUE,
                    phase = SyscallEvent.PHASE_EXIT,
                    tid = 71,
                    tgid = 70,
                    syscallNr = 64,
                    eventFlags = SyscallEvent.FLAG_AFTER_DROPPED,
                    ruleFlags = SyscallObserveRule.FLAG_TARGET_UID,
                    targetId = SyscallObserveRule.MAX_UID,
                    comm = "target",
                    userCookie = -2L,
                    argMask = -1L,
                    argsBefore = listOf(-1L, 2L),
                    argsAfter = listOf(3L, Long.MIN_VALUE),
                    returnBefore = -4095L,
                    returnAfter = -1L,
                    captureCount = 1,
                    capturePayload = capturePayload,
                    relayDropped = 3,
                ),
            ),
        ).jsonObject

        assertEquals("event", event.getValue("type").jsonPrimitive.content)
        assertEquals("0xffffffffffffffff", event.getValue("seq").jsonPrimitive.content)
        assertEquals("0x8000000000000000", event.getValue("callId").jsonPrimitive.content)
        assertEquals("uid", event.getValue("scope").jsonPrimitive.content)
        assertEquals(SyscallObserveRule.MAX_UID, event.getValue("targetId").jsonPrimitive.long)
        assertEquals("0xfffffffffffffffe", event.getValue("userCookie").jsonPrimitive.content)
        assertEquals("0xffffffffffffffff", event.getValue("argMask").jsonPrimitive.content)
        assertEquals(
            listOf("0xffffffffffffffff", "0x2"),
            event.getValue("argsBefore").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("0x3", "0x8000000000000000"),
            event.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("0xfffffffffffff001", event.getValue("returnBefore").jsonPrimitive.content)
        assertEquals("0xffffffffffffffff", event.getValue("return").jsonPrimitive.content)
        val capture = event.getValue("captures").jsonArray.single().jsonObject
        assertEquals(1, capture.getValue("argIndex").jsonPrimitive.int)
        assertEquals("bytes", capture.getValue("kind").jsonPrimitive.content)
        assertEquals("none", capture.getValue("schema").jsonPrimitive.content)
        assertEquals("exit", capture.getValue("phase").jsonPrimitive.content)
        assertEquals("effective", capture.getValue("source").jsonPrimitive.content)
        assertEquals("0xffffffffffffffff", capture.getValue("address").jsonPrimitive.content)
        assertEquals(4L, capture.getValue("capturedLength").jsonPrimitive.long)
        assertEquals(8L, capture.getValue("originalLength").jsonPrimitive.long)
        assertEquals("truncated", capture.getValue("status").jsonPrimitive.content)
        assertEquals("\"ok\\n\\xff\"", capture.getValue("value").jsonPrimitive.content)
        assertTrue(event.getValue("afterDropped").jsonPrimitive.boolean)
        assertEquals("0x3", event.getValue("relayDropped").jsonPrimitive.content)

        val success = json.parseToJsonElement(encodeSyscallAck(Result.success(Unit))).jsonObject
        assertTrue(success.getValue("ok").jsonPrimitive.boolean)
        assertEquals("null", success.getValue("error").toString())

        val failure = json.parseToJsonElement(
            encodeSyscallAck(Result.failure(IllegalArgumentException("native rejected"))),
        ).jsonObject
        assertEquals("ack", failure.getValue("type").jsonPrimitive.content)
        assertEquals("native rejected", failure.getValue("error").jsonPrimitive.content)
    }
}
