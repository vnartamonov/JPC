package org.jpc.emulator.motherboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the crash-proof LAPIC stub from increment 0004 (B3): reset
 * values match Intel SDM, dword R/W round-trips for read-write registers,
 * read-only registers ignore writes, and the MMIO window refuses code
 * execution.
 */
class LocalApicTest
{
    private LocalApic.LocalApicMemory mem;

    @BeforeEach
    void setUp()
    {
        mem = new LocalApic.LocalApicMemory();
    }

    @Test
    void blockSizeIs4Kb()
    {
        assertEquals(4096L, mem.getSize());
    }

    @Test
    void resetValueId()
    {
        assertEquals(0, mem.getDoubleWord(LocalApic.REG_ID));
    }

    @Test
    void resetValueVersion()
    {
        assertEquals(LocalApic.VERSION_VALUE, mem.getDoubleWord(LocalApic.REG_VERSION));
        assertEquals(0x00050014, mem.getDoubleWord(LocalApic.REG_VERSION));
    }

    @Test
    void resetValueSpuriousVector()
    {
        assertEquals(LocalApic.SVR_RESET, mem.getDoubleWord(LocalApic.REG_SVR));
        assertEquals(0x000000FF, mem.getDoubleWord(LocalApic.REG_SVR));
    }

    @Test
    void resetValueLogicalDestination()
    {
        assertEquals(LocalApic.LDR_RESET, mem.getDoubleWord(LocalApic.REG_LDR));
        assertEquals(0xFF000000, mem.getDoubleWord(LocalApic.REG_LDR));
    }

    @Test
    void resetValueDestinationFormat()
    {
        assertEquals(LocalApic.DFR_RESET, mem.getDoubleWord(LocalApic.REG_DFR));
        assertEquals(0xFFFFFFFF, mem.getDoubleWord(LocalApic.REG_DFR));
    }

    @Test
    void allLvtsResetMasked()
    {
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_TIMER));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_THERMAL));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_PERFCNT));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_LINT0));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_LINT1));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_ERROR));
    }

    @Test
    void taskPriorityRoundTrip()
    {
        mem.setDoubleWord(LocalApic.REG_TPR, 0x20);
        assertEquals(0x20, mem.getDoubleWord(LocalApic.REG_TPR));
    }

    @Test
    void icrLowAndHighRoundTrip()
    {
        mem.setDoubleWord(LocalApic.REG_ICR_LOW, 0x000000C5);
        mem.setDoubleWord(LocalApic.REG_ICR_HIGH, 0x01000000);
        assertEquals(0x000000C5, mem.getDoubleWord(LocalApic.REG_ICR_LOW));
        assertEquals(0x01000000, mem.getDoubleWord(LocalApic.REG_ICR_HIGH));
    }

    @Test
    void writeToVersionIsIgnored()
    {
        mem.setDoubleWord(LocalApic.REG_VERSION, 0xDEADBEEF);
        assertEquals(LocalApic.VERSION_VALUE, mem.getDoubleWord(LocalApic.REG_VERSION));
    }

    @Test
    void writeToEoiDoesNotCrash()
    {
        mem.setDoubleWord(LocalApic.REG_EOI, 0);
        // EOI is write-only with no observable effect in the stub.
        assertEquals(0, mem.getDoubleWord(LocalApic.REG_EOI));
    }

    @Test
    void byteAccessReadsLittleEndianBytes()
    {
        mem.setDoubleWord(LocalApic.REG_TPR, 0x11223344);
        assertEquals((byte) 0x44, mem.getByte(LocalApic.REG_TPR));
        assertEquals((byte) 0x33, mem.getByte(LocalApic.REG_TPR + 1));
        assertEquals((byte) 0x22, mem.getByte(LocalApic.REG_TPR + 2));
        assertEquals((byte) 0x11, mem.getByte(LocalApic.REG_TPR + 3));
    }

    @Test
    void byteWriteIsRecombinedIntoDword()
    {
        mem.setDoubleWord(LocalApic.REG_TPR, 0x11223344);
        mem.setByte(LocalApic.REG_TPR + 1, (byte) 0xAA);
        assertEquals(0x1122AA44, mem.getDoubleWord(LocalApic.REG_TPR));
    }

    @Test
    void executeRealThrows()
    {
        assertThrows(IllegalStateException.class, () -> mem.executeReal(null, 0));
    }

    @Test
    void executeProtectedThrows()
    {
        assertThrows(IllegalStateException.class, () -> mem.executeProtected(null, 0));
    }

    @Test
    void resetRestoresInitialValues()
    {
        mem.setDoubleWord(LocalApic.REG_TPR, 0x55);
        mem.setDoubleWord(LocalApic.REG_LVT_TIMER, 0);
        mem.clear();
        assertEquals(0, mem.getDoubleWord(LocalApic.REG_TPR));
        assertEquals(LocalApic.LVT_MASKED, mem.getDoubleWord(LocalApic.REG_LVT_TIMER));
    }

    @Test
    void componentInitiallyNotInitialised()
    {
        LocalApic apic = new LocalApic();
        // Without a PhysicalAddressSpace handed in, the component stays uninitialised.
        org.junit.jupiter.api.Assertions.assertEquals(false, apic.initialised());
    }
}
