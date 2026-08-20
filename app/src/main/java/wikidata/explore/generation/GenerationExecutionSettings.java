package wikidata.explore.generation;

import wikidata.api.WikidataFactStore;

import java.util.prefs.Preferences;

/** Persistent, run-scoped execution policy. It changes cost and failure handling,
 * never the model or the meaning of a generated domain. */
public final class GenerationExecutionSettings {
    public enum MemoryProfile { AUTO, LOW, BALANCED, LARGE, CUSTOM }
    public enum NetworkProfile {
        GENTLE(2), BALANCED(6), FAST(10);
        private final int concurrency;
        NetworkProfile(int concurrency) { this.concurrency = concurrency; }
        public int concurrency() { return concurrency; }
    }

    private static final Preferences PREFS = Preferences.userNodeForPackage(
            GenerationExecutionSettings.class);
    // Written from the EDT while the plan is open, read by the run thread once it
    // starts. One shared instance, so the values have to be published.
    private volatile MemoryProfile memoryProfile;
    private volatile NetworkProfile networkProfile;
    private volatile int customMemoryMb;
    private volatile boolean requireComplete;
    private final boolean persist;

    public GenerationExecutionSettings() {
        this(true);
    }

    /** {@code persist=false} neither writes nor READS the stored profile: a test that
     *  inherited whichever profile this developer last chose would assert against a
     *  different machine's settings. */
    GenerationExecutionSettings(boolean persist) {
        this.persist = persist;
        memoryProfile = persist ? enumValue(MemoryProfile.class,
                PREFS.get("memoryProfile", MemoryProfile.AUTO.name()), MemoryProfile.AUTO)
                : MemoryProfile.AUTO;
        networkProfile = persist ? enumValue(NetworkProfile.class,
                PREFS.get("networkProfile", NetworkProfile.BALANCED.name()),
                NetworkProfile.BALANCED) : NetworkProfile.BALANCED;
        customMemoryMb = persist ? clampMemory(PREFS.getInt("customMemoryMb", 768)) : 768;
        requireComplete = !persist || PREFS.getBoolean("requireComplete", true);
    }

    public MemoryProfile memoryProfile() { return memoryProfile; }
    public void memoryProfile(MemoryProfile value) {
        memoryProfile = value == null ? MemoryProfile.AUTO : value; save();
    }
    public NetworkProfile networkProfile() { return networkProfile; }
    public void networkProfile(NetworkProfile value) {
        networkProfile = value == null ? NetworkProfile.BALANCED : value; save();
    }
    public int customMemoryMb() { return customMemoryMb; }
    public void customMemoryMb(int value) { customMemoryMb = clampMemory(value); save(); }
    public boolean requireComplete() { return requireComplete; }
    public void requireComplete(boolean value) { requireComplete = value; save(); }
    public int concurrency() { return networkProfile.concurrency(); }

    public int resolvedMemoryMb() {
        return switch (memoryProfile) {
            case LOW -> 384;
            case BALANCED -> 768;
            case LARGE -> 1536;
            case CUSTOM -> customMemoryMb;
            case AUTO -> {
                long heapMb = Runtime.getRuntime().maxMemory() / 1024L / 1024L;
                yield (int) Math.max(256L, Math.min(768L, heapMb / 4L));
            }
        };
    }

    public WikidataFactStore newFactStore() {
        return new WikidataFactStore(resolvedMemoryMb() * 1024L * 1024L);
    }

    private void save() {
        if (!persist) return;
        PREFS.put("memoryProfile", memoryProfile.name());
        PREFS.put("networkProfile", networkProfile.name());
        PREFS.putInt("customMemoryMb", customMemoryMb);
        PREFS.putBoolean("requireComplete", requireComplete);
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int clampMemory(int value) {
        return Math.max(64, Math.min(8192, value));
    }
}
