package ng1ok.yggdrasilmanager

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import ng1ok.yggdrasilmanager.data.MapsDumpCodec
import ng1ok.yggdrasilmanager.data.model.MapsPolicy
import ng1ok.yggdrasilmanager.data.model.MapsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsPolicyTest {
    @Test
    fun dumpCodecDecodesRulesAndScope() {
        val bytes = ByteBuffer.allocate(24 + 168).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(24)
            putInt(2)
            putInt(1)
            putInt(1)
            putInt(1)
            putInt(0)
            putInt(MapsPolicy.ACTION_HIDE)
            putInt(MapsPolicy.MATCH_PATH_SUBSTR)
            putInt(MapsPolicy.RULE_F_MATCH_PERMS)
            putInt(0)
            put("r-x\u0000".toByteArray())
            put(ByteArray(4))
            putLong(0)
            putLong(0)
            put("libfoo\u0000".toByteArray())
            put(ByteArray(121))
        }.array()

        val scope = MapsDumpCodec.decode(bytes)
        requireNotNull(scope)
        assertEquals(true, scope.global)
        assertEquals(1, scope.totalRules)
        assertEquals(MapsRule(MapsPolicy.ACTION_HIDE, MapsPolicy.MATCH_PATH_SUBSTR, "r-x", null, pattern = "libfoo"), scope.rules.single())
    }

    @Test
    fun dumpCodecRejectsInvalidHeader() {
        assertNull(MapsDumpCodec.decode(ByteArray(24)))
    }
}
