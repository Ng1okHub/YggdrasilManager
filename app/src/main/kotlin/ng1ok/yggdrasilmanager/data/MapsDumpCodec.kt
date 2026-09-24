package ng1ok.yggdrasilmanager.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import ng1ok.yggdrasilmanager.data.model.MapsScope

/** Decodes the packed little-endian ygg_maps_dump returned by JNI. */
internal object MapsDumpCodec {
    fun decode(bytes: ByteArray?): MapsScope? {
        if (bytes == null || bytes.size < DUMP_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dumpSize = buffer.int
        val version = buffer.int
        val global = buffer.int != 0
        val totalRules = buffer.int
        val copiedRules = buffer.int
        buffer.int
        if (dumpSize < DUMP_SIZE || version != MAPS_ABI_VERSION) return null

        val count = copiedRules.coerceIn(0, MapsPolicy.MAX_RULES)
        val available = ((bytes.size - DUMP_SIZE) / RULE_SIZE).coerceAtLeast(0)
        val safeCount = count.coerceAtMost(available)
        val rules = ArrayList<MapsRule>(safeCount)
        repeat(safeCount) {
            val action = buffer.int
            val matchKind = buffer.int
            val flags = buffer.int
            buffer.int
            val matchPerms = readCString(buffer, 4)
            val setPerms = readCString(buffer, 4)
            val addrStart = buffer.long
            val addrEnd = buffer.long
            val pattern = readCString(buffer, MapsPolicy.MAX_PATTERN_LENGTH + 1)
            rules += MapsRule(
                action = action,
                matchKind = matchKind,
                matchPerms = matchPerms.takeUnless { flags and MapsPolicy.RULE_F_MATCH_PERMS == 0 },
                setPerms = setPerms.takeUnless { action != MapsPolicy.ACTION_REWRITE_PERMS },
                addrStart = addrStart,
                addrEnd = addrEnd,
                pattern = pattern,
            )
        }
        return MapsScope(global, rules, totalRules.coerceAtLeast(0))
    }

    private fun readCString(buffer: ByteBuffer, length: Int): String {
        val bytes = ByteArray(length)
        buffer.get(bytes)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.copyOf(end).toString(Charsets.UTF_8)
    }

    private const val MAPS_ABI_VERSION = 2
    private const val DUMP_SIZE = 24
    private const val RULE_SIZE = 168
}
