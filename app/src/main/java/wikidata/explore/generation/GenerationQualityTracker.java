package wikidata.explore.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attempt history plus final-state reconciliation. Failures are recorded when they
 * happen, but the final verdict is derived only from identities still unresolved after
 * convergence. A retry may therefore repair an earlier failure without erasing history.
 */
public final class GenerationQualityTracker {
    public record Attempt(String key, String message, Set<String> identities,
                          boolean failure) { }

    private final List<Attempt> history = new ArrayList<>();
    private final Map<String, LinkedHashSet<String>> unresolved = new LinkedHashMap<>();
    private final Map<String, String> messages = new LinkedHashMap<>();

    public synchronized void failed(String key, String message,
                                    Collection<String> identities) {
        String stableKey = clean(key, "generation");
        LinkedHashSet<String> ids = cleanIdentities(identities);
        history.add(new Attempt(stableKey, clean(message, stableKey), Set.copyOf(ids), true));
        unresolved.computeIfAbsent(stableKey, ignored -> new LinkedHashSet<>()).addAll(ids);
        messages.put(stableKey, clean(message, stableKey));
    }

    public synchronized void resolved(String key, Collection<String> identities) {
        String stableKey = clean(key, "generation");
        LinkedHashSet<String> ids = cleanIdentities(identities);
        history.add(new Attempt(stableKey, "resolved", Set.copyOf(ids), false));
        LinkedHashSet<String> pending = unresolved.get(stableKey);
        if (pending != null) {
            pending.removeAll(ids);
            if (pending.isEmpty()) unresolved.remove(stableKey);
        }
    }

    /** A non-identity failure cannot be repaired implicitly; use its stable key as the
     * identity and resolve it explicitly if a later iteration succeeds. */
    public void failed(String key, String message) {
        failed(key, message, List.of("@" + clean(key, "generation")));
    }

    public synchronized List<Attempt> history() { return List.copyOf(history); }

    public synchronized GenerationRun.Quality quality() {
        if (unresolved.isEmpty()) return GenerationRun.Quality.completeQuality();
        List<String> warnings = new ArrayList<>();
        LinkedHashSet<String> qids = new LinkedHashSet<>();
        unresolved.forEach((key, ids) -> {
            warnings.add(messages.getOrDefault(key, key) + " (" + ids.size()
                    + " unresolved)");
            ids.stream().filter(id -> id.matches("(?i)Q\\d+")).forEach(qids::add);
        });
        return GenerationRun.Quality.partial(warnings, List.copyOf(qids));
    }

    private static LinkedHashSet<String> cleanIdentities(Collection<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values != null) values.stream().filter(v -> v != null && !v.isBlank())
                .forEach(out::add);
        return out;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
