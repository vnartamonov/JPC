package org.jpc.emulator.processor;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.Timer;
import org.jpc.emulator.TimerResponsive;
import org.jpc.support.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the diagnostic output produced by {@link FaultLogger} when JPC
 * dispatches a {@link ProcessorException}. The format exposed via
 * {@code -log-fault} must contain the EIP, the raw bytes and disassembly of
 * the faulting instruction, all GPRs, segment selectors, EFLAGS, control
 * registers and the top of the stack.
 */
class FaultLoggerTest
{
    private Processor cpu;

    @BeforeEach
    void setUp()
    {
        cpu = new Processor(new StubClock());
        // Place identifiable values into all GPRs.
        cpu.r_eax.set32(0x11111111);
        cpu.r_ebx.set32(0x22222222);
        cpu.r_ecx.set32(0x33333333);
        cpu.r_edx.set32(0x44444444);
        cpu.r_esi.set32(0x55555555);
        cpu.r_edi.set32(0x66666666);
        cpu.r_ebp.set32(0x77777777);
        cpu.r_esp.set32(0x88888888);
    }

    @Test
    void containsHeaderAndExceptionType()
    {
        ProcessorException pe = new ProcessorException(
                ProcessorException.Type.GENERAL_PROTECTION, 0, true);
        String dump = FaultLogger.format(cpu, pe, 0xC0102837, 0);

        assertTrue(dump.contains("=== JPC fault dispatch ==="),
                "header line present");
        assertTrue(dump.contains("=== end fault dispatch ==="),
                "footer line present");
        assertTrue(dump.contains("type=GENERAL_PROTECTION"),
                "exception type printed");
        assertTrue(dump.contains("vector=0x0D"),
                "vector printed in hex; got: " + dump);
    }

    @Test
    void containsSavedEipAsLinearAddress()
    {
        ProcessorException pe = ProcessorException.UNDEFINED;
        String dump = FaultLogger.format(cpu, pe, 0xC0102837, 0x10);

        assertTrue(dump.contains("C0102837"),
                "saved EIP rendered; got:\n" + dump);
    }

    @Test
    void containsAllGeneralPurposeRegisters()
    {
        ProcessorException pe = ProcessorException.UNDEFINED;
        String dump = FaultLogger.format(cpu, pe, 0xC0102837, 0x88888888);

        assertTrue(dump.contains("eax=11111111"));
        assertTrue(dump.contains("ebx=22222222"));
        assertTrue(dump.contains("ecx=33333333"));
        assertTrue(dump.contains("edx=44444444"));
        assertTrue(dump.contains("esi=55555555"));
        assertTrue(dump.contains("edi=66666666"));
        assertTrue(dump.contains("ebp=77777777"));
        assertTrue(dump.contains("esp=88888888"));
    }

    @Test
    void usesSavedEspNotCurrentEsp()
    {
        // savedEsp should be displayed even if the live ESP differs.
        cpu.r_esp.set32(0xDEADBEEF);
        String dump = FaultLogger.format(cpu, ProcessorException.UNDEFINED,
                0xC0102837, 0x12345678);

        assertTrue(dump.contains("esp=12345678"),
                "passed-in saved ESP wins; got:\n" + dump);
    }

    @Test
    void describesMemoryAsUnavailableWithoutLinearMemory()
    {
        // The fresh constructor leaves cpu.linearMemory == null; we verify the
        // dump degrades gracefully instead of NPEing.
        String dump = FaultLogger.format(cpu, ProcessorException.UNDEFINED,
                0xC0102837, 0);

        assertTrue(dump.contains("linear memory unavailable"),
                "dump notes the missing memory; got:\n" + dump);
    }

    @Test
    void includesControlRegisterLineAndStackSection()
    {
        String dump = FaultLogger.format(cpu, ProcessorException.UNDEFINED,
                0xC0102837, 0);

        assertTrue(dump.contains("eflags="));
        assertTrue(dump.contains("cr0="));
        assertTrue(dump.contains("cr2="));
        assertTrue(dump.contains("cr3="));
        assertTrue(dump.contains("cr4="));
        assertTrue(dump.contains("cpl="));
        assertTrue(dump.contains("--- stack (ss:esp .. +0x20) ---"),
                "stack header present");
    }

    @Test
    void doesNotThrowOnExtremeEspZero()
    {
        // Reproduces the exact NetBSD/i386 11.0_RC2 spllower+0x37 crash state:
        // savedEip=C0102837, savedEsp=0. The dump should still produce a
        // string and not propagate a fault while reading <unmapped> stack.
        String dump = FaultLogger.format(cpu, new ProcessorException(
                ProcessorException.Type.GENERAL_PROTECTION, 0, true),
                0xC0102837, 0);

        assertTrue(dump.contains("esp=00000000"),
                "esp=0 rendered; got:\n" + dump);
        assertEquals(2, dump.split("=== ").length - 1,
                "exactly two banner sections");
    }

    private static final class StubClock extends AbstractHardwareComponent implements Clock
    {
        public void update(int instructions) {}
        public void updateAndProcess(int instructions) {}
        public void updateNowAndProcess(boolean sleep) {}
        public long getTicks() { return 0L; }
        public long getEmulatedNanos() { return 0L; }
        public long getEmulatedMicros() { return 0L; }
        public long getRealMillis() { return 0L; }
        public long getTickRate() { return 1L; }
        public long getIPS() { return 1L; }
        public Timer newTimer(TimerResponsive object) { return new Timer(object, this); }
        public void update(Timer object) {}
        public void pause() {}
        public void resume() {}
    }
}
