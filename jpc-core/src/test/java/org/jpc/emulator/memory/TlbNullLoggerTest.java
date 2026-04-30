package org.jpc.emulator.memory;

import org.jpc.j2se.Option;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the diagnostic output produced by {@link TlbNullLogger} when
 * activated via the {@code -log-tlb-null} CLI flag. The logger is a
 * runtime-only diagnostic added in increment 0008 to confirm the source
 * of {@link NullPointerException} bubbling out of opcode handlers when
 * the underlying {@code PhysicalAddressSpace.getMemoryBlockAt} returned
 * a {@code null} reference.
 */
class TlbNullLoggerTest
{
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void setUp()
    {
        TlbNullLogger.resetForTesting();
        originalErr = System.err;
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true));
    }

    @AfterEach
    void tearDown() throws Exception
    {
        System.setErr(originalErr);
        forceSwitch(false);
    }

    /** Test-only switch toggle; reflects the {@link Option.Switch}'s package field. */
    private static void forceSwitch(boolean active) throws Exception
    {
        Field setField = Option.class.getDeclaredField("set");
        setField.setAccessible(true);
        setField.setBoolean(Option.log_tlb_null, active);
    }

    @Test
    void disabledByDefault()
    {
        assertFalse(TlbNullLogger.enabled(),
                "log-tlb-null is off unless explicitly set");
        TlbNullLogger.physBlockNull(0xFEE00000, 0x3FB, 0x140);
        assertEquals(0, capturedErr.size(),
                "no diagnostic lines emitted when disabled");
    }

    @Test
    void physBlockNullWritesAddressAndIndices() throws Exception
    {
        forceSwitch(true);
        TlbNullLogger.physBlockNull(0xFED40000, 0x3FB, 0x140);
        String out = capturedErr.toString();
        assertTrue(out.contains("PhysicalAddressSpace.getMemoryBlockAt"),
                "category prefix present; got: " + out);
        assertTrue(out.contains("0xfed40000"),
                "phys addr printed in lower-hex; got: " + out);
        assertTrue(out.contains("0x3fb"),
                "top-index printed; got: " + out);
        assertTrue(out.contains("0x140"),
                "bottom-index printed; got: " + out);
    }

    @Test
    void linearReadAndWriteHaveDistinctPrefixes() throws Exception
    {
        forceSwitch(true);
        TlbNullLogger.linearReadNull(0xC1234567, "4k", 0x100000, true);
        TlbNullLogger.linearWriteNull(0xC1234567, "4k", 0x100000, true);
        String out = capturedErr.toString();
        assertTrue(out.contains("validateTLBEntryRead returned null"));
        assertTrue(out.contains("validateTLBEntryWrite returned null"));
    }

    @Test
    void summaryReportsAllCounters() throws Exception
    {
        forceSwitch(true);
        TlbNullLogger.physBlockNull(0xFED40000, 0x3FB, 0x140);
        TlbNullLogger.physBlockNull(0xFED41000, 0x3FB, 0x141);
        TlbNullLogger.linearReadNull(0xC0000000, "4k", 0x100000, true);

        String summary = TlbNullLogger.summary();
        assertTrue(summary.contains("phys-null=2"));
        assertTrue(summary.contains("linear-read-null=1"));
        assertTrue(summary.contains("linear-write-null=0"));
    }

    @Test
    void floodIsCappedToAvoidLogExplosion() throws Exception
    {
        forceSwitch(true);
        // Trigger the same category 50 times; only the first 17 lines
        // (16 normal + 1 "suppressed" notice) should hit stderr.
        for (int i = 0; i < 50; i++) {
            TlbNullLogger.physBlockNull(0xFEC00000 + (i << 12), 0x3FB, i);
        }
        String out = capturedErr.toString();
        long lines = out.lines().count();
        assertTrue(lines <= 17,
                "log capped to ~17 lines, got " + lines + ":\n" + out);
        assertTrue(out.contains("further PhysicalAddressSpace null returns suppressed"),
                "suppression notice printed once");
        assertTrue(TlbNullLogger.summary().contains("phys-null=50"),
                "underlying counter still tracks every event");
    }
}
