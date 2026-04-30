package org.jpc.emulator.motherboard;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.HardwareComponent;
import org.jpc.emulator.memory.AddressSpace;
import org.jpc.emulator.memory.EPROMMemory;
import org.jpc.emulator.memory.PhysicalAddressSpace;

/**
 * Builds an Intel MultiProcessor Specification 1.4 floating pointer plus
 * configuration table and overlays it into BIOS shadow ROM at physical
 * address 0xFC000. NetBSD/i386 and Linux scan 0xF0000-0xFFFFF for the {@code _MP_}
 * signature on early boot; placing the table here lets them discover the
 * (single-CPU, single IO-APIC) topology declared by JPC instead of falling
 * back to legacy 8259-only mode.
 *
 * <p>The overlay preserves the bytes of the original Bochs BIOS block from
 * offset {@link #MP_TOTAL_LENGTH} to the end of the 4 KB block; only the
 * first 240 bytes are replaced.
 *
 * <p>Increment 0003 (B4). LAPIC/IO-APIC MMIO are NOT yet implemented; the
 * declared addresses (0xFEE00000, 0xFEC00000) currently have no responder.
 */
public class MpTable extends AbstractHardwareComponent
{
    private static final Logger LOGGING = Logger.getLogger(MpTable.class.getName());

    /** Physical base of the 4 KB block we override (must be {@link AddressSpace#BLOCK_SIZE}-aligned). */
    public static final int BLOCK_BASE = 0xFC000;

    /** Offset of the MP Floating Pointer Structure within {@link #BLOCK_BASE}. */
    public static final int MPFPS_OFFSET = 0x000;
    /** Offset of the MP Configuration Table within {@link #BLOCK_BASE}. */
    public static final int MPC_OFFSET   = 0x010;

    public static final int MPFPS_LENGTH = 16;
    public static final int MPC_HEADER_LENGTH = 44;

    public static final int PROCESSOR_ENTRY_LENGTH = 20;
    public static final int BUS_ENTRY_LENGTH       = 8;
    public static final int IOAPIC_ENTRY_LENGTH    = 8;
    public static final int IOINT_ENTRY_LENGTH     = 8;
    public static final int LINT_ENTRY_LENGTH      = 8;

    public static final int IOAPIC_INT_ENTRIES = 16;
    public static final int LINT_ENTRIES = 2;
    public static final int TOTAL_ENTRIES = 1 + 1 + 1 + IOAPIC_INT_ENTRIES + LINT_ENTRIES; // 21

    public static final int MPC_LENGTH =
            MPC_HEADER_LENGTH
            + PROCESSOR_ENTRY_LENGTH
            + BUS_ENTRY_LENGTH
            + IOAPIC_ENTRY_LENGTH
            + IOAPIC_INT_ENTRIES * IOINT_ENTRY_LENGTH
            + LINT_ENTRIES * LINT_ENTRY_LENGTH;          // 224

    public static final int MP_TOTAL_LENGTH = MPFPS_LENGTH + MPC_LENGTH; // 240

    public static final int LAPIC_PHYS_ADDR = 0xFEE00000;
    public static final int IOAPIC_PHYS_ADDR = 0xFEC00000;

    public static final byte LAPIC_BSP_ID = 0;
    public static final byte LAPIC_VERSION = 0x14;
    public static final byte IOAPIC_ID = 2;
    public static final byte IOAPIC_VERSION = 0x11;
    public static final byte ISA_BUS_ID = 0;

    public static final int CPU_SIGNATURE_PII = 0x00000634;
    public static final int CPU_FEATURE_FLAGS = 0x00808171;

    private boolean loaded;

    public MpTable()
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

        byte[] mp = buildMpTable();
        System.arraycopy(mp, 0, block, 0, mp.length);

