package org.jpc.emulator.pci.peripheral;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lightweight per-channel command counters for {@link IDEChannel}. Tracks
 * the number of times each ATA and ATAPI opcode has been issued; provides
 * a snapshot view for diagnostics and known-opcode mnemonic lookup.
 *
 * <p>This is the diagnostic backbone behind the {@code -trace-ide} CLI
 * flag: when set, each command also gets logged through
 * {@link java.util.logging}. Independent of the flag, counters are always
 * maintained — they cost a single {@link AtomicLongArray} increment per
 * command and let other code (debugger, save-state inspector, future CSV
 * dumper) inspect device usage.
 *
 * <p>Increment 0006 (B6).
 */
public final class IdeStats
{
    private final AtomicLongArray ataCounts = new AtomicLongArray(256);
    private final AtomicLongArray atapiCounts = new AtomicLongArray(256);

    private static final Map<Integer, String> ATA_NAMES = buildAtaNames();
    private static final Map<Integer, String> ATAPI_NAMES = buildAtapiNames();

    public void recordAta(int opcode)
    {
        ataCounts.incrementAndGet(opcode & 0xFF);
    }

    public void recordAtapi(int opcode)
    {
        atapiCounts.incrementAndGet(opcode & 0xFF);
    }

    public long getAtaCount(int opcode)
    {
        return ataCounts.get(opcode & 0xFF);
    }

    public long getAtapiCount(int opcode)
    {
        return atapiCounts.get(opcode & 0xFF);
    }

    public long getTotalAtaCommands()
    {
        long total = 0;
        for (int i = 0; i < 256; i++) total += ataCounts.get(i);
        return total;
    }

    public long getTotalAtapiCommands()
    {
        long total = 0;
        for (int i = 0; i < 256; i++) total += atapiCounts.get(i);
        return total;
    }

    /** Returns a copy of non-zero ATA opcode counters, ordered by opcode. */
    public Map<Integer, Long> ataSnapshot()
    {
        return snapshot(ataCounts);
    }

    /** Returns a copy of non-zero ATAPI opcode counters, ordered by opcode. */
    public Map<Integer, Long> atapiSnapshot()
    {
        return snapshot(atapiCounts);
    }

