package org.jpc.emulator.motherboard;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the byte layout, signatures and checksums of the static Intel MP
 * 1.4 table produced by {@link MpTable#buildMpTable()}. These properties must
 * hold for NetBSD/i386 and Linux to accept the table during early SMP probe.
 */
class MpTableTest
{
    private static final byte[] TABLE = MpTable.buildMpTable();

    @Test
    void tableHasExactSize()
    {
        assertEquals(MpTable.MP_TOTAL_LENGTH, TABLE.length);
        assertEquals(240, TABLE.length);
    }

    @Test
    void mpfpsSignatureUnderscoreMpUnderscore()
    {
        assertArrayEquals(
                new byte[]{'_', 'M', 'P', '_'},
                slice(TABLE, MpTable.MPFPS_OFFSET, 4));
    }

    @Test
    void mpfpsLengthByteIsOne()
    {
        // length / 16 — the floating-pointer structure is exactly 16 bytes.
        assertEquals(1, TABLE[MpTable.MPFPS_OFFSET + 8] & 0xFF);
    }

    @Test
    void mpfpsSpecRevIs1Dot4()
    {
        assertEquals(0x04, TABLE[MpTable.MPFPS_OFFSET + 9] & 0xFF);
    }

    @Test
    void mpfpsPointsToMpcAt0xFC010()
    {
        long pointer = readLe32(TABLE, MpTable.MPFPS_OFFSET + 4);
        assertEquals(0xFC010L, pointer);
        assertEquals(MpTable.BLOCK_BASE + MpTable.MPC_OFFSET, (int) pointer);
    }

    @Test
    void mpfpsFeatureByte1IsZeroMeaningMpcPresent()
    {
        assertEquals(0, TABLE[MpTable.MPFPS_OFFSET + 11] & 0xFF);
    }

    @Test
    void mpfpsChecksumIsZeroSum()
    {
        assertChecksum(TABLE, MpTable.MPFPS_OFFSET, MpTable.MPFPS_LENGTH, "MPFPS");
    }

    @Test
    void mpcSignaturePcmp()
    {
        assertArrayEquals(
                new byte[]{'P', 'C', 'M', 'P'},
                slice(TABLE, MpTable.MPC_OFFSET, 4));
    }

    @Test
    void mpcBaseTableLengthMatches224()
    {
        int len = readLe16(TABLE, MpTable.MPC_OFFSET + 4);
        assertEquals(MpTable.MPC_LENGTH, len);
        assertEquals(224, len);
    }

    @Test
    void mpcSpecRevIs1Dot4()
    {
        assertEquals(0x04, TABLE[MpTable.MPC_OFFSET + 6] & 0xFF);
    }

    @Test
    void mpcOemAndProductIdAreJpc()
    {
        String oem = new String(TABLE, MpTable.MPC_OFFSET + 8, 8, StandardCharsets.US_ASCII);
        String product = new String(TABLE, MpTable.MPC_OFFSET + 16, 12, StandardCharsets.US_ASCII);
        assertEquals("JPC     ", oem);
        assertEquals("JPC i386    ", product);
    }

    @Test
    void mpcEntryCountIs21()
    {
        assertEquals(MpTable.TOTAL_ENTRIES, readLe16(TABLE, MpTable.MPC_OFFSET + 34));
        assertEquals(21, readLe16(TABLE, MpTable.MPC_OFFSET + 34));
    }

    @Test
    void mpcLapicAddressIsFee00000()
    {
        assertEquals(MpTable.LAPIC_PHYS_ADDR, (int) readLe32(TABLE, MpTable.MPC_OFFSET + 36));
        assertEquals(0xFEE00000, (int) readLe32(TABLE, MpTable.MPC_OFFSET + 36));
    }

    @Test
    void mpcChecksumIsZeroSum()
    {
        assertChecksum(TABLE, MpTable.MPC_OFFSET, MpTable.MPC_LENGTH, "MPC");
    }

    @Test
    void firstEntryIsBspProcessorEnabled()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH;
        assertEquals(0, TABLE[p] & 0xFF, "type=Processor");
        assertEquals(MpTable.LAPIC_BSP_ID, TABLE[p + 1]);
        assertEquals(MpTable.LAPIC_VERSION, TABLE[p + 2]);
        assertEquals(0x03, TABLE[p + 3] & 0xFF, "flags: enabled+BSP");
        assertEquals(MpTable.CPU_SIGNATURE_PII, (int) readLe32(TABLE, p + 4));
    }

    @Test
    void busEntryIsIsa()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH + MpTable.PROCESSOR_ENTRY_LENGTH;
        assertEquals(1, TABLE[p] & 0xFF, "type=Bus");
        assertEquals(MpTable.ISA_BUS_ID, TABLE[p + 1]);
        String bus = new String(TABLE, p + 2, 6, StandardCharsets.US_ASCII);
        assertEquals("ISA   ", bus);
    }

    @Test
    void ioApicEntryAt0xFEC00000()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH
                + MpTable.PROCESSOR_ENTRY_LENGTH
                + MpTable.BUS_ENTRY_LENGTH;
        assertEquals(2, TABLE[p] & 0xFF, "type=IOAPIC");
        assertEquals(MpTable.IOAPIC_ID, TABLE[p + 1]);
        assertEquals(MpTable.IOAPIC_VERSION, TABLE[p + 2]);
        assertEquals(0x01, TABLE[p + 3] & 0xFF, "enabled");
        assertEquals(MpTable.IOAPIC_PHYS_ADDR, (int) readLe32(TABLE, p + 4));
    }

    @Test
    void ioInterruptCountIs16AndAllAreType3()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH
                + MpTable.PROCESSOR_ENTRY_LENGTH
                + MpTable.BUS_ENTRY_LENGTH
                + MpTable.IOAPIC_ENTRY_LENGTH;
        for (int i = 0; i < MpTable.IOAPIC_INT_ENTRIES; i++) {
            int entry = p + i * MpTable.IOINT_ENTRY_LENGTH;
            assertEquals(3, TABLE[entry] & 0xFF,
                    "IO interrupt entry #" + i + " should be type 3");
            assertEquals(MpTable.ISA_BUS_ID, TABLE[entry + 4],
                    "source bus for entry #" + i);
            assertEquals(i, TABLE[entry + 5] & 0xFF,
                    "source IRQ for entry #" + i);
            assertEquals(MpTable.IOAPIC_ID, TABLE[entry + 6]);
        }
    }

    @Test
    void timerIrq0RoutesToIoApicIntin2()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH
                + MpTable.PROCESSOR_ENTRY_LENGTH
                + MpTable.BUS_ENTRY_LENGTH
                + MpTable.IOAPIC_ENTRY_LENGTH; // first IO INT entry is IRQ 0
        assertEquals(2, TABLE[p + 7] & 0xFF, "IRQ 0 should route to INTIN 2");
    }

    @Test
    void localInterruptEntriesArePresent()
    {
        int p = MpTable.MPC_OFFSET + MpTable.MPC_HEADER_LENGTH
                + MpTable.PROCESSOR_ENTRY_LENGTH
                + MpTable.BUS_ENTRY_LENGTH
                + MpTable.IOAPIC_ENTRY_LENGTH
                + MpTable.IOAPIC_INT_ENTRIES * MpTable.IOINT_ENTRY_LENGTH;

        // LINT0 = ExtINT (8259 PIC pass-through)
        assertEquals(4, TABLE[p] & 0xFF);
        assertEquals(3, TABLE[p + 1] & 0xFF, "interrupt type ExtINT");
        assertEquals((byte) 0xFF, TABLE[p + 6], "destination = all CPUs");
        assertEquals(0, TABLE[p + 7] & 0xFF, "LINT0");

        // LINT1 = NMI
        p += MpTable.LINT_ENTRY_LENGTH;
        assertEquals(4, TABLE[p] & 0xFF);
        assertEquals(1, TABLE[p + 1] & 0xFF, "interrupt type NMI");
        assertEquals((byte) 0xFF, TABLE[p + 6]);
        assertEquals(1, TABLE[p + 7] & 0xFF, "LINT1");
    }

    @Test
    void mpfpsLandsInScannedBiosRomRange()
    {
        // Intel MP spec: floating pointer must be in EBDA, last 1KB of base mem,
        // or BIOS ROM 0xF0000-0xFFFFF. Verify our base address is in the BIOS range.
        assertTrue(MpTable.BLOCK_BASE >= 0xF0000,
                "BLOCK_BASE 0x" + Integer.toHexString(MpTable.BLOCK_BASE)
                        + " must be in the BIOS ROM scan range 0xF0000-0xFFFFF");
        assertTrue(MpTable.BLOCK_BASE + MpTable.MPFPS_OFFSET <= 0xFFFF0,
                "MPFPS must fit before 0xFFFFF (it's a 16-byte structure)");
    }

    private static void assertChecksum(byte[] data, int off, int len, String label)
    {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[off + i] & 0xFF;
        assertEquals(0, sum & 0xFF,
                label + " checksum should make sum-of-bytes ≡ 0 mod 256, got 0x"
                        + Integer.toHexString(sum & 0xFF));
    }

    private static long readLe32(byte[] data, int off)
    {
        return (data[off] & 0xFFL)
                | ((data[off + 1] & 0xFFL) << 8)
                | ((data[off + 2] & 0xFFL) << 16)
                | ((data[off + 3] & 0xFFL) << 24);
    }

    private static int readLe16(byte[] data, int off)
    {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }

    private static byte[] slice(byte[] data, int off, int len)
    {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }
}