        EPROMMemory eprom = new EPROMMemory(block, 0, AddressSpace.BLOCK_SIZE,
                addressSpace.getCodeBlockManager());
        addressSpace.mapMemory(BLOCK_BASE, eprom);
        LOGGING.log(Level.INFO,
                "Installed MP-table overlay at 0x{0} (MPFPS=0x{1}, MPC=0x{2})",
                new Object[]{
                        Integer.toHexString(BLOCK_BASE),
                        Integer.toHexString(BLOCK_BASE + MPFPS_OFFSET),
                        Integer.toHexString(BLOCK_BASE + MPC_OFFSET)
                });
    }

    /**
     * Builds the 240-byte MP Floating Pointer + MP Configuration Table blob.
     * Public for unit tests; production code calls this from
     * {@link #installOverlay(PhysicalAddressSpace)}.
     */
    public static byte[] buildMpTable()
    {
        byte[] out = new byte[MP_TOTAL_LENGTH];

        writeMpFloatingPointer(out, MPFPS_OFFSET, BLOCK_BASE + MPC_OFFSET);
        writeMpcTable(out, MPC_OFFSET);

        return out;
    }

    private static void writeMpFloatingPointer(byte[] out, int off, int mpcPhysAddr)
    {
        // Signature "_MP_"
        out[off + 0] = '_';
        out[off + 1] = 'M';
        out[off + 2] = 'P';
        out[off + 3] = '_';
        // MPC physical address (LE32)
        writeLe32(out, off + 4, mpcPhysAddr);
        // Length / 16 — for the 16-byte structure, 1.
        out[off + 8] = 0x01;
        // Spec rev 1.4
        out[off + 9] = 0x04;
        // Checksum placeholder
        out[off + 10] = 0;
        // Feature byte 1: 0 means MPC table is present (not a default config).
        out[off + 11] = 0x00;
        // Feature bytes 2-5: all zero (no IMCRP, etc.).
        out[off + 12] = 0;
        out[off + 13] = 0;
        out[off + 14] = 0;
        out[off + 15] = 0;
        // Patch checksum so sum-of-bytes ≡ 0 mod 256.
        out[off + 10] = checksum(out, off, MPFPS_LENGTH);
    }

    private static void writeMpcTable(byte[] out, int off)
    {
        // MPC header
        out[off + 0] = 'P';
        out[off + 1] = 'C';
        out[off + 2] = 'M';
        out[off + 3] = 'P';
        writeLe16(out, off + 4, MPC_LENGTH);                  // base table length
        out[off + 6] = 0x04;                                  // spec rev 1.4
        out[off + 7] = 0;                                     // checksum (filled later)
        writeAscii(out, off + 8,  "JPC     ", 8);             // OEM ID
        writeAscii(out, off + 16, "JPC i386    ", 12);        // Product ID
        writeLe32(out, off + 28, 0);                          // OEM table pointer
        writeLe16(out, off + 32, 0);                          // OEM table size
        writeLe16(out, off + 34, TOTAL_ENTRIES);              // entry count
        writeLe32(out, off + 36, LAPIC_PHYS_ADDR);            // local APIC address
        writeLe16(out, off + 40, 0);                          // ext table length
        out[off + 42] = 0;                                    // ext table checksum
        out[off + 43] = 0;                                    // reserved

        int p = off + MPC_HEADER_LENGTH;

        // Processor entry (BSP)
        out[p + 0] = 0;                                       // type = processor
        out[p + 1] = LAPIC_BSP_ID;
        out[p + 2] = LAPIC_VERSION;
        out[p + 3] = 0x03;                                    // enabled (bit 0) | BSP (bit 1)
        writeLe32(out, p + 4, CPU_SIGNATURE_PII);
        writeLe32(out, p + 8, CPU_FEATURE_FLAGS);
        for (int i = 12; i < 20; i++) out[p + i] = 0;
        p += PROCESSOR_ENTRY_LENGTH;

        // Bus entry: ISA
        out[p + 0] = 1;                                       // type = bus
        out[p + 1] = ISA_BUS_ID;
        writeAscii(out, p + 2, "ISA   ", 6);
        p += BUS_ENTRY_LENGTH;

        // IO APIC entry
        out[p + 0] = 2;                                       // type = io apic
        out[p + 1] = IOAPIC_ID;
        out[p + 2] = IOAPIC_VERSION;
        out[p + 3] = 0x01;                                    // flags: enabled
        writeLe32(out, p + 4, IOAPIC_PHYS_ADDR);
        p += IOAPIC_ENTRY_LENGTH;

        // IO Interrupt entries: ISA IRQ N -> IO-APIC INTIN.
        // Standard PC mapping: IRQ 0 routed to INTIN 2 (timer); IRQ 2 is the
        // 8259 cascade, no IO-APIC pin; remaining IRQs route 1:1.
        for (int irq = 0; irq < 16; irq++) {
            int intin = irq;
            if (irq == 0) intin = 2;
            if (irq == 2) intin = 0; // unused but keep slot to stay at 16 entries
            out[p + 0] = 3;                                   // type = io int
            out[p + 1] = 0;                                   // INT (vectored)
            writeLe16(out, p + 2, 0);                         // flags: bus default polarity/trigger
            out[p + 4] = ISA_BUS_ID;
            out[p + 5] = (byte) irq;
            out[p + 6] = IOAPIC_ID;
            out[p + 7] = (byte) intin;
            p += IOINT_ENTRY_LENGTH;
        }

        // Local Interrupt entries: LINT0=ExtINT (8259 PIC), LINT1=NMI on all CPUs
        out[p + 0] = 4;                                       // type = local int
        out[p + 1] = 3;                                       // ExtINT
        writeLe16(out, p + 2, 0);
        out[p + 4] = ISA_BUS_ID;
        out[p + 5] = 0;
        out[p + 6] = (byte) 0xFF;                             // all CPUs
        out[p + 7] = 0;                                       // LINT0
        p += LINT_ENTRY_LENGTH;

        out[p + 0] = 4;
        out[p + 1] = 1;                                       // NMI
        writeLe16(out, p + 2, 0);
        out[p + 4] = ISA_BUS_ID;
        out[p + 5] = 0;
        out[p + 6] = (byte) 0xFF;
        out[p + 7] = 1;                                       // LINT1
        p += LINT_ENTRY_LENGTH;

        // Sanity: filled exactly MPC_LENGTH bytes.
        if (p - off != MPC_LENGTH) {
            throw new IllegalStateException(
                    "MPC layout mismatch: filled " + (p - off) + " expected " + MPC_LENGTH);
        }

        out[off + 7] = checksum(out, off, MPC_LENGTH);
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
}
