package ng1ok.yggdrasilmanager

import ng1ok.yggdrasilmanager.data.model.Arm64SyscallCatalog
import ng1ok.yggdrasilmanager.data.model.SyscallAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Arm64SyscallCatalogTest {
    @Test
    fun catalogCoversEveryOfferedNumberExactlyOnce() {
        val entries = Arm64SyscallCatalog.entries(emptySet())

        assertEquals(
            Arm64SyscallCatalog.SELECTOR_LAST - Arm64SyscallCatalog.SELECTOR_FIRST + 1,
            entries.size,
        )
        assertEquals(
            (Arm64SyscallCatalog.SELECTOR_FIRST..Arm64SyscallCatalog.SELECTOR_LAST).toList(),
            entries.map { it.nr }.sorted(),
        )
    }

    @Test
    fun namedSyscallsMatchTheArm64Abi() {
        // arm64 numbering differs from x86: 63 is read, 66 is writev, 62 is lseek.
        assertEquals("read", Arm64SyscallCatalog.nameFor(63))
        assertEquals("writev", Arm64SyscallCatalog.nameFor(66))
        assertEquals("lseek", Arm64SyscallCatalog.nameFor(62))
        assertEquals("openat", Arm64SyscallCatalog.nameFor(56))
        assertEquals("newfstatat", Arm64SyscallCatalog.nameFor(79))
        assertEquals("statx", Arm64SyscallCatalog.nameFor(291))
        assertEquals("syscall_9999", Arm64SyscallCatalog.nameFor(9999))
    }

    @Test
    fun unimplementedNumbersCarryTheirReason() {
        // Measured with a fork-per-syscall ENOSYS probe on arm64 kernel 5.10.249.
        val unavailable = Arm64SyscallCatalog.entries(emptySet())
            .filter { it.availability != SyscallAvailability.AVAILABLE }
            .associate { it.nr to it.availability }

        assertEquals(49, unavailable.size)
        assertEquals(SyscallAvailability.ARCH_UNSUPPORTED, unavailable[42])
        assertEquals(SyscallAvailability.ARCH_UNSUPPORTED, unavailable[244])
        assertEquals(SyscallAvailability.ARCH_UNSUPPORTED, unavailable[259])
        assertEquals(SyscallAvailability.KERNEL_DISABLED, unavailable[180])
        assertEquals(SyscallAvailability.KERNEL_DISABLED, unavailable[288])
        assertEquals(null, unavailable[63])
        assertEquals(null, unavailable[291])
    }

    @Test
    fun argumentSchemasAndEnterOnlyPhasesAreFlagged() {
        val entries = Arm64SyscallCatalog.entries(setOf(56, 291)).associateBy { it.nr }

        // The schema table drives the picker's "auto argument parsing" marker.
        assertTrue(entries.getValue(56).hasArgumentSchema)
        assertTrue(entries.getValue(291).hasArgumentSchema)
        assertEquals(false, entries.getValue(63).hasArgumentSchema)

        // exit / exit_group never return, so the exit phase cannot be observed.
        assertTrue(entries.getValue(93).enterOnly)
        assertTrue(entries.getValue(94).enterOnly)
        assertEquals(false, entries.getValue(63).enterOnly)
    }

    @Test
    fun entriesAreOrderedWithObservableSyscallsFirst() {
        val entries = Arm64SyscallCatalog.entries(emptySet())
        val ranks = entries.map { it.availability.ordinal }

        assertEquals(ranks.sorted(), ranks)
        assertEquals(SyscallAvailability.AVAILABLE, entries.first().availability)
    }
}
