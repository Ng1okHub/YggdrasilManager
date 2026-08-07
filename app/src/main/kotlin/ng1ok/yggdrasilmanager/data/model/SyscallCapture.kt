package ng1ok.yggdrasilmanager.data.model

data class SyscallCapture(
    val argIndex: Int,
    val kind: Int,
    val phase: Int,
    val pointerSource: Int,
    val schemaId: Int,
    val status: Int,
    val originalLength: Long,
    val capturedLength: Long,
    val address: Long,
    val value: String?,
) {
    val kindName: String
        get() = when (kind) {
            KIND_CSTRING -> "cstring"
            KIND_BYTES -> "bytes"
            KIND_STRUCT -> "struct"
            else -> "unknown"
        }

    val phaseName: String
        get() = when (phase) {
            SyscallEvent.PHASE_ENTER -> "enter"
            SyscallEvent.PHASE_EXIT -> "exit"
            else -> "unknown"
        }

    val pointerSourceName: String
        get() = when (pointerSource) {
            POINTER_ORIGINAL -> "original"
            POINTER_EFFECTIVE -> "effective"
            else -> "unknown"
        }

    val schemaName: String
        get() = when (schemaId) {
            SCHEMA_NONE -> "none"
            SCHEMA_OPEN_HOW -> "open_how"
            SCHEMA_STATX -> "statx"
            SCHEMA_TIMESPEC64 -> "timespec64"
            SCHEMA_SOCKADDR -> "sockaddr"
            else -> "unknown"
        }

    val statusName: String
        get() {
            if (status == 0) return "ok"
            return buildList {
                if (status and STATUS_NULL != 0) add("null")
                if (status and STATUS_FAULT != 0) add("fault")
                if (status and STATUS_TRUNCATED != 0) add("truncated")
                if (status and STATUS_SKIPPED != 0) add("skipped")
                if (status and STATUS_BUDGET != 0) add("budget")
            }.joinToString("|")
        }

    companion object {
        const val KIND_CSTRING = 1
        const val KIND_BYTES = 2
        const val KIND_STRUCT = 3

        const val POINTER_ORIGINAL = 1
        const val POINTER_EFFECTIVE = 2

        const val SCHEMA_NONE = 0
        const val SCHEMA_OPEN_HOW = 1
        const val SCHEMA_STATX = 2
        const val SCHEMA_TIMESPEC64 = 3
        const val SCHEMA_SOCKADDR = 4

        const val STATUS_NULL = 1 shl 0
        const val STATUS_FAULT = 1 shl 1
        const val STATUS_TRUNCATED = 1 shl 2
        const val STATUS_SKIPPED = 1 shl 3
        const val STATUS_BUDGET = 1 shl 4
    }
}

internal object SyscallCaptureDecoder {
    private const val TLV_SIZE = 32
    private const val CAPTURE_COUNT_MAX = 6
    private const val STATUS_MASK =
        SyscallCapture.STATUS_NULL or
            SyscallCapture.STATUS_FAULT or
            SyscallCapture.STATUS_TRUNCATED or
            SyscallCapture.STATUS_SKIPPED or
            SyscallCapture.STATUS_BUDGET

    fun decode(captureCount: Int, payload: ByteArray): List<SyscallCapture> {
        require(captureCount in 0..CAPTURE_COUNT_MAX) {
            "invalid syscall capture count: $captureCount"
        }
        if (captureCount == 0) {
            require(payload.isEmpty()) { "capture payload has no descriptors" }
            return emptyList()
        }

        val captures = ArrayList<SyscallCapture>(captureCount)
        var cursor = 0
        repeat(captureCount) { index ->
            require(payload.size - cursor >= TLV_SIZE) {
                "capture $index header overruns payload"
            }
            val size = payload.readLe16(cursor)
            val argIndex = payload.u8(cursor + 2)
            val kind = payload.u8(cursor + 3)
            val phase = payload.u8(cursor + 4)
            val pointerSource = payload.u8(cursor + 5)
            val schemaId = payload.readLe16(cursor + 6)
            val status = payload.readLe32(cursor + 8).toInt()
            val originalLength = payload.readLe32(cursor + 12)
            val capturedLength = payload.readLe32(cursor + 16)
            val reserved = payload.readLe32(cursor + 20)
            val address = payload.readLe64(cursor + 24)

            require(size >= TLV_SIZE && size % 8 == 0 && size <= payload.size - cursor) {
                "invalid capture $index size: $size"
            }
            require(
                capturedLength <= originalLength &&
                    capturedLength <= (size - TLV_SIZE).toLong(),
            ) {
                "invalid capture $index lengths: $capturedLength/$originalLength"
            }
            require(size == align8(TLV_SIZE + capturedLength.toInt())) {
                "capture $index size does not match its data length"
            }
            require(argIndex in 0 until 6) { "invalid capture $index argument: $argIndex" }
            require(kind in SyscallCapture.KIND_CSTRING..SyscallCapture.KIND_STRUCT) {
                "invalid capture $index kind: $kind"
            }
            require(schemaId in SyscallCapture.SCHEMA_NONE..SyscallCapture.SCHEMA_SOCKADDR) {
                "invalid capture $index schema: $schemaId"
            }
            require(schemaId == SyscallCapture.SCHEMA_NONE || kind == SyscallCapture.KIND_STRUCT) {
                "capture $index schema requires a struct"
            }
            require(phase == SyscallEvent.PHASE_ENTER || phase == SyscallEvent.PHASE_EXIT) {
                "invalid capture $index phase: $phase"
            }
            require(
                pointerSource == SyscallCapture.POINTER_ORIGINAL ||
                    pointerSource == SyscallCapture.POINTER_EFFECTIVE,
            ) { "invalid capture $index pointer source: $pointerSource" }
            require(status and STATUS_MASK.inv() == 0) {
                "invalid capture $index status: $status"
            }
            require(reserved == 0L) { "capture $index reserved field is not zero" }

            val dataStart = cursor + TLV_SIZE
            val dataEnd = dataStart + capturedLength.toInt()
            val data = payload.copyOfRange(dataStart, dataEnd)
            val value = if (capturedLength > 0 || status == 0) {
                decodeValue(kind, schemaId, data)
            } else {
                null
            }
            captures += SyscallCapture(
                argIndex = argIndex,
                kind = kind,
                phase = phase,
                pointerSource = pointerSource,
                schemaId = schemaId,
                status = status,
                originalLength = originalLength,
                capturedLength = capturedLength,
                address = address,
                value = value,
            )
            cursor += size
        }
        require(cursor == payload.size) {
            "capture payload has ${payload.size - cursor} trailing bytes"
        }
        return captures
    }

