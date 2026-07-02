package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import quiz.Quizable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalizerTest {

    /** A minimal Quizable used as a field value (a reference). */
    private record Ref(String id, String label) implements Quizable {
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
        @Override public boolean hasField(String f) { return false; }
        @Override public boolean hasAnyField() { return false; }
        @Override public boolean hasFields(Collection<String> f) { return false; }
        @Override public HashMap<List<Object>, Quizable> generateUniqueCombinations(List<String> f) {
            return new HashMap<>();
        }
        @Override public Quizable project(List<String> f, List<Object> v) { return this; }
    }

    private static Canonicalizer.FieldReader reader(Map<String, Object> values) {
        return values::get;
    }

    @Test
    void labelModeKeepsTheFallbackLabel() {
        CanonicalSpec spec = new CanonicalSpec()
                .kind(CanonicalSpec.Kind.WIKIDATA_ENTITY)
                .displayNameMode(CanonicalSpec.DisplayNameMode.LABEL);

        assertEquals("Al Pacino",
                Canonicalizer.displayName(spec, reader(Map.of()), "Al Pacino"));
    }

    @Test
    void fieldModeUsesAReferenceDisplayName() {
        CanonicalSpec spec = new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");

        Map<String, Object> fields = Map.of("nominee", new Ref("Q1", "Al Pacino"));

        assertEquals("Al Pacino",
                Canonicalizer.displayName(spec, reader(fields), "GUID-fallback"));
    }

    @Test
    void fieldModeFallsBackWhenFieldIsBlank() {
        CanonicalSpec spec = new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");

        assertEquals("fallback",
                Canonicalizer.displayName(spec, reader(new HashMap<>()), "fallback"));
    }

    @Test
    void templateModeInterpolatesFields() {
        CanonicalSpec spec = new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{nominee} · {category} {year}");

        Map<String, Object> fields = new HashMap<>();
        fields.put("nominee", new Ref("Q1", "Al Pacino"));
        fields.put("category", new Ref("Q2", "Best Actor"));
        fields.put("year", "1979");

        assertEquals("Al Pacino · Best Actor 1979",
                Canonicalizer.displayName(spec, reader(fields), "fb"));
    }

    @Test
    void entityIdentifierIsTheQid() {
        CanonicalSpec spec = new CanonicalSpec().kind(CanonicalSpec.Kind.WIKIDATA_ENTITY);
        assertEquals("Q42",
                Canonicalizer.identifier(spec, reader(Map.of()), "Q42", "fb"));
    }

    @Test
    void derivedIdentifierJoinsKeyFields() {
        CanonicalSpec spec = new CanonicalSpec().kind(CanonicalSpec.Kind.DERIVED);
        spec.keyFields().add("nominee");
        spec.keyFields().add("category");
        spec.keyFields().add("year");

        Map<String, Object> fields = new HashMap<>();
        fields.put("nominee", new Ref("Q1", "Al Pacino"));
        fields.put("category", new Ref("Q2", "Best Actor"));
        fields.put("year", "1979");

        assertEquals("Q1|Q2|1979",
                Canonicalizer.identifier(spec, reader(fields), "GUID", "fb"));
    }

    @Test
    void derivedIdentifierFallsBackWhenNoKeyResolves() {
        CanonicalSpec spec = new CanonicalSpec().kind(CanonicalSpec.Kind.DERIVED);
        spec.keyFields().add("missing");
        assertEquals("GUID",
                Canonicalizer.identifier(spec, reader(new HashMap<>()), "x", "GUID"));
    }

    @Test
    void dataFieldsNamedNameOrQidAreReservedAndRenamed() {
        GeneratedClassModel c = new GeneratedClassModel("Person");

        GeneratedFieldModel nameData =
                c.addField("name", FieldType.STRING, FieldCardinality.SINGLE);
        GeneratedFieldModel qidData =
                c.addField("qid", FieldType.STRING, FieldCardinality.SINGLE);

        assertEquals("nameValue", nameData.name());
        assertEquals("qidValue", qidData.name());

        // After 3d there is NO model-level `name` field: identity/display name comes
        // from CanonicalSpec + the generated @NotQuizableField `name`. A data field
        // named name/qid was renamed away, so nothing is left that isNameField.
        long identityNames = c.fields().stream()
                .filter(GeneratedFieldModel::isNameField)
                .count();
        assertEquals(0, identityNames, "no vestigial model-level name field");
    }

    @Test
    void copyDropsAVestigialLegacyNameField() {
        // A model loaded from a pre-canonicalization file may still carry a `name`
        // field; copy() (the generation path) drops it so it can't resurface.
        GeneratedClassModel c = new GeneratedClassModel("Legacy");
        c.fields().add(GeneratedFieldModel.nameField());   // simulate a legacy name field
        assertEquals(1, c.fields().stream().filter(GeneratedFieldModel::isNameField).count());

        GeneratedClassModel copy = c.copy();
        assertEquals(0, copy.fields().stream()
                .filter(GeneratedFieldModel::isNameField).count(),
                "copy strips the legacy name field");
    }
}
