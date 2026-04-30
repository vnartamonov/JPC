package org.jpc.emulator.processor;

import org.jpc.emulator.AbstractHardwareComponent;
import org.jpc.emulator.Timer;
import org.jpc.emulator.TimerResponsive;
import org.jpc.support.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the one-line CPU snapshot produced by {@link EipPoller#snapshot()}.
 * The poller itself runs on a daemon thread and is exercised in JPC end-to-end
 * boot tests; the unit test here just checks the formatter so the format
 * stays stable for log-grepping.
 */
class EipPollerTest
{
    private Processor cpu;
    private EipPoller poller;

    @BeforeEach
    void setUp()
    {
        cpu = new Processor(new StubClock());
        cpu.r_eax.set32(0x11111111);
        cpu.r_ebx.set32(0x22222222);
        cpu.r_ecx.set32(0x33333333);
        cpu.r_edx.set32(0x44444444);
        cpu.r_esi.set32(0x55555555);
        cpu.r_edi.set32(0x66666666);
        cpu.r_ebp.set32(0x77777777);
        cpu.r_esp.set32(0x88888888);
        cpu.eip = 0xC0102837;
        poller = new EipPoller(cpu, 1000);
    }

    @Test
    void snapshotIsSingleLineWithExpectedFields()
    {
        String s = poller.snapshot();

        assertTrue(s.startsWith("[poll T+"), "starts with poll banner; got: " + s);
        assertTrue(s.contains("mode="),    "mode field present");
        assertTrue(s.contains("cpl="),     "cpl field present");
        assertTrue(s.contains("cs:eip="),  "cs:eip field present");
        assertTrue(s.contains("eax=11111111"));
        assertTrue(s.contains("ebx=22222222"));
        assertTrue(s.contains("ecx=33333333"));
        assertTrue(s.contains("edx=44444444"));
        assertTrue(s.contains("esi=55555555"));
        assertTrue(s.contains("edi=66666666"));
        assertTrue(s.contains("ebp=77777777"));
        assertTrue(s.contains("esp=88888888"));
        assertTrue(s.contains("eflags="),  "eflags field present");
        assertTrue(s.indexOf('\n') < 0,    "snapshot must be one line; got: " + s);
    }

    @Test
    void snapshotIncludesEipInHex()
    {
        String s = poller.snapshot();
        assertTrue(s.contains(":C0102837"),
                "EIP rendered in upper-case hex without 0x prefix; got: " + s);
    }

    @Test
    void rejectsZeroPeriod()
    {
        assertThrows(IllegalArgumentException.class, () -> new EipPoller(cpu, 0));
    }

    @Test
    void rejectsNegativePeriod()
    {
        assertThrows(IllegalArgumentException.class, () -> new EipPoller(cpu, -1));
    }

    @Test
    void snapshotRobustAfterReset()
    {
        // After a fresh constructor, segments are valid; nothing should NPE.
        Processor fresh = new Processor(new StubClock());
        EipPoller p = new EipPoller(fresh, 1000);
        String s = p.snapshot();
        assertTrue(s.length() > 80, "snapshot non-empty; got: " + s);
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
