package org.jpc.emulator.motherboard;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the byte layout, signatures, lengths, pointers and checksums of
 * the static ACPI 1.0 table set produced by {@link Acpi#buildAcpiTables()}.
 * If any of these fail, NetBSD/i386 (or Linux) will reject the table during
 * the early ACPI scan.
 */
class AcpiTest
{
    private static final byte[] TABLES = Acpi.buildAcpiTables();

    @Test
    void totalSizeMatchesPackedLayout()
    {
        assertEquals(Acpi.TOTAL_TABLE_BYTES, TABLES.length);
        assertEquals(0x133, TABLES.length);
    }

    // ---- RSDP ---------------------------------------------------------

    @Test
    void rsdpSignature()
    {
        // Note trailing space — exactly 8 ASCII bytes.
        assertArrayEquals("RSD PTR ".getBytes(StandardCharsets.US_ASCII),
                slice(TABLES, Acpi.RSDP_OFFSET, 8));
    }

    @Test
    void rsdpRevisionIsZeroForAcpi10()
    {
        assertEquals(0, TABLES[Acpi.RSDP_OFFSET + 15] & 0xFF);
    }

    @Test
    void rsdpPointsToRsdtAt0xFB020()
    {
        assertEquals(0xFB020L, readLe32(TABLES, Acpi.RSDP_OFFSET + 16));
        assertEquals(Acpi.BLOCK_BASE + Acpi.RSDT_OFFSET,
                (int) readLe32(TABLES, Acpi.RSDP_OFFSET + 16));
    }

    @Test
    void rsdpChecksum()
    {
        assertChecksum(TABLES, Acpi.RSDP_OFFSET, Acpi.RSDP_LENGTH, "RSDP");
    }

    @Test
    void rsdpInScannedRange()
    {
        // ACPI 1.0 spec: RSDP must be at 16-byte boundary in EBDA first 1KB
        // or in BIOS ROM 0xE0000-0xFFFFF.
        int addr = Acpi.BLOCK_BASE + Acpi.RSDP_OFFSET;
        assertTrue(addr >= 0xE0000 && addr <= 0xFFFFF,
                "RSDP at 0x" + Integer.toHexString(addr) + " must be in BIOS scan range");
        assertEquals(0, addr & 0xF, "RSDP must be 16-byte aligned");
    }

    // ---- RSDT ---------------------------------------------------------

    @Test
    void rsdtSignature()
    {
        assertArrayEquals("RSDT".getBytes(StandardCharsets.US_ASCII),
                slice(TABLES, Acpi.RSDT_OFFSET, 4));
    }

    @Test
    void rsdtLengthMatches()
    {
        assertEquals(Acpi.RSDT_LENGTH, (int) readLe32(TABLES, Acpi.RSDT_OFFSET + 4));
        assertEquals(44, (int) readLe32(TABLES, Acpi.RSDT_OFFSET + 4));
    }

    @Test
    void rsdtEntriesPointToMadtAndFadt()
    {
        long entry0 = readLe32(TABLES, Acpi.RSDT_OFFSET + Acpi.SDT_HEADER_LENGTH + 0);
        long entry1 = readLe32(TABLES, Acpi.RSDT_OFFSET + Acpi.SDT_HEADER_LENGTH + 4);
        assertEquals(0xFB050L, entry0);
        assertEquals(0xFB090L, entry1);
        assertEquals(Acpi.BLOCK_BASE + Acpi.MADT_OFFSET, (int) entry0);
        assertEquals(Acpi.BLOCK_BASE + Acpi.FADT_OFFSET, (int) entry1);
    }

    @Test
    void rsdtChecksum()
    {
        assertChecksum(TABLES, Acpi.RSDT_OFFSET, Acpi.RSDT_LENGTH, "RSDT");
    }

    // ---- MADT ---------------------------------------------------------

    @Test
    void madtSignature()
    {
        assertArrayEquals("APIC".getBytes(StandardCharsets.US_ASCII),
                slice(TABLES, Acpi.MADT_OFFSET, 4));
    }

    @Test
    void madtLengthMatches()
    {
        assertEquals(Acpi.MADT_LENGTH, (int) readLe32(TABLES, Acpi.MADT_OFFSET + 4));
        assertEquals(64, (int) readLe32(TABLES, Acpi.MADT_OFFSET + 4));
    }

    @Test
    void madtLocalApicAddress()
    {
        assertEquals(Acpi.LAPIC_PHYS_ADDR,
                (int) readLe32(TABLES, Acpi.MADT_OFFSET + Acpi.SDT_HEADER_LENGTH + 0));
        assertEquals(0xFEE00000,
                (int) readLe32(TABLES, Acpi.MADT_OFFSET + Acpi.SDT_HEADER_LENGTH + 0));
    }

    @Test
    void madtFlagsHavePcatCompat()
    {
        assertEquals(0x00000001,
                (int) readLe32(TABLES, Acpi.MADT_OFFSET + Acpi.SDT_HEADER_LENGTH + 4));
    }

    @Test
    void madtProcessorLocalApicEntry()
    {
        int p = Acpi.MADT_OFFSET + Acpi.SDT_HEADER_LENGTH + 8;
        assertEquals(0, TABLES[p] & 0xFF, "type=Processor LAPIC");
        assertEquals(8, TABLES[p + 1] & 0xFF, "length=8");
        assertEquals(Acpi.ACPI_PROCESSOR_ID, TABLES[p + 2]);
        assertEquals(Acpi.LAPIC_BSP_APIC_ID, TABLES[p + 3]);
        assertEquals(0x00000001, (int) readLe32(TABLES, p + 4), "flags=enabled");
    }

    @Test
    void madtIoApicEntry()
    {
        int p = Acpi.MADT_OFFSET + Acpi.SDT_HEADER_LENGTH + 8 + 8;
        assertEquals(1, TABLES[p] & 0xFF, "type=IO APIC");
        assertEquals(12, TABLES[p + 1] & 0xFF, "length=12");
        assertEquals(Acpi.IOAPIC_ID, TABLES[p + 2]);
        assertEquals(0, TABLES[p + 3] & 0xFF, "reserved");
        assertEquals(Acpi.IOAPIC_PHYS_ADDR, (int) readLe32(TABLES, p + 4));
        assertEquals(0, (int) readLe32(TABLES, p + 8), "GSI base=0");
    }

    @Test
    void madtChecksum()
    {
        assertChecksum(TABLES, Acpi.MADT_OFFSET, Acpi.MADT_LENGTH, "MADT");
    }

    // ---- FADT ---------------------------------------------------------

    @Test
    void fadtSignature()
    {
        assertArrayEquals("FACP".getBytes(StandardCharsets.US_ASCII),
                slice(TABLES, Acpi.FADT_OFFSET, 4));
    }

    @Test
    void fadtLengthMatches()
    {
        assertEquals(Acpi.FADT_LENGTH, (int) readLe32(TABLES, Acpi.FADT_OFFSET + 4));
        assertEquals(116, (int) readLe32(TABLES, Acpi.FADT_OFFSET + 4));
    }

    @Test
    void fadtPointsToDsdt()
    {
        assertEquals(0xFB108L, readLe32(TABLES, Acpi.FADT_OFFSET + 40));
        assertEquals(Acpi.BLOCK_BASE + Acpi.DSDT_OFFSET,
                (int) readLe32(TABLES, Acpi.FADT_OFFSET + 40));
    }

    @Test
    void fadtIntModelIsDualPic()
    {
        assertEquals(0, TABLES[Acpi.FADT_OFFSET + 44] & 0xFF);
    }

    @Test
    void fadtSciInt()
    {
        // SCI_INT at offset 46, LE16
        assertEquals(9,
                (TABLES[Acpi.FADT_OFFSET + 46] & 0xFF)
                        | ((TABLES[Acpi.FADT_OFFSET + 47] & 0xFF) << 8));
    }

    @Test
    void fadtChecksum()
    {
        assertChecksum(TABLES, Acpi.FADT_OFFSET, Acpi.FADT_LENGTH, "FADT");
    }

    // ---- DSDT ---------------------------------------------------------

    @Test
    void dsdtSignature()
    {
        assertArrayEquals("DSDT".getBytes(StandardCharsets.US_ASCII),
                slice(TABLES, Acpi.DSDT_OFFSET, 4));
    }

    @Test
    void dsdtLengthMatches()
    {
        assertEquals(Acpi.DSDT_LENGTH, (int) readLe32(TABLES, Acpi.DSDT_OFFSET + 4));
        assertEquals(43, (int) readLe32(TABLES, Acpi.DSDT_OFFSET + 4));
    }

    @Test
    void dsdtAmlBodyEncodesEmptyScopeUnderscoreSb()
    {
        byte[] expected = {
                (byte) 0x10,  // ScopeOp
                (byte) 0x06,  // PkgLength
                (byte) 0x5C,  // RootChar '\'
                (byte) 0x5F, (byte) 0x53, (byte) 0x42, (byte) 0x5F  // "_SB_"
        };
        assertArrayEquals(expected,
                slice(TABLES, Acpi.DSDT_OFFSET + Acpi.SDT_HEADER_LENGTH, Acpi.DSDT_AML_LENGTH));
    }

    @Test
    void dsdtChecksum()
    {
        assertChecksum(TABLES, Acpi.DSDT_OFFSET, Acpi.DSDT_LENGTH, "DSDT");
    }

    // ---- Cross-table consistency -------------------------------------

    @Test
    void madtAndFadtAgreeOnApicAddresses()
    {
        // Acpi MADT must agree with the addresses we wired up in 0004 (LocalApic, IoApic).
        assertEquals(LocalApic.BASE_ADDRESS, Acpi.LAPIC_PHYS_ADDR);
        assertEquals(IoApic.BASE_ADDRESS, Acpi.IOAPIC_PHYS_ADDR);
    }

    @Test
    void allHeaderRevisionsAreOne()
    {
        assertEquals(1, TABLES[Acpi.RSDT_OFFSET + 8] & 0xFF, "RSDT rev");
        assertEquals(1, TABLES[Acpi.MADT_OFFSET + 8] & 0xFF, "MADT rev");
        assertEquals(1, TABLES[Acpi.FADT_OFFSET + 8] & 0xFF, "FADT rev");
        assertEquals(1, TABLES[Acpi.DSDT_OFFSET + 8] & 0xFF, "DSDT rev");
    }

    // ---- helpers ------------------------------------------------------

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

    private static byte[] slice(byte[] data, int off, int len)
    {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }
}
