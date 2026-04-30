package org.jpc.emulator.processor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically samples the {@link Processor} state and emits a one-line
 * snapshot. Useful when the guest hangs (idle in HLT, stuck in a tight
 * kernel loop, blocked in a debugger like NetBSD's ddb): the periodic
 * snapshot shows where execution is sitting without requiring an exception
 * dispatch (which would never fire while the kernel is waiting for a
 * non-arriving interrupt or a serial console keypress).
 *
 * <p>Activated by the {@code -eip-poll &lt;ms&gt;} CLI flag.
 *
 * <p>The poll runs on a daemon thread, so it never blocks JVM shutdown.
 * Memory reads through the linear address space go through the same
 * defensive helpers as {@link FaultLogger} so a poll never re-enters or
 * re-faults the emulator.
 */
public final class EipPoller
{
    private static final Logger LOGGING = Logger.getLogger(EipPoller.class.getName());

    private final Processor cpu;
    private final long periodMs;
    private final long startNanos;
    private volatile boolean running;
    private Thread thread;
    private long prevTicks = Long.MIN_VALUE;
    private long lastTicks = Long.MIN_VALUE;
    private int consecutiveStalls;

    public EipPoller(Processor cpu, long periodMs)
    {
        if (periodMs < 1) throw new IllegalArgumentException("periodMs must be >= 1");
        this.cpu = cpu;
        this.periodMs = periodMs;
        this.startNanos = System.nanoTime();
    }

    public void start()
    {
        running = true;
        thread = new Thread(this::run, "EipPoller");
        thread.setDaemon(true);
        thread.start();
        LOGGING.log(Level.INFO, "EipPoller started (period {0} ms)", periodMs);
    }

    public void stop()
    {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void run()
    {
        while (running) {
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException ie) {
                if (!running) break;
            }
            try {
                LOGGING.log(Level.INFO, snapshot());
            } catch (Throwable t) {
                LOGGING.log(Level.WARNING, "EipPoller snapshot failed: " + t, t);
            }
        }
    }

    /** One-line summary of the CPU state. Public so other code can request a snapshot on demand. */
    public String snapshot()
    {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String mode = modeOf(cpu);
        int cs = safeSelector(cpu.cs);
        int eip = cpu.eip;
        int csBase = safeBase(cpu.cs);

        long currentTicks = safeTicks();
        long delta = (prevTicks == Long.MIN_VALUE) ? -1L : (currentTicks - prevTicks);
        String stallTag = (prevTicks != Long.MIN_VALUE && delta == 0) ? " STALLED" : "";
        prevTicks = currentTicks;

        return String.format(
                "[poll T+%d.%03ds%s] mode=%s cpl=%d cs:eip=%04X:%08X (lin=%08X) "
                + "ticks=%d ticksDelta=%d "
                + "eax=%08X ebx=%08X ecx=%08X edx=%08X "
                + "esi=%08X edi=%08X ebp=%08X esp=%08X eflags=%08X",
                elapsedMs / 1000, elapsedMs % 1000, stallTag,
                mode, cpu.getCPL(), cs, eip, csBase + eip,
                currentTicks, delta,
                cpu.r_eax.get32(), cpu.r_ebx.get32(),
                cpu.r_ecx.get32(), cpu.r_edx.get32(),
                cpu.r_esi.get32(), cpu.r_edi.get32(),
                cpu.r_ebp.get32(), cpu.r_esp.get32(),
                cpu.getEFlags());
    }

    private long safeTicks()
    {
        try { return cpu.vmClock == null ? -1L : cpu.vmClock.getTicks(); }
        catch (Throwable t) { return -1L; }
    }

    private static String modeOf(Processor cpu)
    {
        try {
            if (cpu.isVirtual8086Mode()) return "V86";
            if (cpu.isProtectedMode())   return "PM";
            return "RM";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int safeSelector(Segment seg)
    {
        try { return seg == null ? 0 : seg.getSelector(); }
        catch (Throwable t) { return 0; }
    }

    private static int safeBase(Segment seg)
    {
        try { return seg == null ? 0 : seg.getBase(); }
        catch (Throwable t) { return 0; }
    }
}
