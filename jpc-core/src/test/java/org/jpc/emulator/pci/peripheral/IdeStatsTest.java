package org.jpc.emulator.pci.peripheral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-channel command counters introduced for B6 — diagnostic
 * surface for {@link IDEChannel}'s ATA/ATAPI dispatch paths.
 */
class IdeStatsTest
{
    private IdeStats stats;

    @BeforeEach
    void setUp()
    {
        stats = new IdeStats();
    }

    @Test
    void freshInstanceHasZeroCounters()
    {
        for (int i = 0; i < 256; i++) {
            assertEquals(0L, stats.getAtaCount(i));
            assertEquals(0L, stats.getAtapiCount(i));
        }
        assertEquals(0L, stats.getTotalAtaCommands());
        assertEquals(0L, stats.getTotalAtapiCommands());
    }

    @Test
    void recordAtaIncrements()
    {
        stats.recordAta(0x20);
        assertEquals(1L, stats.getAtaCount(0x20));
        assertEquals(1L, stats.getTotalAtaCommands());
    }

    @Test
    void recordAtaIsAccumulative()
    {
        stats.recordAta(0xC8);
        stats.recordAta(0xC8);
        stats.recordAta(0xC8);
        assertEquals(3L, stats.getAtaCount(0xC8));
    }

    @Test
    void differentOpcodesIndependent()
    {
        stats.recordAta(0x20);
        stats.recordAta(0xC8);
        stats.recordAta(0xC8);
        assertEquals(1L, stats.getAtaCount(0x20));
        assertEquals(2L, stats.getAtaCount(0xC8));
        assertEquals(3L, stats.getTotalAtaCommands());
    }

    @Test
    void ataAndAtapiAreIsolated()
    {
        stats.recordAta(0x28);
        stats.recordAtapi(0x28);
        assertEquals(1L, stats.getAtaCount(0x28));
        assertEquals(1L, stats.getAtapiCount(0x28));
        assertEquals(1L, stats.getTotalAtaCommands());
        assertEquals(1L, stats.getTotalAtapiCommands());
    }

    @Test
    void recordOpcodeMaskedToByte()
    {
        // Negative or oversized values still index correctly.
        stats.recordAta(0xFFFF20);
        stats.recordAta(-256 + 0x20);
        assertEquals(2L, stats.getAtaCount(0x20));
    }

    @Test
    void ataSnapshotContainsOnlyNonZero()
    {
        stats.recordAta(0xEC);
        stats.recordAta(0xC8);
        stats.recordAta(0xC8);
        Map<Integer, Long> snap = stats.ataSnapshot();
        assertEquals(2, snap.size());
        assertEquals(1L, snap.get(0xEC));
        assertEquals(2L, snap.get(0xC8));
    }

    @Test
    void atapiSnapshotEmptyAfterInit()
    {
        assertTrue(stats.atapiSnapshot().isEmpty());
    }

    @Test
    void resetClearsBothFamilies()
    {
        stats.recordAta(0xEC);
        stats.recordAtapi(0x12);
        stats.reset();
        assertEquals(0L, stats.getAtaCount(0xEC));
        assertEquals(0L, stats.getAtapiCount(0x12));
        assertEquals(0L, stats.getTotalAtaCommands());
        assertEquals(0L, stats.getTotalAtapiCommands());
    }

    @Test
    void ataMnemonicsCoverCommonCommands()
    {
        // Spec values straight from ATA-7 / IDEChannel.IDEState.
        assertEquals("WIN_IDENTIFY",       IdeStats.ataMnemonic(0xEC));
        assertEquals("WIN_READ",           IdeStats.ataMnemonic(0x20));
        assertEquals("WIN_READDMA",        IdeStats.ataMnemonic(0xC8));
        assertEquals("WIN_WRITEDMA",       IdeStats.ataMnemonic(0xCA));
        assertEquals("WIN_PACKETCMD",      IdeStats.ataMnemonic(0xA0));
        assertEquals("WIN_FLUSH_CACHE",    IdeStats.ataMnemonic(0xE7));
        assertEquals("WIN_SETFEATURES",    IdeStats.ataMnemonic(0xEF));
    }

    @Test
    void unknownAtaOpcodeFallsBackToHex()
    {
        assertEquals("0x77", IdeStats.ataMnemonic(0x77));
    }

    @Test
    void atapiMnemonicsCoverCommonCommands()
    {
        assertEquals("GPCMD_INQUIRY",        IdeStats.atapiMnemonic(0x12));
        assertEquals("GPCMD_READ_10",        IdeStats.atapiMnemonic(0x28));
        assertEquals("GPCMD_MODE_SENSE_10",  IdeStats.atapiMnemonic(0x5A));
        assertEquals("GPCMD_READ_TOC_PMA_ATIP", IdeStats.atapiMnemonic(0x43));
        assertEquals("GPCMD_TEST_UNIT_READY", IdeStats.atapiMnemonic(0x00));
        assertEquals("GPCMD_REQUEST_SENSE",   IdeStats.atapiMnemonic(0x03));
    }

    @Test
    void unknownAtapiOpcodeFallsBackToHex()
    {
        assertEquals("0x77", IdeStats.atapiMnemonic(0x77));
    }

    @Test
    void mnemonicHexIsUppercaseTwoDigits()
    {
        assertEquals("0x05", IdeStats.ataMnemonic(0x05));
        assertEquals("0xAB", IdeStats.ataMnemonic(0xAB));
        assertFalse(IdeStats.ataMnemonic(0xAB).contains("ab"),
                "unknown mnemonic should be upper-case hex");
    }

    @Test
    void snapshotIsIndependentOfFurtherUpdates()
    {
        stats.recordAta(0xEC);
        Map<Integer, Long> snap = stats.ataSnapshot();
        stats.recordAta(0xEC);
        // The snapshot is a fresh map, not a live view.
        assertEquals(1L, snap.get(0xEC));
        assertEquals(2L, stats.getAtaCount(0xEC));
    }
}
