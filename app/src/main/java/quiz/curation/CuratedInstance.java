package quiz.curation;

import java.util.List;

/**
 * One explicitly accepted instance that did not exist in the generated source population.
 *
 * <p>The instance is persisted in the same curation sidecar as the reviewed field decision
 * that points at it. Its identity is supplied by the producing evidence recipe; field values
 * stay typed, and references retain both their declared type and identifier so replay never
 * has to infer a target from a label.</p>
 */
public record CuratedInstance(
        String type,
        String id,
        String displayName,
        List<FieldValue> fields,
        ValueSource source) {

    public CuratedInstance {
        type = required(type, "Curated instance type is required");
        id = required(id, "Curated instance identifier is required");
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        fields = fields == null ? List.of() : fields.stream()
                .filter(java.util.Objects::nonNull).toList();
    }

    public record FieldValue(String field, Object value, List<Reference> references) {
        public FieldValue {
            field = required(field, "Curated instance field is required");
            references = references == null ? List.of() : references.stream()
                    .filter(java.util.Objects::nonNull).toList();
            if (value != null && !references.isEmpty()) {
                throw new IllegalArgumentException(
                        "A curated field is either a literal value or references, not both");
            }
        }

        public static FieldValue literal(String field, Object value) {
            return new FieldValue(field, value, List.of());
        }

        public static FieldValue references(String field, List<Reference> values) {
            return new FieldValue(field, null, values);
        }
    }

    public record Reference(String type, String id) {
        public Reference {
            type = required(type, "Reference type is required");
            id = required(id, "Reference identifier is required");
        }
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
