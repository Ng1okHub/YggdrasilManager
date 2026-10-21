package ng1ok.yggdrasilmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ng1ok.yggdrasilmanager.data.selftest.SelfTestFeature
import ng1ok.yggdrasilmanager.data.selftest.SelfTestLogKind
import ng1ok.yggdrasilmanager.data.selftest.SelfTestOutputParser
import ng1ok.yggdrasilmanager.data.selftest.SelfTestStatus

class SelfTestOutputParserTest {
    @Test
    fun `sections and checks map to per-feature statuses`() {
        val parser = SelfTestOutputParser()

        parser.accept("")
        assertEquals(SelfTestLogKind.SECTION, parser.accept("== feature: core =="))
        assertEquals(SelfTestLogKind.PASS, parser.accept("[ ok ] core: available"))
        assertEquals(SelfTestLogKind.PASS, parser.accept("[ ok ] core: get_version matches header"))
        assertEquals(SelfTestLogKind.SECTION, parser.accept("== feature: maps =="))
        assertEquals(SelfTestLogKind.PASS, parser.accept("[ ok ] maps: fixture file"))
        assertEquals(
            SelfTestLogKind.FAIL,
            parser.accept("[FAIL] maps: maps hides record (line 980: got 1 want 0 errno 0(Success))"),
        )
        parser.accept("summary: 4 checks, 1 failed")
        parser.accept("RESULT: FAIL (1)")
        parser.finish()

        val statuses = parser.statusesSnapshot()
        assertEquals(SelfTestStatus.PASSED, statuses["core"])
        assertEquals(SelfTestStatus.FAILED, statuses["maps"])
        assertEquals(4, parser.checks)
        assertEquals(1, parser.failed)
        assertNull(parser.fatal)
    }

    @Test
    fun `summary line replaces locally counted checks`() {
        val parser = SelfTestOutputParser()

        parser.accept("== feature: hwbp ==")
        parser.accept("[ ok ] hwbp: trampoline ran")
        assertEquals(1, parser.checks)

        parser.accept("summary: 9 checks, 0 failed")
        parser.accept("ALL PASS")
        parser.finish()

        assertEquals(9, parser.checks)
        assertEquals(0, parser.failed)
        assertEquals(SelfTestStatus.PASSED, parser.statusesSnapshot()["hwbp"])
    }

    @Test
    fun `root-only sections can be skipped`() {
        val parser = SelfTestOutputParser()

        assertEquals(
            SelfTestLogKind.INFO,
            parser.accept("== syscall: SKIPPED (needs root) =="),
        )
        parser.finish()

        assertEquals(SelfTestStatus.SKIPPED, parser.statusesSnapshot()["syscall"])
    }

    @Test
    fun `fatal availability failure is surfaced`() {
        val parser = SelfTestOutputParser()

        assertEquals(
            SelfTestLogKind.ERROR,
            parser.accept("FATAL: Yggdrasil module not available (prctl not hooked)."),
        )
        parser.finish()

        assertNotNull(parser.fatal)
        assertEquals(0, parser.checks)
    }

    @Test
    fun `feature ids stay aligned with the native filter names`() {
        assertEquals(
            listOf(
                "core",
                "memfd",
                "merge",
                "maps",
                "dbg",
                "ptrace",
                "label",
                "hwbp",
                "syscall",
                "stramp",
                "freeze",
            ),
            SelfTestFeature.entries.map { it.id },
        )
        assertTrue(SelfTestFeature.entries.filter { it.needsRoot }.map { it.id }.containsAll(
            listOf("syscall", "stramp", "freeze"),
        ))
        assertEquals(SelfTestFeature.Freeze, SelfTestFeature.fromId("freeze"))
        assertNull(SelfTestFeature.fromId("unknown"))
    }
}
