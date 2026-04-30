package org.jpc.emulator.motherboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the crash-proof IO-APIC stub from increment 0004 (B3): the
 * IOREGSEL/IOWIN indirect access pattern, reset values for IOAPICID and
 * IOAPICVER, redirection table mask defaults, RTE round-trip, and
 * read-only behaviour for IOAPICVER/IOAPICARB.
 */
class IoApicTest
{
    private IoApic.IoApicMemory mem;

    @BeforeEach
    void setUp()
    {
        mem = new IoApic.IoApicMemory();
    }

    @Test
    void blockSizeIs4Kb()
    {
        assertEquals(4096L, mem.getSize());
    }

    @Test
    void readIoApicId()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICID);
        assertEquals(IoApic.IOAPICID_RESET, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
        assertEquals(0x02000000, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void readIoApicVersionEncodesEntryCount()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICVER);
        int v = mem.getDoubleWord(IoApic.OFFSET_IOWIN);
        assertEquals(IoApic.IOAPICVER_RESET, v);
        assertEquals(0x11, v & 0xFF, "version=0x11");
        assertEquals(IoApic.REDIRECTION_ENTRIES - 1, (v >>> 16) & 0xFF,
                "max redirection entry encoded as N-1");
    }

    @Test
    void readIoApicArb()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICARB);
        assertEquals(0, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void allRedirectionEntriesMaskedAtReset()
    {
        for (int e = 0; e < IoApic.REDIRECTION_ENTRIES; e++) {
            int idxLow = IoApic.IDX_REDTBL_BASE + e * 2;
            int idxHigh = idxLow + 1;
            mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, idxLow);
            assertEquals(IoApic.RTE_LOW_MASKED, mem.getDoubleWord(IoApic.OFFSET_IOWIN),
                    "RTE[" + e + "] low should be masked");
            mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, idxHigh);
            assertEquals(0, mem.getDoubleWord(IoApic.OFFSET_IOWIN),
                    "RTE[" + e + "] high should be zero");
        }
    }

    @Test
    void redirectionEntryWriteRoundTrip()
    {
        // Configure RTE0: vector 0x40, edge-triggered, masked.
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_REDTBL_BASE);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0x00010040);
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_REDTBL_BASE + 1);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0x00000000);

        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_REDTBL_BASE);
        assertEquals(0x00010040, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void writeToVersionIsIgnored()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICVER);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0xDEADBEEF);
        assertEquals(IoApic.IOAPICVER_RESET, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void writeToArbIsIgnored()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICARB);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0x12345678);
        assertEquals(0, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void ioregselRoundTrip()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, 0x42);
        assertEquals(0x42, mem.getDoubleWord(IoApic.OFFSET_IOREGSEL));
        assertEquals(0x42, mem.getIoRegSel());
    }

    @Test
    void resetRestoresInitialValues()
    {
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_REDTBL_BASE);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0xDEADBEEF);
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICID);
        mem.setDoubleWord(IoApic.OFFSET_IOWIN, 0x77000000);

        mem.clear();

        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_IOAPICID);
        assertEquals(IoApic.IOAPICID_RESET, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
        mem.setDoubleWord(IoApic.OFFSET_IOREGSEL, IoApic.IDX_REDTBL_BASE);
        assertEquals(IoApic.RTE_LOW_MASKED, mem.getDoubleWord(IoApic.OFFSET_IOWIN));
    }

    @Test
    void executeProtectedThrows()
    {
        assertThrows(IllegalStateException.class, () -> mem.executeProtected(null, 0));
    }
}
