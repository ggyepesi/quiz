package canonical;

import java.util.List;
import java.util.Map;

/**
 * One modeled instance after keying and reduction, retaining what produced it.
 *
 * <p>The modeled {@link #key} is deliberately separate from source, occurrence and
 * owner/site identities. That separation is what permits several source candidates to
 * become one modeled instance without losing the identifiers needed to revisit them.
 */
public record CanonicalInstance(
        String className,
        String key,
        Map<String, Object> values,
        int candidateCount,
        List<String> sourceIdentities,
        List<String> occurrenceIdentities,
        List<String> ownerSiteIdentities,
        List<Candidate> candidates) {

    public CanonicalInstance {
        className = className == null ? "" : className;
        key = key == null ? "" : key;
        values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                values == null ? Map.of() : values));
        sourceIdentities = List.copyOf(sourceIdentities == null ? List.of() : sourceIdentities);
        occurrenceIdentities = List.copyOf(
                occurrenceIdentities == null ? List.of() : occurrenceIdentities);
        ownerSiteIdentities = List.copyOf(
                ownerSiteIdentities == null ? List.of() : ownerSiteIdentities);
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }
}
