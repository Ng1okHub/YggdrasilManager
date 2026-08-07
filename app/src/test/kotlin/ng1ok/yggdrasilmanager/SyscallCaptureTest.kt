package ng1ok.yggdrasilmanager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import ng1ok.yggdrasilmanager.data.model.SyscallCapture
import ng1ok.yggdrasilmanager.data.model.SyscallCaptureDecoder
import ng1ok.yggdrasilmanager.data.model.SyscallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyscallCaptureTest {
    @Test
    fun decodesCStringAndBinaryBytesWithoutTreatingThemAsUtf8() {
        val cstring = captureTlv(
            argIndex = 1,
            kind = SyscallCapture.KIND_CSTRING,
            data = byteArrayOf(
                'A'.code.toByte(), '"'.code.toByte(), '\\'.code.toByte(),
                '\n'.code.toByte(), 0, 'x'.code.toByte(),
            ),
        )
        val bytes = captureTlv(
            argIndex = 2,
            kind = SyscallCapture.KIND_BYTES,
            status = SyscallCapture.STATUS_TRUNCATED or SyscallCapture.STATUS_BUDGET,
            originalLength = 9,
            data = byteArrayOf('B'.code.toByte(), 0, 0xff.toByte()),
        )

        val decoded = SyscallCaptureDecoder.decode(2, cstring + bytes)

        assertEquals("\"A\\\"\\\\\\n\"", decoded[0].value)
        assertEquals("\"B\\x00\\xff\"", decoded[1].value)
        assertEquals("truncated|budget", decoded[1].statusName)
        assertEquals(3, decoded[1].capturedLength)
        assertEquals(9, decoded[1].originalLength)
    }

    @Test
    fun decodesOpenHowAndSignedTimespec64() {
        val openHow = littleEndianData(24)
            .putLong(0, 0x42)
            .putLong(8, 0x1a4)
            .putLong(16, 0x8)
            .array()
        val timespec = littleEndianData(16)
            .putLong(0, -2)
            .putLong(8, 123_456_789)
            .array()
        val payload = captureTlv(
            argIndex = 2,
            kind = SyscallCapture.KIND_STRUCT,
            schemaId = SyscallCapture.SCHEMA_OPEN_HOW,
            data = openHow,
        ) + captureTlv(
            argIndex = 1,
            kind = SyscallCapture.KIND_STRUCT,
            schemaId = SyscallCapture.SCHEMA_TIMESPEC64,
            phase = SyscallEvent.PHASE_EXIT,
            data = timespec,
        )

        val decoded = SyscallCaptureDecoder.decode(2, payload)

        assertEquals("{flags=0x42 mode=0644 resolve=0x8}", decoded[0].value)
        assertEquals("{tv_sec=-2 tv_nsec=123456789}", decoded[1].value)
    }

    @Test
    fun decodesStatxFieldsAtTheReferenceClientOffsets() {
        val statx = littleEndianData(160)
            .putInt(0, 0x1fff)
            .putInt(4, 4096)
            .putLong(8, 0x20)
            .putInt(16, 2)
            .putInt(20, 1000)
            .putInt(24, 1001)
            .putShort(28, 0x81a4.toShort())
            .putLong(32, 99)
            .putLong(40, 1234)
            .putLong(48, 8)
            .putLong(56, 0x40)
            .putLong(64, 10)
            .putInt(72, 20)
            .putLong(80, 30)
            .putInt(88, 40)
            .putLong(96, 50)
            .putInt(104, 60)
            .putLong(112, 70)
            .putInt(120, 80)
            .putInt(128, 1)
            .putInt(132, 2)
            .putInt(136, 3)
            .putInt(140, 4)
            .putLong(144, 55)
            .putInt(152, 512)
            .putInt(156, 4096)
            .array()

        val value = SyscallCaptureDecoder.decode(
            1,
            captureTlv(
                argIndex = 4,
                kind = SyscallCapture.KIND_STRUCT,
                schemaId = SyscallCapture.SCHEMA_STATX,
                phase = SyscallEvent.PHASE_EXIT,
                data = statx,
            ),
        ).single().value.orEmpty()

        assertTrue(value.startsWith("{mask=0x1fff blksize=4096 attributes=0x20"))
        assertTrue(value.contains("uid=1000 gid=1001 mode=0100644 ino=99 size=1234"))
        assertTrue(value.contains("atime=10.000000020 btime=30.000000040"))
        assertTrue(value.contains("rdev=1:2 dev=3:4 mnt_id=55"))
        assertTrue(value.endsWith("dio_mem_align=512 dio_offset_align=4096}"))
    }

    @Test
    fun decodesUnixIpv4AndIpv6SockaddrValues() {
        val unix = byteArrayOf(1, 0) + "/tmp/socket\u0000ignored".encodeToByteArray()
        val ipv4 = byteArrayOf(
            2, 0, 0x01, 0xbb.toByte(),
            127, 0, 0, 1,
        )
        val ipv6 = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 10)
            put(2, 0)
            put(3, 53)
            put(7, 7)
            put(8, 0x20)
            put(9, 0x01)
            put(10, 0x0d)
            put(11, 0xb8.toByte())
            put(22, 0)
            put(23, 1)
            putInt(24, 2)
        }.array()
        val payload = listOf(unix, ipv4, ipv6).fold(ByteArray(0)) { result, data ->
            result + captureTlv(
                argIndex = 1,
                kind = SyscallCapture.KIND_STRUCT,
                schemaId = SyscallCapture.SCHEMA_SOCKADDR,
                data = data,
            )
        }

        val decoded = SyscallCaptureDecoder.decode(3, payload)

        assertEquals("{family=AF_UNIX path=\"/tmp/socket\"}", decoded[0].value)
        assertEquals("{family=AF_INET address=127.0.0.1 port=443}", decoded[1].value)
        assertEquals(
            "{family=AF_INET6 address=2001:db8::1 port=53 flowinfo=7 scope_id=2}",
            decoded[2].value,
        )
    }

    @Test
    fun formatsIpv4MappedIpv6LikeInetNtop() {
        val ipv6 = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 10)
            put(2, 0x01)
            put(3, 0xbb.toByte())
            put(18, 0xff.toByte())
            put(19, 0xff.toByte())
            put(20, 192.toByte())
            put(21, 0)
            put(22, 2)
            put(23, 128.toByte())
        }.array()

        val value = SyscallCaptureDecoder.decode(
            1,
            captureTlv(
                argIndex = 1,
                kind = SyscallCapture.KIND_STRUCT,
                schemaId = SyscallCapture.SCHEMA_SOCKADDR,
                data = ipv6,
            ),
        ).single().value

        assertEquals(
            "{family=AF_INET6 address=::ffff:192.0.2.128 port=443 flowinfo=0 scope_id=0}",
            value,
        )
    }

    @Test
    fun exposesStatusWhenNoBytesCouldBeCaptured() {
        val decoded = SyscallCaptureDecoder.decode(
            1,
            captureTlv(
                argIndex = 1,
                kind = SyscallCapture.KIND_CSTRING,
                status = SyscallCapture.STATUS_FAULT,
                originalLength = 16,
                data = byteArrayOf(),
            ),
        ).single()

        assertEquals("fault", decoded.statusName)
        assertNull(decoded.value)
    }

    @Test
    fun rejectsMisalignedAndTrailingCaptureRecords() {
        val valid = captureTlv(
            argIndex = 0,
            kind = SyscallCapture.KIND_CSTRING,
            data = "ok".encodeToByteArray(),
        )
        val misaligned = valid.copyOf().also { it[0] = 34 }

        assertThrows(IllegalArgumentException::class.java) {
            SyscallCaptureDecoder.decode(1, misaligned)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyscallCaptureDecoder.decode(1, valid + byteArrayOf(0))
        }
    }

    private fun captureTlv(
        argIndex: Int,
        kind: Int,
        schemaId: Int = SyscallCapture.SCHEMA_NONE,
        phase: Int = SyscallEvent.PHASE_ENTER,
        status: Int = 0,
        originalLength: Int = -1,
        address: Long = 0x1234,
        data: ByteArray,
    ): ByteArray {
        val size = align8(32 + data.size)
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(size.toShort())
            put(argIndex.toByte())
            put(kind.toByte())
            put(phase.toByte())
            put(SyscallCapture.POINTER_EFFECTIVE.toByte())
            putShort(schemaId.toShort())
            putInt(status)
            putInt(if (originalLength >= 0) originalLength else data.size)
            putInt(data.size)
            putInt(0)
            putLong(address)
            put(data)
        }.array()
    }

    private fun littleEndianData(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun align8(value: Int): Int = (value + 7) and 7.inv()
}
