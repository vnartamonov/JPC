package org.jpc.emulator.processor;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.Timer;
import org.jpc.emulator.TimerResponsive;
import org.jpc.support.Clock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that CPUID leaf 1 exposes APIC on chip (EDX bit 9) for cpuLevel 5 and 6.
 * NetBSD's alternatives_apply() gates spllower LAPIC patching on cpu_feature & CPUID_APIC.
 * Without this bit the 0x00 0x00 placeholder survives and faults on first spl call.
 */
class CpuidApicBitTest {

    private static Processor freshProcessor() {
        return new Processor(new StubClock());
    }

    @Test
    void cpuidLeaf1EdxHasApicBitForCpuLevel6() {
        Processor cpu = freshProcessor();
        assertEquals(6, Processor.cpuLevel, "test assumes default cpuLevel=6");

        cpu.r_eax.set32(0x01);
        cpu.cpuid();

        int edx = cpu.r_edx.get32();
        assertNotEquals(0, edx & (1 << 9), "CPUID.1.EDX bit 9 (APIC on chip) must be set for cpuLevel=6");
    }

    @Test
    void cpuidLeaf1EaxReturnsFamilyModelStepping() {
        Processor cpu = freshProcessor();
        cpu.r_eax.set32(0x01);
        cpu.cpuid();

        assertEquals(0x634, cpu.r_eax.get32(), "cpuLevel=6 must report eax=0x634 (Pentium II stepping 4)");
    }

    private static final class StubClock extends AbstractHardwareComponent implements Clock {
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
