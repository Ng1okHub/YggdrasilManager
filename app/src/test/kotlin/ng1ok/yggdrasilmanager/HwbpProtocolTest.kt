package ng1ok.yggdrasilmanager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.decodeClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.encodeAck
import ng1ok.yggdrasilmanager.ui.hwbp.encodeEvent
import ng1ok.yggdrasilmanager.ui.hwbp.encodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HwbpProtocolTest {
    private val json = Json

    @Test
    fun observeCommandMapsModeDefaultsAndUnsignedAddress() {
        val result = decodeClientCommand(
            """{"type":"observe","pid":1234,"addr":"0xffff000000001000","mode":"RW","len":8}"""
        )

        val command = result.getOrThrow() as HwbpClientCommand.Observe

        assertEquals(1234, command.params.pid)
        assertEquals(
            "ffff000000001000",
            java.lang.Long.toUnsignedString(command.params.targetAddr, 16)
        )
        assertEquals(3, command.params.mode)
        assertEquals(8, command.params.len)
        assertEquals(0, command.params.flags)
        assertEquals(256, command.params.stackSize)
    }

    @Test
    fun unobserveCommandAcceptsCanonicalHexAddress() {
        val result = decodeClientCommand(
            """{"type":"unobserve","pid":99,"addr":"0x7ff0001000"}"""
        )

        val command = result.getOrThrow() as HwbpClientCommand.Unobserve

        assertEquals(99, command.pid)
        assertEquals(0x7ff0001000L, command.targetAddr)
    }

    @Test
    fun malformedAndUnsupportedCommandsFailWithStableMessages() {
        assertEquals(
            "Unknown message type: ping",
            decodeClientCommand("""{"type":"ping"}""").exceptionOrNull()?.message
        )
        assertEquals(
            "pid must be positive",
            decodeClientCommand(
                """{"type":"observe","pid":0,"addr":"0x1000","mode":"R","len":4}"""
            ).exceptionOrNull()?.message
        )
        assertEquals(
            "X mode requires len 4",
            decodeClientCommand(
                """{"type":"observe","pid":1,"addr":"0x1000","mode":"X","len":8}"""
            ).exceptionOrNull()?.message
        )
    }

    @Test
    fun stateContainsEveryRequiredPointField() {
        val root = json.parseToJsonElement(
            encodeState(listOf(HwbpObserveParams(12, 0x1000, 3, 8, 1, 256)))
        ).jsonObject
        val point = root.getValue("points").jsonArray.single().jsonObject

        assertEquals("state", root.getValue("type").jsonPrimitive.content)
        assertEquals(12, point.getValue("pid").jsonPrimitive.int)
        assertEquals("0x1000", point.getValue("addr").jsonPrimitive.content)
        assertEquals("RW", point.getValue("mode").jsonPrimitive.content)
        assertEquals(8, point.getValue("len").jsonPrimitive.int)
        assertEquals(1, point.getValue("flags").jsonPrimitive.int)
        assertEquals(256, point.getValue("stackSize").jsonPrimitive.int)
    }

    @Test
    fun eventAndAckMatchThePublishedWireShape() {
        val event = HwbpEvent(
            42,
            2,
            7,
            8,
            "target",
            3,
            8,
            0,
            0x1000,
            0x2000,
            0x3000,
            longArrayOf(4, 5)
        )
        val encodedEvent = json.parseToJsonElement(encodeEvent(event)).jsonObject

        assertEquals("event", encodedEvent.getValue("type").jsonPrimitive.content)
        assertEquals(42L, encodedEvent.getValue("seq").jsonPrimitive.content.toLong())
        assertEquals(
            listOf("4", "5"),
            encodedEvent.getValue("regs").jsonArray.map { it.jsonPrimitive.content }
        )

        val success = json.parseToJsonElement(encodeAck(Result.success(Unit))).jsonObject
        assertTrue(success.getValue("ok").jsonPrimitive.boolean)
        assertEquals("null", success.getValue("error").toString())

        val failure = json.parseToJsonElement(
            encodeAck(Result.failure(IllegalArgumentException("bad input")))
        ).jsonObject
        assertEquals("bad input", failure.getValue("error").jsonPrimitive.content)
    }
}
