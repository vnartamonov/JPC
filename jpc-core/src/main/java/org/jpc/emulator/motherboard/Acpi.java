package org.jpc.emulator.motherboard;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.HardwareComponent;
import org.jpc.emulator.memory.AddressSpace;
import org.jpc.emulator.memory.EPROMMemory;
import org.jpc.emulator.memory.PhysicalAddressSpace;

/**
 * Builds a minimal ACPI 1.0 table set (RSDP, RSDT, MADT, FACP, DSDT) and
 * overlays it into BIOS shadow ROM at physical address 0xFB000. NetBSD/i386
 * and Linux scan {@code 0xE0000-0xFFFFF} for the {@code "RSD PTR "}
 * signature on early boot; placing the table here lets them parse the
 * (single-CPU, single IO-APIC) topology declared by JPC via ACPI in
 * preference to the MP-table from increment 0003.
 *
 * <p>The DSDT body is just an empty {@code Scope (\_SB) {}} — syntactically
 * valid AML that lets the parser walk the namespace without finding
 * devices. Power management, GPE handling, and device enumeration are not
 * implemented; they are separate increments.
 *
 * <p>Layout inside the 4 KB block at 0xFB000:
 * <pre>
 *   0x000  RSDP  (20 bytes)
 *   0x020  RSDT  (44 bytes, header + 2 pointers)
 *   0x050  MADT  (64 bytes, header + LAPIC addr/flags + 2 entries)
 *   0x090  FACP  (116 bytes, ACPI 1.0 FADT)
 *   0x108  DSDT  (43 bytes, header + 7-byte AML body)
 *   0x133+ original BIOS bytes
 * </pre>
 */
public class Acpi extends AbstractHardwareComponent
{
    private static final Logger LOGGING = Logger.getLogger(Acpi.class.getName());

    public static final int BLOCK_BASE = 0xFB000;

    public static final int RSDP_OFFSET = 0x000;
    public static final int RSDT_OFFSET = 0x020;
    public static final int MADT_OFFSET = 0x050;
    public static final int FADT_OFFSET = 0x090;
    public static final int DSDT_OFFSET = 0x108;

    public static final int RSDP_LENGTH = 20;
    public static final int SDT_HEADER_LENGTH = 36;
    public static final int RSDT_LENGTH = SDT_HEADER_LENGTH + 4 * 2;   // 44
    public static final int MADT_LENGTH = SDT_HEADER_LENGTH + 4 + 4 + 8 + 12; // 64
    public static final int FADT_LENGTH = 116;
    public static final int DSDT_AML_LENGTH = 7;
    public static final int DSDT_LENGTH = SDT_HEADER_LENGTH + DSDT_AML_LENGTH; // 43

    public static final int TOTAL_TABLE_BYTES = DSDT_OFFSET + DSDT_LENGTH; // 0x133 = 307

    public static final int LAPIC_PHYS_ADDR = 0xFEE00000;
    public static final int IOAPIC_PHYS_ADDR = 0xFEC00000;
    public static final byte ACPI_PROCESSOR_ID = 0;
    public static final byte LAPIC_BSP_APIC_ID = 0;
    public static final byte IOAPIC_ID = 2;

    public static final byte[] OEM_ID            = ascii("JPCJPC", 6);
    public static final byte[] OEM_TABLE_ID      = ascii("JPCi386 ", 8);
    public static final int    OEM_REVISION      = 1;
    public static final byte[] CREATOR_ID        = ascii("JPC ", 4);
    public static final int    CREATOR_REVISION  = 1;

    private boolean loaded;

    public Acpi()
    {
        loaded = false;
    }

    @Override
    public boolean initialised()
    {
        return loaded;
    }

    @Override
    public boolean updated()
    {
        return loaded;
    }

    @Override
    public void reset()
    {
        loaded = false;
    }

