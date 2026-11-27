package ng1ok.yggdrasilmanager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ng1ok.yggdrasilmanager.data.hwbp.HwbpSavedConfig
import ng1ok.yggdrasilmanager.data.model.HwbpBacktraceFrame
import ng1ok.yggdrasilmanager.data.model.HwbpEvent
import ng1ok.yggdrasilmanager.data.model.HwbpObserveParams
import ng1ok.yggdrasilmanager.data.model.ProcMaps
import ng1ok.yggdrasilmanager.data.model.ProcMapsSource
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpLibraryTargetResolver
import ng1ok.yggdrasilmanager.ui.hwbp.HwbpPackagePidResolver
import ng1ok.yggdrasilmanager.ui.hwbp.decodeClientCommand
import ng1ok.yggdrasilmanager.ui.hwbp.encodeAck
import ng1ok.yggdrasilmanager.ui.hwbp.encodeEvent
import ng1ok.yggdrasilmanager.ui.hwbp.encodeState
import ng1ok.yggdrasilmanager.ui.hwbp.extractHwbpLibraryNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HwbpProtocolTest {
    private val json = Json

    private val maps = ProcMapsSource {
        """
        7a00000000-7a00100000 r-xp 00001000 00:00 0 /system/lib64/libfoo.so
        7a00100000-7a00200000 r--p 00101000 00:00 0 /system/lib64/libfoo.so
        """.trimIndent()
    }
    private val libraryResolver = HwbpLibraryTargetResolver { pid, library, offset, source ->
        ProcMaps.resolveLibraryTarget(pid, library, offset, source)
    }

    @Test
    fun libraryChoicesDeduplicateSegmentsAndExcludeOtherMappedFiles() {
        val libraries = extractHwbpLibraryNames(
            """
            1000-2000 r-xp 00000000 00:00 0 /system/lib64/libz.so
            2000-3000 r--p 00001000 00:00 0 /system/lib64/libz.so
            3000-4000 r-xp 00000000 00:00 0 /data/app/target/lib/arm64/libalpha.so (deleted)
            4000-5000 r-xp 00000000 00:00 0 /data/app/target/base.apk!/lib/arm64-v8a/libalpha.so
            5000-6000 r-xp 00000000 00:00 0 /system/lib64/libversioned.so.1
            6000-7000 r--p 00000000 00:00 0 /data/app/target/base.apk
            7000-8000 r--p 00000000 00:00 0 /system/fonts/a.ttf
            8000-9000 rw-p 00000000 00:00 0 [anon:libc_malloc]
            9000-a000 r--p 00000000 00:00 0 /data/libname.something
            """.trimIndent()
        )

        assertEquals(listOf("libalpha.so", "libversioned.so.1", "libz.so"), libraries)
    }

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
        assertEquals(4096, command.params.stackSize)
    }

    @Test
    fun observeCommandResolvesLibPlusOffset() {
        val result = decodeClientCommand(
            """{"type":"observe","pid":99,"lib":"libfoo.so","offset":"0x1234","mode":"X","len":4}""",
            maps,
            libraryResolver,
        )

        val command = result.getOrThrow() as HwbpClientCommand.Observe
        assertEquals(0x7a00000234L, command.params.targetAddr)
        assertEquals("libfoo.so", command.params.lib)
        assertEquals(0x1234L, command.params.offset)
    }

    @Test
    fun observeCommandResolvesPackageNameToTheCurrentPid() {
        val packageResolver = HwbpPackagePidResolver { packageName ->
            assertEquals("com.example.target", packageName)
            321
        }
        val result = decodeClientCommand(
            """{"type":"observe","packageName":"com.example.target","addr":"0x1000","mode":"R","len":4}""",
            packagePidResolver = packageResolver,
        )

        val command = result.getOrThrow() as HwbpClientCommand.Observe

        assertEquals(321, command.params.pid)
        assertEquals("com.example.target", command.params.packageName)
    }

    @Test
    fun savedConfigCommandRequiresPackageAndRetainsReusableDefinition() {
        val result = decodeClientCommand(
            """{"type":"saveConfig","id":"system-lib","packageName":"com.example.target","lib":"libfoo.so","offset":"0x20","mode":"RW","len":8,"stackSize":256,"note":"watch entry"}"""
        )

        val command = result.getOrThrow() as HwbpClientCommand.SaveConfig

        assertEquals("system-lib", command.config.id)
        assertEquals("com.example.target", command.config.packageName)
        assertEquals("libfoo.so", command.config.lib)
        assertEquals(0x20L, command.config.offset)
        assertEquals("watch entry", command.config.note)
    }

    @Test
    fun observeCommandRejectsNegativeNumericOffset() {
        val result = decodeClientCommand(
            """{"type":"observe","pid":99,"lib":"libfoo.so","offset":-1,"mode":"X","len":4}""",
            maps,
            libraryResolver,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("non-negative"))
    }

    @Test
    fun configCommandNormalizesBacktraceMode() {
        val result = decodeClientCommand("""{"type":"config","backtraceMode":"DWARF"}""")
        val command = result.getOrThrow() as HwbpClientCommand.Config
        assertEquals("dwarf", command.backtraceMode)
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
            encodeState(
                listOf(HwbpObserveParams(12, 0x1000, 3, 8, 1, 256, "libfoo.so", 0x10)),
                backtraceMode = "dwarf",
                savedConfigs = listOf(
                    HwbpSavedConfig(
                        id = "config-1",
                        packageName = "com.example.target",
                        lib = "libfoo.so",
                        offset = 0x20,
                        mode = 3,
                        len = 8,
                        flags = 1,
                        stackSize = 256,
                        note = "watch entry",
                    )
                ),
            )
        ).jsonObject
        val point = root.getValue("points").jsonArray.single().jsonObject

        assertEquals("state", root.getValue("type").jsonPrimitive.content)
        assertEquals("dwarf", root.getValue("backtraceMode").jsonPrimitive.content)
        assertEquals(12, point.getValue("pid").jsonPrimitive.int)
        assertEquals("0x1000", point.getValue("addr").jsonPrimitive.content)
        assertEquals("RW", point.getValue("mode").jsonPrimitive.content)
        assertEquals(8, point.getValue("len").jsonPrimitive.int)
        assertEquals(1, point.getValue("flags").jsonPrimitive.int)
        assertEquals(256, point.getValue("stackSize").jsonPrimitive.int)
        assertEquals("libfoo.so", point.getValue("lib").jsonPrimitive.content)
        assertEquals("0x10", point.getValue("offset").jsonPrimitive.content)
        val savedConfig = root.getValue("savedConfigs").jsonArray.single().jsonObject
        assertEquals("config-1", savedConfig.getValue("id").jsonPrimitive.content)
        assertEquals("com.example.target", savedConfig.getValue("packageName").jsonPrimitive.content)
        assertEquals("watch entry", savedConfig.getValue("note").jsonPrimitive.content)
    }

    @Test
    fun eventAndAckMatchThePublishedWireShape() {
        val event = HwbpEvent(
            seq = 42,
            dropped = 2,
            tid = 7,
            tgid = 8,
            comm = "target",
            mode = 3,
            len = 8,
            flags = 1,
            watchedAddr = 0x1000,
            pc = 0x2000,
            sp = 0x3000,
            regs = longArrayOf(4, 5),
            backtrace = listOf(
                HwbpBacktraceFrame(0, 0x2000, "libfoo.so", 0x20, "libfoo.so+0x20")
            ),
            backtraceMode = "fp",
        )
        val encodedEvent = json.parseToJsonElement(encodeEvent(event)).jsonObject

        assertEquals("event", encodedEvent.getValue("type").jsonPrimitive.content)
        assertEquals(42L, encodedEvent.getValue("seq").jsonPrimitive.content.toLong())
        assertEquals(
            listOf("0x4", "0x5"),
            encodedEvent.getValue("regs").jsonArray.map { it.jsonPrimitive.content }
        )
        val frame = encodedEvent.getValue("backtrace").jsonArray.single().jsonObject
        assertEquals("libfoo.so+0x20", frame.getValue("label").jsonPrimitive.content)

        val success = json.parseToJsonElement(encodeAck(Result.success(Unit))).jsonObject
        assertTrue(success.getValue("ok").jsonPrimitive.boolean)
        assertEquals("null", success.getValue("error").toString())

        val failure = json.parseToJsonElement(
            encodeAck(Result.failure(IllegalArgumentException("bad input")))
        ).jsonObject
        assertEquals("bad input", failure.getValue("error").jsonPrimitive.content)
    }
}
