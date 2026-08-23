package datasource.enrichment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral measurement of discovery work, before the reader applies decisions.
 *
 * <p>No {@code completed}: every examination ends completed or failed, so it was
 * {@code examined - failed} — a third number carrying one invariant that nothing
 * enforced, summed independently, and read by nobody.
 *
 * <p>The usable candidates are KEPT rather than counted. A count cannot be opened, and
 * the filtering that produced it already had the list in hand; retaining it is what lets
 * "Wikidata: 12 usable" be a bucket a reader can look inside rather than a number they
 * have to believe.
 */
public record SourceYield(
        String source,
        int examined,
        int failed,
        int skipped,
        int candidates,
        List<EnrichmentProposal.FieldCandidate> usableFields,
        List<EnrichmentProposal.MediaCandidate> usableMedia,
        int corroborations,
        long elapsedMillis) {

    public SourceYield {
        source = source == null || source.isBlank() ? "Unknown source" : source.trim();
        // Counts, from a counter that only increments. A negative one is a defect in
        // whatever measured, and clamping it to zero would report plausible work.
        count(examined, "examined");
        count(failed, "failed");
        count(skipped, "skipped");
        count(candidates, "candidates");
        count(corroborations, "corroborations");
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis cannot be negative");
        }
        if (failed > examined) {
            throw new IllegalArgumentException(
                    "failed (" + failed + ") cannot exceed examined (" + examined + ")");
        }
        usableFields = List.copyOf(usableFields == null ? List.of() : usableFields);
        usableMedia = List.copyOf(usableMedia == null ? List.of() : usableMedia);
    }

    public static List<SourceYield> aggregate(Collection<SourceYield> values) {
        Map<String, SourceYield> totals = new LinkedHashMap<>();
        if (values != null) for (SourceYield value : values) {
            if (value == null) continue;
            totals.merge(value.source(), value, SourceYield::plus);
        }
        return List.copyOf(totals.values());
    }

    public SourceYield plus(SourceYield other) {
        if (other == null) return this;
        if (!source.equals(other.source)) {
            throw new IllegalArgumentException("Cannot combine different sources");
        }
        return new SourceYield(source, examined + other.examined,
                failed + other.failed, skipped + other.skipped,
                candidates + other.candidates,
                join(usableFields, other.usableFields),
                join(usableMedia, other.usableMedia),
                corroborations + other.corroborations,
                elapsedMillis + other.elapsedMillis);
    }

    /** What every examination that did not fail produced. */
    public int completed() { return examined - failed; }

    /** Derived from the candidates themselves, so the number and the list it summarizes
     *  cannot come apart. */
    public int usableChanges() { return usableFields.size() + usableMedia.size(); }

    public String summary() {
        return source + ": " + examined + " examined, " + usableChanges() + " usable, "
                + corroborations + " corroboration(s), " + candidates + " candidate(s), "
                + failed + " failed, " + skipped + " skipped, " + elapsedMillis + " ms";
    }

    private static <T> List<T> join(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static void count(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
    }
}