    private static Map<Integer, Long> snapshot(AtomicLongArray src)
    {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++) {
            long v = src.get(i);
            if (v != 0) out.put(i, v);
        }
        return out;
    }

    public void reset()
    {
        for (int i = 0; i < 256; i++) {
            ataCounts.set(i, 0);
            atapiCounts.set(i, 0);
        }
    }

    /**
     * Mnemonic for an ATA opcode, e.g. {@code "WIN_READDMA"} for {@code 0xC8}.
     * Falls back to {@code "0xNN"} for unknown opcodes.
     */
    public static String ataMnemonic(int opcode)
    {
        String name = ATA_NAMES.get(opcode & 0xFF);
        return name != null ? name : String.format("0x%02X", opcode & 0xFF);
    }

    /**
     * Mnemonic for an ATAPI/MMC packet opcode, e.g. {@code "GPCMD_READ_10"}
     * for {@code 0x28}. Falls back to {@code "0xNN"} for unknown opcodes.
     */
    public static String atapiMnemonic(int opcode)
    {
        String name = ATAPI_NAMES.get(opcode & 0xFF);
        return name != null ? name : String.format("0x%02X", opcode & 0xFF);
    }

    private static Map<Integer, String> buildAtaNames()
    {
        Map<Integer, String> m = new HashMap<>();
        // Names mirror IDEChannel.IDEState.WIN_*. Kept manual to avoid
        // reflecting into a package-private inner class.
        m.put(0x00, "WIN_NOP");
        m.put(0x08, "WIN_SRST/DEVICE_RESET");
        m.put(0x10, "WIN_RECAL/RESTORE");
        m.put(0x20, "WIN_READ");
        m.put(0x21, "WIN_READ_ONCE");
        m.put(0x22, "WIN_READ_LONG");
        m.put(0x23, "WIN_READ_LONG_ONCE");
        m.put(0x24, "WIN_READ_EXT");
        m.put(0x25, "WIN_READDMA_EXT");
        m.put(0x26, "WIN_READDMA_QUEUED_EXT");
        m.put(0x27, "WIN_READ_NATIVE_MAX_EXT");
        m.put(0x29, "WIN_MULTREAD_EXT");
        m.put(0x30, "WIN_WRITE");
        m.put(0x31, "WIN_WRITE_ONCE");
        m.put(0x34, "WIN_WRITE_EXT");
        m.put(0x35, "WIN_WRITEDMA_EXT");
        m.put(0x36, "WIN_WRITEDMA_QUEUED_EXT");
        m.put(0x39, "WIN_MULTWRITE_EXT");
        m.put(0x3C, "WIN_WRITE_VERIFY");
        m.put(0x40, "WIN_VERIFY");
        m.put(0x41, "WIN_VERIFY_ONCE");
        m.put(0x42, "WIN_VERIFY_EXT");
        m.put(0x50, "WIN_FORMAT");
        m.put(0x70, "WIN_SEEK");
        m.put(0x90, "WIN_DIAGNOSE");
        m.put(0x91, "WIN_SPECIFY");
        m.put(0xA0, "WIN_PACKETCMD");
        m.put(0xA1, "WIN_PIDENTIFY");
        m.put(0xB0, "WIN_SMART");
        m.put(0xC4, "WIN_MULTREAD");
        m.put(0xC5, "WIN_MULTWRITE");
        m.put(0xC6, "WIN_SETMULT");
        m.put(0xC7, "WIN_READDMA_QUEUED");
        m.put(0xC8, "WIN_READDMA");
        m.put(0xC9, "WIN_READDMA_ONCE");
        m.put(0xCA, "WIN_WRITEDMA");
        m.put(0xCB, "WIN_WRITEDMA_ONCE");
        m.put(0xCC, "WIN_WRITEDMA_QUEUED");
        m.put(0xE0, "WIN_STANDBYNOW1");
        m.put(0xE1, "WIN_IDLEIMMEDIATE");
        m.put(0xE5, "WIN_CHECKPOWERMODE1");
        m.put(0xE7, "WIN_FLUSH_CACHE");
        m.put(0xEA, "WIN_FLUSH_CACHE_EXT");
        m.put(0xEC, "WIN_IDENTIFY");
        m.put(0xEE, "WIN_IDENTIFY_DMA");
        m.put(0xEF, "WIN_SETFEATURES");
        m.put(0xF8, "WIN_READ_NATIVE_MAX");
        return m;
    }

    private static Map<Integer, String> buildAtapiNames()
    {
        Map<Integer, String> m = new HashMap<>();
        // Names mirror IDEChannel.IDEState.GPCMD_*. SFF-8090 / MMC-6.
        m.put(0x00, "GPCMD_TEST_UNIT_READY");
        m.put(0x03, "GPCMD_REQUEST_SENSE");
        m.put(0x04, "GPCMD_FORMAT_UNIT");
        m.put(0x12, "GPCMD_INQUIRY");
        m.put(0x1B, "GPCMD_START_STOP_UNIT");
        m.put(0x1E, "GPCMD_PREVENT_ALLOW_MEDIUM_REMOVAL");
        m.put(0x25, "GPCMD_READ_CDVD_CAPACITY");
        m.put(0x28, "GPCMD_READ_10");
        m.put(0x2B, "GPCMD_SEEK");
        m.put(0x35, "GPCMD_FLUSH_CACHE");
        m.put(0x42, "GPCMD_READ_SUBCHANNEL");
        m.put(0x43, "GPCMD_READ_TOC_PMA_ATIP");
        m.put(0x45, "GPCMD_PLAY_AUDIO_10");
        m.put(0x46, "GPCMD_GET_CONFIGURATION");
        m.put(0x47, "GPCMD_PLAY_AUDIO_MSF");
        m.put(0x48, "GPCMD_PLAY_AUDIO_TI");
        m.put(0x4A, "GPCMD_GET_EVENT_STATUS_NOTIFICATION");
        m.put(0x4B, "GPCMD_PAUSE_RESUME");
        m.put(0x4E, "GPCMD_STOP_PLAY_SCAN");
        m.put(0x51, "GPCMD_READ_DISC_INFO");
        m.put(0x52, "GPCMD_READ_TRACK_RZONE_INFO");
        m.put(0x55, "GPCMD_MODE_SELECT_10");
        m.put(0x5A, "GPCMD_MODE_SENSE_10");
        m.put(0x5B, "GPCMD_CLOSE_TRACK");
        m.put(0xA1, "GPCMD_BLANK");
        m.put(0xA6, "GPCMD_LOAD_UNLOAD");
        m.put(0xA8, "GPCMD_READ_12");
        m.put(0xAC, "GPCMD_GET_PERFORMANCE");
        m.put(0xAD, "GPCMD_READ_DVD_STRUCTURE");
        m.put(0xB9, "GPCMD_READ_CD_MSF");
        m.put(0xBC, "GPCMD_PLAY_CD");
        m.put(0xBD, "GPCMD_MECHANISM_STATUS");
        m.put(0xBE, "GPCMD_READ_CD");
        return m;
    }
}