    private fun decodeValue(kind: Int, schemaId: Int, data: ByteArray): String = when (kind) {
        SyscallCapture.KIND_CSTRING -> "\"${escapeBytes(data, stopAtNul = true)}\""
        SyscallCapture.KIND_BYTES -> "\"${escapeBytes(data, stopAtNul = false)}\""
        else -> when (schemaId) {
            SyscallCapture.SCHEMA_OPEN_HOW -> decodeOpenHow(data)
            SyscallCapture.SCHEMA_STATX -> decodeStatx(data)
            SyscallCapture.SCHEMA_TIMESPEC64 -> decodeTimespec64(data)
            SyscallCapture.SCHEMA_SOCKADDR -> decodeSockaddr(data)
            else -> "{hex=${data.hexBytes()}}"
        }
    }

    private fun escapeBytes(data: ByteArray, stopAtNul: Boolean): String = buildString {
        for (value in data) {
            val byte = value.toInt() and 0xff
            if (byte == 0 && stopAtNul) break
            when (byte) {
                '\\'.code -> append("\\\\")
                '"'.code -> append("\\\"")
                '\n'.code -> append("\\n")
                '\r'.code -> append("\\r")
                '\t'.code -> append("\\t")
                in 0x20..0x7e -> append(byte.toChar())
                else -> append("\\x").append(byte.toString(16).padStart(2, '0'))
            }
        }
    }

    private fun decodeOpenHow(data: ByteArray): String {
        if (data.size < 8) return "{partial=true hex=${data.hexBytes()}}"
        val flags = data.readLe64(0).hexUnsigned()
        if (data.size < 16) return "{flags=0x$flags partial=true}"
        val mode = data.readLe64(8).octalUnsigned()
        if (data.size < 24) return "{flags=0x$flags mode=$mode partial=true}"
        val resolve = data.readLe64(16).hexUnsigned()
        return "{flags=0x$flags mode=$mode resolve=0x$resolve}"
    }

    private fun decodeTimespec64(data: ByteArray): String {
        if (data.size < 16) return "{partial=true hex=${data.hexBytes()}}"
        return "{tv_sec=${data.readLe64(0)} tv_nsec=${data.readLe64(8)}}"
    }

    private fun decodeStatx(data: ByteArray): String {
        if (data.size < 64) return "{partial=true hex=${data.hexBytes()}}"
        return buildString {
            append("{mask=0x").append(data.readLe32(0).toString(16))
            append(" blksize=").append(data.readLe32(4))
            append(" attributes=0x").append(data.readLe64(8).hexUnsigned())
            append(" nlink=").append(data.readLe32(16))
            append(" uid=").append(data.readLe32(20))
            append(" gid=").append(data.readLe32(24))
            append(" mode=0").append(data.readLe16(28).toString(8))
            append(" ino=").append(data.readLe64(32).decimalUnsigned())
            append(" size=").append(data.readLe64(40).decimalUnsigned())
            append(" blocks=").append(data.readLe64(48).decimalUnsigned())
            append(" attributes_mask=0x").append(data.readLe64(56).hexUnsigned())
            if (data.size >= 128) {
                append(" atime=").append(data.readLe64(64)).append('.')
                    .append(data.readLe32(72).toString().padStart(9, '0'))
                append(" btime=").append(data.readLe64(80)).append('.')
                    .append(data.readLe32(88).toString().padStart(9, '0'))
                append(" ctime=").append(data.readLe64(96)).append('.')
                    .append(data.readLe32(104).toString().padStart(9, '0'))
                append(" mtime=").append(data.readLe64(112)).append('.')
                    .append(data.readLe32(120).toString().padStart(9, '0'))
            }
            if (data.size >= 160) {
                append(" rdev=").append(data.readLe32(128)).append(':')
                    .append(data.readLe32(132))
                append(" dev=").append(data.readLe32(136)).append(':')
                    .append(data.readLe32(140))
                append(" mnt_id=").append(data.readLe64(144).decimalUnsigned())
                append(" dio_mem_align=").append(data.readLe32(152))
                append(" dio_offset_align=").append(data.readLe32(156))
            }
            append('}')
        }
    }

