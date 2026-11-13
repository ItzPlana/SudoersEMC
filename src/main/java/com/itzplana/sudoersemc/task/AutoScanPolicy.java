package com.itzplana.sudoersemc.task;

/**
 * Pure policy decision for auto-scanning at server startup.
 *
 * <p>This class depends on no Minecraft type; it answers "should this startup request a scan" based only
 * on config switches, the last scan time and the number of generated entries. The actual scan is still
 * scheduled by {@link ScanManager}; this class has no side effects.</p>
 */
public final class AutoScanPolicy {

    private AutoScanPolicy() {
    }

    /**
     * Whether an auto scan should be requested at server startup.
     *
     * <p>Fixed decision order:</p>
     * <ol>
     *   <li>{@code enabled == false}: the auto-scan master switch is off, return {@code false};</li>
     *   <li>{@code alwaysRescan == true}: the admin requires a rescan on every startup, return {@code true};</li>
     *   <li>{@code lastScanEpochMillis <= 0}: never scanned, return {@code true};</li>
     *   <li>{@code generatedEntryCount <= 0}: no generated entries at all (e.g. cache missing or cleared),
     *       return {@code true};</li>
     *   <li>otherwise a usable cache is assumed, return {@code false}.</li>
     * </ol>
     *
     * @param enabled             auto-scan master switch
     * @param alwaysRescan        whether to force a rescan on every startup
     * @param lastScanEpochMillis epoch millis of the last completed scan; {@code 0} means never scanned
     * @param generatedEntryCount current number of generated entries
     * @return {@code true} when an auto scan should be requested
     */
    public static boolean shouldScan(boolean enabled, boolean alwaysRescan,
                                     long lastScanEpochMillis, int generatedEntryCount) {
        // Older callers cannot compute a fingerprint; keep Phase 22 behaviour: skip when a cache exists.
        return shouldScan(enabled, alwaysRescan, lastScanEpochMillis, generatedEntryCount, true);
    }

    /**
     * Whether an auto scan should be requested at server startup, taking into account whether the
     * current input fingerprint changed.
     *
     * <p>Fixed decision order:</p>
     * <ol>
     *   <li>{@code enabled == false}: the auto-scan master switch is off, return {@code false};</li>
     *   <li>{@code alwaysRescan == true}: the admin requires a rescan on every startup, return {@code true};</li>
     *   <li>{@code lastScanEpochMillis <= 0}: never scanned, return {@code true};</li>
     *   <li>{@code generatedEntryCount <= 0}: no generated entries at all, return {@code true};</li>
     *   <li>{@code fingerprintsMatch == false}: items, recipes, tags, config or external baseline changed,
     *       return {@code true};</li>
     *   <li>otherwise inputs are unchanged and a usable cache exists, return {@code false}.</li>
     * </ol>
     *
     * <p>When fingerprint computation fails the caller should pass {@code false}, degrading this method
     * to "scan needed"; it never blocks startup.</p>
     *
     * @param enabled              auto-scan master switch
     * @param alwaysRescan         whether to force a rescan on every startup
     * @param lastScanEpochMillis  epoch millis of the last completed scan; {@code 0} means never scanned
     * @param generatedEntryCount  current number of generated entries
     * @param fingerprintsMatch    whether the current input fingerprint exactly matches the saved one
     * @return {@code true} when an auto scan should be requested
     */
    public static boolean shouldScan(boolean enabled, boolean alwaysRescan,
                                     long lastScanEpochMillis, int generatedEntryCount,
                                     boolean fingerprintsMatch) {
        if (!enabled) {
            return false;
        }
        if (alwaysRescan) {
            return true;
        }
        if (lastScanEpochMillis <= 0L) {
            return true;
        }
        if (generatedEntryCount <= 0) {
            return true;
        }
        return !fingerprintsMatch;
    }
}
