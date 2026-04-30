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
 * Crash-proof Local APIC MMIO stub at physical address {@value #BASE_ADDRESS}.
 * All registers in the 4 KB window are backed by a 256-entry {@code int[]}
 * (one entry per dword); reset values follow Intel SDM Vol.3A §10.4 closely
 * enough that NetBSD/i386 and Linux can complete the LAPIC-init code path
 * without faulting.
 *
 * <p>The stub does NOT deliver interrupts: timer registers store the
 * configuration but never fire, EOI is a no-op, ICR writes do not produce
 * IPIs, ESR never accumulates errors. Promoting this to a working device is
 * a separate increment.
 *
 * <p>The {@link Processor} parameter on {@code execute*} throws like
 * {@link PhysicalAddressSpace.UnconnectedMemoryBlock}: code execution from
 * the LAPIC window is invalid and must surface as an emulator error rather
 * than be silently fed to the decoder.
 */
public class LocalApic extends AbstractHardwareComponent
{
    private static final Logger LOGGING = Logger.getLogger(LocalApic.class.getName());

    public static final int BASE_ADDRESS = 0xFEE00000;

    public static final int REG_ID                 = 0x020;
    public static final int REG_VERSION            = 0x030;
    public static final int REG_TPR                = 0x080;
    public static final int REG_APR                = 0x090;
    public static final int REG_PPR                = 0x0A0;
    public static final int REG_EOI                = 0x0B0;
    public static final int REG_RRD                = 0x0C0;
    public static final int REG_LDR                = 0x0D0;
    public static final int REG_DFR                = 0x0E0;
    public static final int REG_SVR                = 0x0F0;
    public static final int REG_ISR_BASE           = 0x100;
    public static final int REG_TMR_BASE           = 0x180;
    public static final int REG_IRR_BASE           = 0x200;
    public static final int REG_ESR                = 0x280;
    public static final int REG_ICR_LOW            = 0x300;
    public static final int REG_ICR_HIGH           = 0x310;
    public static final int REG_LVT_TIMER          = 0x320;
    public static final int REG_LVT_THERMAL        = 0x330;
    public static final int REG_LVT_PERFCNT        = 0x340;
    public static final int REG_LVT_LINT0          = 0x350;
    public static final int REG_LVT_LINT1          = 0x360;
    public static final int REG_LVT_ERROR          = 0x370;
    public static final int REG_INITIAL_COUNT      = 0x380;
    public static final int REG_CURRENT_COUNT      = 0x390;
    public static final int REG_DIVIDE_CONFIG      = 0x3E0;

    public static final int VERSION_VALUE          = 0x00050014;
    public static final int LDR_RESET              = 0xFF000000;
    public static final int DFR_RESET              = 0xFFFFFFFF;
    public static final int SVR_RESET              = 0x000000FF;
    public static final int LVT_MASKED             = 0x00010000;

    private final LocalApicMemory memory = new LocalApicMemory();
    private boolean loaded;

    public LocalApic()
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
            LOGGING.log(Level.INFO, "LAPIC stub mapped at 0x{0}",
                    Integer.toHexString(BASE_ADDRESS));
        }
    }

    @Override
    public void updateComponent(HardwareComponent component)
    {
        acceptComponent(component);
    }

    /** Visible for testing. */
    public LocalApicMemory getMemory()
    {
        return memory;
    }

    /**
     * MMIO-backed register file. Storage is {@code int[1024]} for the full
     * 4 KB window; only ~30 of those slots are meaningful, the rest read as
     * zero and accept arbitrary writes (forward-compatible).
     */
    public static final class LocalApicMemory extends AbstractMemory
    {
        private static final int DWORDS = AddressSpace.BLOCK_SIZE / 4;
        private final int[] regs = new int[DWORDS];

        public LocalApicMemory()
        {
            applyResetValues();
        }

        void applyResetValues()
        {
            for (int i = 0; i < regs.length; i++) regs[i] = 0;
            regs[REG_VERSION       >>> 2] = VERSION_VALUE;
            regs[REG_LDR           >>> 2] = LDR_RESET;
            regs[REG_DFR           >>> 2] = DFR_RESET;
            regs[REG_SVR           >>> 2] = SVR_RESET;
            regs[REG_LVT_TIMER     >>> 2] = LVT_MASKED;
            regs[REG_LVT_THERMAL   >>> 2] = LVT_MASKED;
            regs[REG_LVT_PERFCNT   >>> 2] = LVT_MASKED;
            regs[REG_LVT_LINT0     >>> 2] = LVT_MASKED;
            regs[REG_LVT_LINT1     >>> 2] = LVT_MASKED;
            regs[REG_LVT_ERROR     >>> 2] = LVT_MASKED;
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
            return regs[(offset & (AddressSpace.BLOCK_SIZE - 1)) >>> 2];
        }

        @Override
        public void setDoubleWord(int offset, int data)
        {
            int idx = (offset & (AddressSpace.BLOCK_SIZE - 1)) >>> 2;
            switch (idx << 2) {
                case REG_VERSION:
                case REG_APR:
                case REG_PPR:
                case REG_RRD:
                    return; // read-only
                case REG_EOI:
                    return; // write-only register: no ISR semantics in stub
                default:
                    regs[idx] = data;
            }
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
            // LAPIC reset is whole-state; partial clear isn't meaningful here.
            applyResetValues();
        }

        @Override
        public void loadInitialContents(int address, byte[] buf, int off, int len)
        {
            // No-op: LAPIC has no preloadable image content.
        }

        @Override
        public void addSpanningBlock(SpanningCodeBlock b, int lengthRemaining)
        {
            // Spanning code through MMIO is invalid; nothing to record.
        }

        @Override
        public int executeReal(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in LAPIC MMIO @ 0x" + Integer.toHexString(address));
        }

        @Override
        public int executeProtected(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in LAPIC MMIO @ 0x" + Integer.toHexString(address));
        }

        @Override
        public int executeVirtual8086(Processor cpu, int address)
        {
            throw new IllegalStateException(
                    "Cannot execute code in LAPIC MMIO @ 0x" + Integer.toHexString(address));
        }
    }
}