    private fun decodeSockaddr(data: ByteArray): String {
        if (data.size < 2) return "{partial=true hex=${data.hexBytes()}}"
        val family = data.readLe16(0)
        if (family == AF_UNIX) {
            if (data.size == 2) return "{family=AF_UNIX path=\"\"}"
            val abstract = data.u8(2) == 0
            val offset = if (abstract) 3 else 2
            val path = if (offset <= data.size) {
                escapeBytes(data.copyOfRange(offset, data.size), stopAtNul = !abstract)
            } else {
                ""
            }
            val label = if (abstract) "abstract" else "path"
            return "{family=AF_UNIX $label=\"$path\"}"
        }
        if (family == AF_INET && data.size >= 8) {
            val address = (4..7).joinToString(".") { data.u8(it).toString() }
            return "{family=AF_INET address=$address port=${data.readBe16(2)}}"
        }
        if (family == AF_INET6 && data.size >= 28) {
            val address = formatIpv6(data.copyOfRange(8, 24))
            return "{family=AF_INET6 address=$address port=${data.readBe16(2)} " +
                "flowinfo=${data.readBe32(4)} scope_id=${data.readLe32(24)}}"
        }
        if (family == AF_NETLINK && data.size >= 12) {
            return "{family=AF_NETLINK pid=${data.readLe32(4)} " +
                "groups=0x${data.readLe32(8).toString(16)}}"
        }
        return "{family=$family hex=${data.copyOfRange(2, data.size).hexBytes()}}"
    }

    private fun formatIpv6(address: ByteArray): String {
        val isIpv4Mapped = address.take(10).all { it == 0.toByte() } &&
            address.u8(10) == 0xff && address.u8(11) == 0xff
        if (isIpv4Mapped) {
            val ipv4 = (12..15).joinToString(".") { address.u8(it).toString() }
            return "::ffff:$ipv4"
        }

        val words = IntArray(8) { index -> address.readBe16(index * 2) }
        var bestStart = -1
        var bestLength = 0
        var currentStart = -1
        for (index in 0..words.size) {
            if (index < words.size && words[index] == 0) {
                if (currentStart < 0) currentStart = index
            } else if (currentStart >= 0) {
                val length = index - currentStart
                if (length > bestLength && length >= 2) {
                    bestStart = currentStart
                    bestLength = length
                }
                currentStart = -1
            }
        }
        if (bestStart < 0) return words.joinToString(":") { it.toString(16) }
        val left = words.take(bestStart).joinToString(":") { it.toString(16) }
        val right = words.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return when {
            left.isEmpty() && right.isEmpty() -> "::"
            left.isEmpty() -> "::$right"
            right.isEmpty() -> "$left::"
            else -> "$left::$right"
        }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.readLe16(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.readLe32(offset: Int): Long =
        u8(offset).toLong() or
            (u8(offset + 1).toLong() shl 8) or
            (u8(offset + 2).toLong() shl 16) or
            (u8(offset + 3).toLong() shl 24)

    private fun ByteArray.readLe64(offset: Int): Long =
        readLe32(offset) or (readLe32(offset + 4) shl 32)

    private fun ByteArray.readBe16(offset: Int): Int =
        (u8(offset) shl 8) or u8(offset + 1)

    private fun ByteArray.readBe32(offset: Int): Long =
        (u8(offset).toLong() shl 24) or
            (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or
            u8(offset + 3).toLong()

    private fun ByteArray.hexBytes(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun Long.hexUnsigned(): String = java.lang.Long.toUnsignedString(this, 16)

    private fun Long.decimalUnsigned(): String = java.lang.Long.toUnsignedString(this)

    private fun Long.octalUnsigned(): String {
        val digits = java.lang.Long.toUnsignedString(this, 8)
        return if (digits == "0") digits else "0$digits"
    }

    private fun align8(value: Int): Int = (value + 7) and 7.inv()

    private const val AF_UNIX = 1
    private const val AF_INET = 2
    private const val AF_INET6 = 10
    private const val AF_NETLINK = 16
}
