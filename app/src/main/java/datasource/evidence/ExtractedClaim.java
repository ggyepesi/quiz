package datasource.evidence;

import datasource.EntityRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A source assertion, independent of the domain field to which it may be mapped. */
public record ExtractedClaim(
        EntityRef subject,
        String semanticProperty,
        Object proposedValue,
        EntityRef proposedEntity,
        List<EvidenceFragment> evidence,
        String extractionMethod,
        String recipeVersion,
        String modelFingerprint,
        double confidence,
        List<String> warnings) {
    public ExtractedClaim {
        subject = Objects.requireNonNull(subject, "Claim subject is required");
        semanticProperty = required(semanticProperty, "Semantic property is required");
        if (proposedValue == null && proposedEntity == null) {
            throw new IllegalArgumentException("Claim value or entity is required");
        }
        stableValue(proposedValue); // reject values whose identity cannot be persisted reliably
        evidence = evidence == null ? List.of() : evidence.stream()
                .filter(Objects::nonNull).toList();
        if (evidence.isEmpty()) throw new IllegalArgumentException("Claim evidence is required");
        extractionMethod = required(extractionMethod, "Extraction method is required");
        recipeVersion = required(recipeVersion, "Recipe version is required");
        modelFingerprint = text(modelFingerprint);
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
        warnings = warnings == null ? List.of() : warnings.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /** Stable identity of the source assertion, excluding its later assessment. */
    public String claimId() {
        return digest(material(false));
    }

    /** Stable identity of exactly what the reviewer saw and accepted. */
    public String assessmentId() {
        return digest(material(true));
    }

    public EvidenceStatus statusAgainst(SourceDocument currentDocument,
                                        String currentRecipeVersion,
                                        String currentModelFingerprint) {
        return statusAgainstDocuments(currentDocument == null ? Map.of()
                        : Map.of(currentDocument.documentId(), currentDocument),
                currentRecipeVersion, currentModelFingerprint);
    }

    /** Revalidates every supporting document; one current fragment cannot hide another
     * missing or revised fragment in a multi-document claim. */
    public EvidenceStatus statusAgainstDocuments(
            Map<String, SourceDocument> currentDocuments,
            String currentRecipeVersion,
            String currentModelFingerprint) {
        if (currentDocuments == null) currentDocuments = Map.of();
        for (EvidenceFragment fragment : evidence) {
            SourceDocument current = currentDocuments.get(fragment.document().documentId());
            if (current == null) return EvidenceStatus.SOURCE_UNAVAILABLE;
            if (!fragment.document().sameVersion(current)) return EvidenceStatus.STALE_SOURCE;
        }
        if (!text(currentRecipeVersion).isBlank()
                && !recipeVersion.equals(text(currentRecipeVersion))) {
            return EvidenceStatus.STALE_RECIPE;
        }
        if (!text(currentModelFingerprint).isBlank()
                && !modelFingerprint.equals(text(currentModelFingerprint))) {
            return EvidenceStatus.STALE_MODEL;
        }
        return EvidenceStatus.CURRENT;
    }

    private String material(boolean assessment) {
        StringBuilder out = new StringBuilder()
                .append(subject.qualifiedId()).append('\0')
                .append(semanticProperty).append('\0')
                .append(proposedEntity == null ? "" : proposedEntity.qualifiedId()).append('\0')
                .append(stableValue(proposedValue)).append('\0');
        evidence.stream().sorted(Comparator.comparing(f -> f.document().documentId()
                        + '\0' + f.document().versionId() + '\0' + f.startOffset()))
                .forEach(f -> out.append(f.document().documentId()).append('\0')
                        .append(f.document().versionId()).append('\0').append(f.section())
                        .append('\0').append(f.startOffset()).append('\0').append(f.endOffset())
                        .append('\0').append(f.excerpt()).append('\0'));
        if (assessment) {
            out.append(extractionMethod).append('\0').append(recipeVersion).append('\0')
                    .append(modelFingerprint).append('\0').append(confidence).append('\0');
            warnings.forEach(w -> out.append(w).append('\0'));
        }
        return out.toString();
    }

    private static String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Whether a value can be carried as a literal at all: a producer asks HERE rather
     *  than keeping its own list of accepted types, which would decide silently and
     *  drift. A value this rejects belongs in {@link #proposedEntity}. */
    public static boolean isStableValue(Object value) {
        if (value == null) return false; // null is only valid alongside proposedEntity
        try {
            stableValue(value);
            return true;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    /** Typed semantic equality for two literal claim values. This deliberately does not
     * equate text "1" with number 1 merely because their display strings match. */
    public static boolean sameStableValue(Object first, Object second) {
        if (!isStableValue(first) || !isStableValue(second)) return false;
        return stableValue(first).equals(stableValue(second));
    }

    /** A deterministic representation used only for lineage identity. A domain object is
     * represented by {@link #proposedEntity}, and a value type that knows its canonical
     * text says so through {@link aux.StableValue} — never a process-local toString(). */
    private static String stableValue(Object value) {
        if (value == null) return "null";
        if (value instanceof aux.StableValue stable) {
            String form = stable.stableForm();
            if (form == null) {
                throw new IllegalArgumentException("Stable value returned null: "
                        + value.getClass().getName());
            }
            return value.getClass().getName() + ':' + form;
        }
        if (value instanceof CharSequence || value instanceof Number
                || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?> || value instanceof java.net.URI
                || value instanceof java.time.temporal.TemporalAccessor) {
            return value.getClass().getName() + ':' + value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> entries = new LinkedHashMap<>();
            map.forEach((key, item) -> entries.put(stableValue(key), stableValue(item)));
            return entries.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> values) {
            java.util.stream.Stream<String> serialized = values.stream()
                    .map(ExtractedClaim::stableValue);
            if (value instanceof java.util.Set<?>) serialized = serialized.sorted();
            return serialized
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        throw new IllegalArgumentException("Unsupported evidence value type "
                + value.getClass().getName()
                + "; represent entity values with proposedEntity");
    }

    private static String required(String value, String message) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