    @Override
    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.initialised() && !loaded) {
            installOverlay((PhysicalAddressSpace) component);
            loaded = true;
        }
    }

    @Override
    public void updateComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.updated() && !loaded) {
            installOverlay((PhysicalAddressSpace) component);
            loaded = true;
        }
    }

    private void installOverlay(PhysicalAddressSpace addressSpace)
    {
        byte[] block = new byte[AddressSpace.BLOCK_SIZE];
        addressSpace.copyContentsIntoArray(BLOCK_BASE, block, 0, AddressSpace.BLOCK_SIZE);

        byte[] tables = buildAcpiTables();
        System.arraycopy(tables, 0, block, 0, tables.length);

        EPROMMemory eprom = new EPROMMemory(block, 0, AddressSpace.BLOCK_SIZE,
                addressSpace.getCodeBlockManager());
        addressSpace.mapMemory(BLOCK_BASE, eprom);
        LOGGING.log(Level.INFO,
                "Installed ACPI overlay at 0x{0} (RSDP=0x{1}, RSDT=0x{2}, FADT=0x{3}, DSDT=0x{4})",
                new Object[]{
                        Integer.toHexString(BLOCK_BASE),
                        Integer.toHexString(BLOCK_BASE + RSDP_OFFSET),
                        Integer.toHexString(BLOCK_BASE + RSDT_OFFSET),
                        Integer.toHexString(BLOCK_BASE + FADT_OFFSET),
                        Integer.toHexString(BLOCK_BASE + DSDT_OFFSET)
                });
    }

    /**
     * Builds the full ACPI table set as a single packed byte array.
     * Length = {@link #TOTAL_TABLE_BYTES}. Public for unit tests.
     */
    public static byte[] buildAcpiTables()
    {
        byte[] out = new byte[TOTAL_TABLE_BYTES];

        writeRsdp(out, RSDP_OFFSET, BLOCK_BASE + RSDT_OFFSET);
        writeRsdt(out, RSDT_OFFSET,
                BLOCK_BASE + MADT_OFFSET,
                BLOCK_BASE + FADT_OFFSET);
        writeMadt(out, MADT_OFFSET);
        writeFadt(out, FADT_OFFSET, BLOCK_BASE + DSDT_OFFSET);
        writeDsdt(out, DSDT_OFFSET);

        return out;
    }

    private static void writeRsdp(byte[] out, int off, int rsdtAddr)
    {
        // Signature "RSD PTR " (note trailing space — exactly 8 bytes).
        writeAscii(out, off + 0, "RSD PTR ", 8);
        out[off + 8] = 0; // checksum placeholder
        System.arraycopy(OEM_ID, 0, out, off + 9, 6);
        out[off + 15] = 0; // revision = 0 means ACPI 1.0
        writeLe32(out, off + 16, rsdtAddr);
        out[off + 8] = checksum(out, off, RSDP_LENGTH);
    }

    private static void writeRsdt(byte[] out, int off, int madtAddr, int fadtAddr)
    {
        writeSdtHeader(out, off, "RSDT", RSDT_LENGTH, 1);
        writeLe32(out, off + SDT_HEADER_LENGTH + 0, madtAddr);
        writeLe32(out, off + SDT_HEADER_LENGTH + 4, fadtAddr);
        finaliseChecksum(out, off, RSDT_LENGTH);
    }

    private static void writeMadt(byte[] out, int off)
    {
        writeSdtHeader(out, off, "APIC", MADT_LENGTH, 1);
        // After header: Local APIC addr (4) + flags (4)
        writeLe32(out, off + SDT_HEADER_LENGTH + 0, LAPIC_PHYS_ADDR);
        writeLe32(out, off + SDT_HEADER_LENGTH + 4, 0x00000001); // PCAT_COMPAT

        int p = off + SDT_HEADER_LENGTH + 8;

        // Processor Local APIC (type 0, length 8)
        out[p + 0] = 0;                       // type
        out[p + 1] = 8;                       // length
        out[p + 2] = ACPI_PROCESSOR_ID;
        out[p + 3] = LAPIC_BSP_APIC_ID;
        writeLe32(out, p + 4, 0x00000001);    // flags: enabled
        p += 8;

        // IO APIC (type 1, length 12)
        out[p + 0]  = 1;                      // type
        out[p + 1]  = 12;                     // length
        out[p + 2]  = IOAPIC_ID;
        out[p + 3]  = 0;                      // reserved
        writeLe32(out, p + 4, IOAPIC_PHYS_ADDR);
        writeLe32(out, p + 8, 0);             // GSI base
        p += 12;

        if (p - off != MADT_LENGTH) {
            throw new IllegalStateException("MADT layout mismatch: " + (p - off) + " vs " + MADT_LENGTH);
        }
        finaliseChecksum(out, off, MADT_LENGTH);
    }

    private static void writeFadt(byte[] out, int off, int dsdtAddr)
    {
        writeSdtHeader(out, off, "FACP", FADT_LENGTH, 1);
        // The remaining 80 bytes default to zero; we only set the few that matter.
        writeLe32(out, off + 36, 0);          // FIRMWARE_CTRL — no FACS
        writeLe32(out, off + 40, dsdtAddr);   // DSDT
        out[off + 44] = 0;                    // INT_MODEL = dual 8259 PIC
        out[off + 45] = 0;                    // reserved
        writeLe16(out, off + 46, 9);          // SCI_INT
        writeLe32(out, off + 48, 0);          // SMI_CMD
        // Bytes 52..115 stay zero (no PM blocks, no flags). Matches "PM not implemented".
        finaliseChecksum(out, off, FADT_LENGTH);
    }

    private static void writeDsdt(byte[] out, int off)
    {
        writeSdtHeader(out, off, "DSDT", DSDT_LENGTH, 1);
        // AML body: Scope (\_SB) {}
        // 0x10                ScopeOp
        // 0x06                PkgLength (1-byte) = 6 = PkgLen byte + NameString
        // 0x5C                RootChar '\'
        // 0x5F 0x53 0x42 0x5F NameSeg "_SB_"
        int p = off + SDT_HEADER_LENGTH;
        out[p + 0] = 0x10;
        out[p + 1] = 0x06;
        out[p + 2] = 0x5C;
        out[p + 3] = 0x5F;
        out[p + 4] = 0x53;
        out[p + 5] = 0x42;
        out[p + 6] = 0x5F;
        finaliseChecksum(out, off, DSDT_LENGTH);
    }

    private static void writeSdtHeader(byte[] out, int off, String signature, int length, int revision)
    {
        writeAscii(out, off + 0, signature, 4);
        writeLe32(out, off + 4, length);
        out[off + 8] = (byte) revision;
        out[off + 9] = 0;                                      // checksum (filled later)
        System.arraycopy(OEM_ID, 0, out, off + 10, 6);
        System.arraycopy(OEM_TABLE_ID, 0, out, off + 16, 8);
        writeLe32(out, off + 24, OEM_REVISION);
        System.arraycopy(CREATOR_ID, 0, out, off + 28, 4);
        writeLe32(out, off + 32, CREATOR_REVISION);
    }

    private static void finaliseChecksum(byte[] out, int off, int length)
    {
        // SDT-style checksum is at offset 9 within the table (header position).
        out[off + 9] = checksum(out, off, length);
    }

    private static byte checksum(byte[] data, int off, int len)
    {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[off + i] & 0xFF;
        return (byte) (-sum);
    }

    private static void writeLe16(byte[] out, int off, int value)
    {
        out[off]     = (byte) (value & 0xFF);
        out[off + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void writeLe32(byte[] out, int off, int value)
    {
        out[off]     = (byte) (value & 0xFF);
        out[off + 1] = (byte) ((value >>> 8) & 0xFF);
        out[off + 2] = (byte) ((value >>> 16) & 0xFF);
        out[off + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeAscii(byte[] out, int off, String s, int len)
    {
        for (int i = 0; i < len; i++) {
            out[off + i] = (byte) (i < s.length() ? s.charAt(i) : 0x20);
        }
    }

    private static byte[] ascii(String s, int len)
    {
        byte[] out = new byte[len];
        writeAscii(out, 0, s, len);
        return out;
    }
}
