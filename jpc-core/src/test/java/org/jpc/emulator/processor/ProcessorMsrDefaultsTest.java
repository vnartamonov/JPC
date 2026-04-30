package org.jpc.emulator.processor;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.Timer;
import org.jpc.emulator.TimerResponsive;
import org.jpc.support.Clock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the MSR defaults seeded in {@link Processor} for guests (NetBSD,
 * modern Linux) that read architectural MSRs during early boot.
 *
 * <p>The processor's reset() path requires a non-null linearMemory; the
 * constructor does not. These tests therefore exercise the constructor seed
 * (which mirrors the reset seed).
 */
class ProcessorMsrDefaultsTest
{
    private static Processor freshProcessor()
    {
        return new Processor(new StubClock());
    }

    @Test
    void defaultCpuLevelIsPentiumIi()
    {
        // No -cpulevel CLI arg in test JVM; the static-final default applies.
        assertEquals(6, Processor.cpuLevel,
                "default cpuLevel should be 6 (Pentium II) per increment 0002");
    }

    @Test
    void apicBaseMsrSeededWithLapicAddressBspAndEnable()
    {
        Processor cpu = freshProcessor();
        // 0xFEE00000 base | bit 8 (BSP) | bit 11 (global enable) = 0xFEE00900
        assertEquals(0xFEE00900L, cpu.getMSR(Processor.IA32_APIC_BASE_MSR));
        assertEquals(Processor.IA32_APIC_BASE_DEFAULT,
                cpu.getMSR(Processor.IA32_APIC_BASE_MSR));
    }

    @Test
    void mtrrCapMsrSeeded()
    {
        Processor cpu = freshProcessor();
        // VCNT=8 | FIX=1 (bit 8) | WC=1 (bit 10)
        assertEquals(0x508L, cpu.getMSR(Processor.IA32_MTRRCAP_MSR));
    }

    @Test
    void patMsrSeededWithIntelResetValue()
    {
        Processor cpu = freshProcessor();
        // Intel reset value: WB, WT, UC-, UC, WB, WT, UC-, UC.
        assertEquals(0x0007040600070406L, cpu.getMSR(Processor.IA32_PAT_MSR));
    }

    @Test
    void miscEnableMsrSeededWithFastStrings()
    {
        Processor cpu = freshProcessor();
        assertEquals(0x1L, cpu.getMSR(Processor.IA32_MISC_ENABLE_MSR));
    }

    @Test
    void mtrrDefTypeMsrSeededZero()
    {
        Processor cpu = freshProcessor();
        assertEquals(0L, cpu.getMSR(Processor.IA32_MTRR_DEF_TYPE_MSR));
    }

    @Test
    void unknownMsrReturnsZeroNotCrash()
    {
        Processor cpu = freshProcessor();
        assertEquals(0L, cpu.getMSR(0xDEADBEEF));
    }

    @Test
    void wrmsrThenRdmsrRoundTrip()
    {
        Processor cpu = freshProcessor();
        cpu.setMSR(Processor.IA32_TSC_MSR, 0xCAFEBABEDEADBEEFL);
        assertEquals(0xCAFEBABEDEADBEEFL, cpu.getMSR(Processor.IA32_TSC_MSR));
    }

    @Test
    void wrmsrCanOverrideSeededValue()
    {
        Processor cpu = freshProcessor();
        cpu.setMSR(Processor.IA32_APIC_BASE_MSR, 0L);
        assertEquals(0L, cpu.getMSR(Processor.IA32_APIC_BASE_MSR));
    }

    /**
     * Minimal Clock implementation: no timers, monotonic dummy ticks. None of
     * the test paths invoke clock methods, but Processor's constructor requires
     * a non-null Clock to satisfy the field assignment.
     */
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
