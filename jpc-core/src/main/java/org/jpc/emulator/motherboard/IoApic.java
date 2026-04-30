package org.jpc.emulator.motherboard;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.HardwareComponent;
import org.jpc.emulator.execution.codeblock.SpanningCodeBlock;
import org.jpc.emulator.memory.AbstractMemory;
import org.jpc.emulator.memory.AddressSpace;
import org.jpc.emulator.memory.PhysicalAddressSpace;
import org.jpc.emulator.processor.Processor;

/**
 * Crash-proof IO-APIC MMIO stub at physical address {@value #BASE_ADDRESS}.
 * Exposes only the two architectural dword registers — IOREGSEL at offset
 * 0x00 and IOWIN at offset 0x10 — and an indirect register file accessible
 * through them.
 *
 * <p>Indirect registers cover the standard architectural slots:
 * <ul>
 *   <li>0x00 IOAPICID — initialised from the value declared in MP-table 0003 (ID=2)</li>
 *   <li>0x01 IOAPICVER — 24 redirection entries, version 0x11</li>
 *   <li>0x02 IOAPICARB — 0</li>
 *   <li>0x10..0x3F IOREDTBL[0..23] — 24 entries × 64 bits, all masked at reset</li>
 * </ul>
 *
 * <p>The stub does NOT route ISA IRQs through the IO-APIC; legacy 8259
 * delivery via {@link InterruptController} is unchanged. IRQ routing is
 * a separate increment.
 */
public class IoApic extends AbstractHardwareComponent
{
    private static final Logger LOGGING = Logger.getLogger(IoApic.class.getName());

    public static final int BASE_ADDRESS = 0xFEC00000;

    public static final int OFFSET_IOREGSEL = 0x00;
    public static final int OFFSET_IOWIN    = 0x10;

    public static final int IDX_IOAPICID    = 0x00;
    public static final int IDX_IOAPICVER   = 0x01;
    public static final int IDX_IOAPICARB   = 0x02;
    public static final int IDX_REDTBL_BASE = 0x10;

    public static final int REDIRECTION_ENTRIES = 24;
    public static final int IOAPICID_RESET  = 0x02 << 24;
    public static final int IOAPICVER_RESET = (REDIRECTION_ENTRIES - 1) << 16 | 0x11;
    public static final int RTE_LOW_MASKED  = 0x00010000;

    private final IoApicMemory memory = new IoApicMemory();
    private boolean loaded;

    public IoApic()
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
        memory.applyResetValues();
        loaded = false;
    }

    @Override
    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.initialised() && !loaded) {
            ((PhysicalAddressSpace) component).mapMemory(BASE_ADDRESS, memory);
            loaded = true;
            LOGGING.log(Level.INFO, "IO-APIC stub mapped at 0x{0}",
                    Integer.toHexString(BASE_ADDRESS));
        }
    }

    @Override
    public void updateComponent(HardwareComponent component)
    {
        acceptComponent(component);
    }

    public IoApicMemory getMemory()
    {
        return memory;
    }

    public static final class IoApicMemory extends AbstractMemory
    {
        /** Indirect register file: 64 dword slots, indexed by IOREGSEL low 8 bits. */
        private final int[] indirect = new int[256];
        private int ioregsel;

        public IoApicMemory()
        {
            applyResetValues();
        }

        void applyResetValues()
        {
            for (int i = 0; i < indirect.length; i++) indirect[i] = 0;
            indirect[IDX_IOAPICID]  = IOAPICID_RESET;
            indirect[IDX_IOAPICVER] = IOAPICVER_RESET;
            indirect[IDX_IOAPICARB] = 0;
            for (int e = 0; e < REDIRECTION_ENTRIES; e++) {
                indirect[IDX_REDTBL_BASE + e * 2]     = RTE_LOW_MASKED;
                indirect[IDX_REDTBL_BASE + e * 2 + 1] = 0;
            }
            ioregsel = 0;
        }

        @Override
        public long getSize()
        {
            return AddressSpace.BLOCK_SIZE;
        }

        @Override
        public boolean isAllocated()
        {
            return true;
        }

        @Override
        public int getDoubleWord(int offset)
        {
            int local = offset & (AddressSpace.BLOCK_SIZE - 1);
            if (local == OFFSET_IOREGSEL) return ioregsel;
            if (local == OFFSET_IOWIN)    return indirect[ioregsel & 0xFF];
            return 0;
        }

        @Override
        public void setDoubleWord(int offset, int data)
        {
            int local = offset & (AddressSpace.BLOCK_SIZE - 1);
            if (local == OFFSET_IOREGSEL) {
                ioregsel = data;
            } else if (local == OFFSET_IOWIN) {
                int idx = ioregsel & 0xFF;
                if (idx == IDX_IOAPICVER || idx == IDX_IOAPICARB) {
                    return; // read-only
                }
                indirect[idx] = data;
            }
            // writes to other offsets in the 4 KB block are silently dropped.
        }

        @Override
        public byte getByte(int offset)
        {
            int dw = getDoubleWord(offset & ~3);
            int shift = (offset & 3) << 3;
            return (byte) (dw >>> shift);
        }

        @Override
        public void setByte(int offset, byte data)
        {
            int aligned = offset & ~3;
            int shift = (offset & 3) << 3;
            int dw = getDoubleWord(aligned);
            dw = (dw & ~(0xFF << shift)) | ((data & 0xFF) << shift);
            setDoubleWord(aligned, dw);
        }

        @Override
        public void clear()
        {
            applyResetValues();
        }

        @Override
        public void clear(int start, int length)
        {
            applyResetValues();
        }

        @Override
        public void loadInitialContents(int address, byte[] buf, int off, int len)
        {
            // no-op
        }

        @Override
        public void addSpanningBlock(SpanningCodeBlock b, int lengthRemaining)
        {
            // no-op
        }

        @Override
        public int executeReal(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in IO-APIC MMIO @ 0x" + Integer.toHexString(address));
        }

        @Override
        public int executeProtected(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in IO-APIC MMIO @ 0x" + Integer.toHexString(address));
        }

        @Override
        public int executeVirtual8086(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in IO-APIC MMIO @ 0x" + Integer.toHexString(address));
        }

        /** Visible for testing — direct access to the index register. */
        public int getIoRegSel()
        {
            return ioregsel;
        }
    }
}
