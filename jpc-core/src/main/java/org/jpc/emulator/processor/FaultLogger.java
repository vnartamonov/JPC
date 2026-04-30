package org.jpc.emulator.processor;

import org.jpc.emulator.execution.decoder.Disassembler;
import org.jpc.emulator.execution.decoder.Instruction;

/**
 * Diagnostic helper that produces a multi-line snapshot of a {@link Processor}
 * at the moment a {@link ProcessorException} is dispatched: faulting EIP, the
 * 16 raw bytes at CS:EIP, a disassembly of the faulting instruction, all
 * general-purpose registers, segment selectors, EFLAGS, control registers
 * and the top 8 dwords of the stack.
 *
 * <p>Used by {@code -log-fault} to surface what the kernel was running when
 * JPC raised an exception. Memory reads through paged virtual addresses go
 * through {@code cpu.linearMemory}; any exception while reading is caught
 * and the offending field reads {@code "&lt;unavailable&gt;"} so the dump is
 * always best-effort and never re-faults.
 *
 * <p>Increment 0008 (B7-followup).
 */
public final class FaultLogger
{
    private FaultLogger() {}

    /**
     * Produces a human-readable multi-line dump of the CPU state at the
     * moment the supplied exception was dispatched.
     *
     * @param cpu       processor state at fault dispatch
     * @param pe        the exception that triggered the dump
     * @param savedEip  EIP before the trap unwound (since {@code cpu.eip}
     *                  has already been pushed to the kernel stack by the
     *                  time this is called from within the dispatcher)
     * @param savedEsp  ESP at fault time
     */
    public static String format(Processor cpu, ProcessorException pe,
                                int savedEip, int savedEsp)
    {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== JPC fault dispatch ===\n");
        sb.append(String.format("type=%s vector=0x%02X errorCode=0x%X hasErrorCode=%s%n",
                pe.getType(), pe.getType().vector(),
                pe.getErrorCode(), pe.hasErrorCode()));

        int csBase = safeSegmentBase(cpu.cs);
        int linearEip = csBase + savedEip;
        sb.append(String.format("cs:eip = %04X:%08X  (linear %08X)%n",
                safeSelector(cpu.cs), savedEip, linearEip));

        sb.append("bytes  = ").append(readBytesAsHex(cpu, linearEip, 16)).append('\n');
        sb.append("disasm = ").append(disassembleAt(cpu, linearEip)).append('\n');

        sb.append("--- registers ---\n");
        sb.append(String.format("eax=%08X ebx=%08X ecx=%08X edx=%08X%n",
                cpu.r_eax.get32(), cpu.r_ebx.get32(),
                cpu.r_ecx.get32(), cpu.r_edx.get32()));
        sb.append(String.format("esi=%08X edi=%08X ebp=%08X esp=%08X%n",
                cpu.r_esi.get32(), cpu.r_edi.get32(),
                cpu.r_ebp.get32(), savedEsp));

        sb.append("--- segments ---\n");
        sb.append(String.format("cs=%04X ds=%04X es=%04X fs=%04X gs=%04X ss=%04X%n",
                safeSelector(cpu.cs), safeSelector(cpu.ds),
                safeSelector(cpu.es), safeSelector(cpu.fs),
                safeSelector(cpu.gs), safeSelector(cpu.ss)));

        sb.append("--- flags / control ---\n");
        sb.append(String.format("eflags=%08X cr0=%08X cr2=%08X cr3=%08X cr4=%08X%n",
                cpu.getEFlags(), cpu.getCR0(), cpu.getCR2(),
                cpu.getCR3(), cpu.getCR4()));
        sb.append(String.format("cpl=%d v8086=%s%n",
                cpu.getCPL(), cpu.isVirtual8086Mode()));

        sb.append("--- stack (ss:esp .. +0x20) ---\n");
        int ssBase = safeSegmentBase(cpu.ss);
        sb.append(readStackDwords(cpu, ssBase + savedEsp, 8));
        sb.append("=== end fault dispatch ===");
        return sb.toString();
    }

    private static int safeSelector(Segment seg)
    {
        try { return seg == null ? 0 : seg.getSelector(); }
        catch (Throwable t) { return 0; }
    }

    private static int safeSegmentBase(Segment seg)
    {
        try { return seg == null ? 0 : seg.getBase(); }
        catch (Throwable t) { return 0; }
    }

    private static String readBytesAsHex(Processor cpu, int linearAddr, int count)
    {
        if (cpu.linearMemory == null) return "<linear memory unavailable>";
        StringBuilder sb = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            try {
                int b = cpu.linearMemory.getByte(linearAddr + i) & 0xFF;
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", b));
            } catch (Throwable t) {
                sb.append(' ').append("??");
            }
        }
        return sb.toString();
    }

    private static String disassembleAt(Processor cpu, int linearAddr)
    {
        if (cpu.linearMemory == null) return "<linear memory unavailable>";
        byte[] code = new byte[15];
        try {
            for (int i = 0; i < code.length; i++) {
                code[i] = cpu.linearMemory.getByte(linearAddr + i);
            }
        } catch (Throwable t) {
            return "<read failed: " + t.getClass().getSimpleName() + ">";
        }
        try {
            boolean is32 = cpu.cs != null && cpu.cs.getDefaultSizeFlag();
            Instruction insn = is32
                    ? Disassembler.disassemble32(new Disassembler.ByteArrayPeekStream(code))
                    : Disassembler.disassemble16(new Disassembler.ByteArrayPeekStream(code));
            return insn.toString();
        } catch (Throwable t) {
            return "<disasm failed: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String readStackDwords(Processor cpu, int linearAddr, int count)
    {
        if (cpu.linearMemory == null) return "<linear memory unavailable>\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int addr = linearAddr + i * 4;
            try {
                int v = cpu.linearMemory.getDoubleWord(addr);
                sb.append(String.format("  %08X: %08X%n", addr, v));
            } catch (Throwable t) {
                sb.append(String.format("  %08X: <unreadable>%n", addr));
            }
        }
        return sb.toString();
    }
}
