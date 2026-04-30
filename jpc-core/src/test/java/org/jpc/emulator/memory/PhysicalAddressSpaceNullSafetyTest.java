package org.jpc.emulator.memory;

import org.jpc.emulator.PC;
import org.jpc.emulator.execution.codeblock.CodeBlockManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the {@code PhysicalAddressSpace.getMemoryBlockAt}
 * null-handling bug fixed in increment 0008.
 *
 * <p>The bug: a 32-bit physical address whose {@code TOP_INDEX} row had
 * already been allocated (because some neighbouring block in the same 4 MB
 * region was mapped — typically the LAPIC stub at 0xFEE00000 or the BIOS
 * shadow ROM at 0xFFFE0000) but whose {@code BOTTOM_INDEX} slot was never
 * mapped, would return {@code null} from {@code getMemoryBlockAt} instead
 * of the expected {@code UNCONNECTED} sentinel. Java's array semantics
 * (default-initialised slots are {@code null} but {@code arr[i]} access
 * does not throw {@link NullPointerException}) means the existing
 * {@code catch (NullPointerException)} branch only fired for the outer
 * row being null, never for an inner slot.
 *
 * <p>The null then propagated through {@code LinearAddressSpace}'s TLB
 * cache and produced {@code NullPointerException}s in opcode handlers
 * such as {@code mov_Gd_Ed_mem.execute}, hanging the executor thread.
 *
 * <p>This test class reconstructs the exact malicious memory map and
 * verifies that {@code getByte}/{@code getDoubleWord} return the
 * floating-high {@code 0xFF...FF} sentinel for the unmapped block,
 * never an {@code NullPointerException}.
 */
class PhysicalAddressSpaceNullSafetyTest
{
    private CodeBlockManager mgr;
    private PhysicalAddressSpace phys;

    @BeforeAll
    static void seedRamSize()
    {
        // 64 MB matches the NetBSD-boot scenario; the bug is independent of
        // the exact value but the test name and addresses below assume it.
        if (PC.SYS_RAM_SIZE == 0)
            PC.SYS_RAM_SIZE = 64 * 1024 * 1024;
    }

    @BeforeEach
    void setUp()
    {
        mgr = new CodeBlockManager();
        phys = new PhysicalAddressSpace(mgr);
    }

    /**
     * Maps a single 4 KB block at 0xFEE00000 (LAPIC) — exactly what
     * {@code LocalApic} from increment 0004 does. This populates the
     * top-row 0x3FB but leaves most bottom slots in that row unmapped.
     */
    private void mapLapicStubAt0xFEE00000()
    {
        Memory stub = new LazyCodeBlockMemory(AddressSpace.BLOCK_SIZE, mgr);
        phys.mapMemory(0xFEE00000, stub);
    }

    @Test
    void getByteFromUnmappedNeighbourReturnsFloatingHighNotNpe()
    {
        mapLapicStubAt0xFEE00000();

        // 0xFED40000 is in the same TOP_INDEX row (0x3FB) as 0xFEE00000
        // but a different BOTTOM_INDEX slot. Without the fix this would
        // bubble a null Memory reference into the caller and fail with
        // NullPointerException at the next get/set.
        byte b = phys.getByte(0xFED40000);
        assertEquals((byte) -1, b,
                "unmapped phys byte should read as floating-high 0xFF");
    }

    @Test
    void getDoubleWordFromUnmappedNeighbourReturnsFloatingHighNotNpe()
    {
        mapLapicStubAt0xFEE00000();

        int dw = phys.getDoubleWord(0xFED40000);
        assertEquals(-1, dw,
                "unmapped phys dword should read as 0xFFFFFFFF");
    }

    @Test
    void setByteOnUnmappedNeighbourIsSilentlyDiscarded()
    {
        mapLapicStubAt0xFEE00000();

        // Writing to an unmapped phys address must not NPE — the
        // UNCONNECTED block silently drops the data.
        assertDoesNotThrow(() -> phys.setByte(0xFED40000, (byte) 0x55));
        assertEquals((byte) -1, phys.getByte(0xFED40000),
                "follow-up read still floats high");
    }

    @Test
    void multipleUnmappedSlotsInSameRowAllReturnUnconnected()
    {
        mapLapicStubAt0xFEE00000();

        // Sweep several distinct bottom slots within the same top row.
        // Every one of them must read floating-high, no NPE.
        int[] probes = { 0xFEC00000, 0xFEC03000, 0xFED00000,
                          0xFED40000, 0xFEDFF000, 0xFEFFE000 };
        for (int addr : probes) {
            assertEquals(-1, phys.getDoubleWord(addr),
                    "phys dword at 0x" + Integer.toHexString(addr));
        }
    }

    @Test
    void unmappedAddressInUnseenTopRowAlsoReturnsUnconnected()
    {
        // No mapping at all in this row — the NPE path inside the existing
        // catch-block already handled this case; the test pins the
        // pre-existing behaviour so a regression in the new code does not
        // accidentally also break the all-null row case.
        int dw = phys.getDoubleWord(0x80000000);
        assertEquals(-1, dw);
    }

    @Test
    void quickIndexLowMemoryStillWorks()
    {
        // Within the SYS_RAM_SIZE region the quickIndex fast path is used.
        // The fix lives in the slow path; verify the fast path is
        // unaffected and reads zero from the freshly initialised RAM.
        assertEquals(0, phys.getDoubleWord(0x00100000));
    }
}
