/*
    JPC: An x86 PC Hardware Emulator for a pure Java Virtual Machine

    Diagnostic helper for inc 0008 (B7-followup): logs every code path that
    can return a null Memory reference in PhysicalAddressSpace and
    LinearAddressSpace. Activated by the -log-tlb-null CLI flag.
 */
package org.jpc.emulator.memory;

import org.jpc.j2se.Option;

/**
 * Centralised, throttled logger used to diagnose where TLB / physical
 * address space lookups silently produce a {@code null} {@link Memory}
 * reference. The logger is a no-op unless {@code -log-tlb-null} is set on
 * the JPC command line.
 *
 * <p>To avoid drowning the boot log we cap the number of distinct messages
 * (per category) printed; once the cap is reached further occurrences are
 * counted but suppressed. The summary can be printed via {@link #summary()}.
 */
public final class TlbNullLogger
{
    private static final int MAX_MESSAGES_PER_CATEGORY = 16;

    private static final java.util.concurrent.atomic.AtomicInteger physBlockNullCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger linearReadNullCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger linearWriteNullCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private TlbNullLogger() {}

    public static boolean enabled()
    {
        return Option.log_tlb_null.isSet();
    }

    public static void physBlockNull(int physAddr, int topIdx, int botIdx)
    {
        if (!enabled()) return;
        int n = physBlockNullCount.incrementAndGet();
        if (n <= MAX_MESSAGES_PER_CATEGORY) {
            System.err.printf(
                    "[tlb-null] PhysicalAddressSpace.getMemoryBlockAt: index[0x%x][0x%x] is null, phys=0x%08x (#%d)%n",
                    topIdx, botIdx, physAddr, n);
        } else if (n == MAX_MESSAGES_PER_CATEGORY + 1) {
            System.err.println("[tlb-null] further PhysicalAddressSpace null returns suppressed; see summary()");
        }
    }

    public static void linearReadNull(int linearAddr, String path,
                                      int cr3, boolean isSupervisor)
    {
        if (!enabled()) return;
        int n = linearReadNullCount.incrementAndGet();
        if (n <= MAX_MESSAGES_PER_CATEGORY) {
            System.err.printf(
                    "[tlb-null] LinearAddressSpace.validateTLBEntryRead returned null, lin=0x%08x path=%s cr3=0x%08x sv=%s (#%d)%n",
                    linearAddr, path, cr3, isSupervisor, n);
        } else if (n == MAX_MESSAGES_PER_CATEGORY + 1) {
            System.err.println("[tlb-null] further LinearAddressSpace read-null returns suppressed; see summary()");
        }
    }

    public static void linearWriteNull(int linearAddr, String path,
                                       int cr3, boolean isSupervisor)
    {
        if (!enabled()) return;
        int n = linearWriteNullCount.incrementAndGet();
        if (n <= MAX_MESSAGES_PER_CATEGORY) {
            System.err.printf(
                    "[tlb-null] LinearAddressSpace.validateTLBEntryWrite returned null, lin=0x%08x path=%s cr3=0x%08x sv=%s (#%d)%n",
                    linearAddr, path, cr3, isSupervisor, n);
        } else if (n == MAX_MESSAGES_PER_CATEGORY + 1) {
            System.err.println("[tlb-null] further LinearAddressSpace write-null returns suppressed; see summary()");
        }
    }

    public static String summary()
    {
        return String.format(
                "[tlb-null summary] phys-null=%d linear-read-null=%d linear-write-null=%d",
                physBlockNullCount.get(),
                linearReadNullCount.get(),
                linearWriteNullCount.get());
    }

    static void resetForTesting()
    {
        physBlockNullCount.set(0);
        linearReadNullCount.set(0);
        linearWriteNullCount.set(0);
    }
}
