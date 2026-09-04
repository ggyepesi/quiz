package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import objectview.field.FieldSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalizerTest {

    private record StableScalar(String canonical, String rendered)
            implements aux.StableValue {
        @Override public String stableForm() { return canonical; }
        @Override public String toString() { return rendered; }
    }

    /** A minimal Viewable used as a field value (a reference). */
    private record Ref(String id, String label) implements Viewable {
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    private static Canonicalizer.FieldReader reader(Map<String, Object> values) {
        return values::get;
    }

    @Test
    void labelModeKeepsTheFallbackLabel() {
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.LABEL);

        assertEquals("Al Pacino",
                Canonicalizer.displayName(spec, reader(Map.of()), "Al Pacino"));
    }

    @Test
    void fieldModeUsesAReferenceDisplayName() {
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");

        Map<String, Object> fields = Map.of("nominee", new Ref("Q1", "Al Pacino"));

        assertEquals("Al Pacino",
                Canonicalizer.displayName(spec, reader(fields), "GUID-fallback"));
    }

    @Test
    void fieldModeFallsBackWhenFieldIsBlank() {
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");

        assertEquals("fallback",
                Canonicalizer.displayName(spec, reader(new HashMap<>()), "fallback"));
    }

    @Test
    void templateModeInterpolatesFields() {
        CanonicalSpec spec = new CanonicalSpec()
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
    void aSourceIdentifiedClassKeepsTheSourcesId() {
        CanonicalSpec spec = new CanonicalSpec();
        assertEquals("Q42", Canonicalizer.identifier(
                ClassKind.SOURCE, spec, reader(Map.of()), "Q42", "fb"));
    }

    @Test
    void derivedIdentifierJoinsKeyFields() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("nominee");
        spec.keyFields().add("category");
        spec.keyFields().add("year");

        Map<String, Object> fields = new HashMap<>();
        fields.put("nominee", new Ref("Q1", "Al Pacino"));
        fields.put("category", new Ref("Q2", "Best Actor"));
        fields.put("year", "1979");

        // The encoding is framed rather than joined with a separator, so what is
        // asserted is the PROPERTY that matters: the same tuple gives the same id, and
        // a tuple whose components differ only in where a separator would fall does
        // not collide with it. "Q1|Q2" + "1979" and "Q1" + "Q2|1979" used to be one id.
        String identifier = Canonicalizer.identifier(
                ClassKind.STATEMENT, spec, reader(fields), "GUID", "fb");
        assertEquals(identifier, Canonicalizer.identifier(
                ClassKind.STATEMENT, spec, reader(fields), "GUID", "fb"));

        Map<String, Object> shifted = new HashMap<>();
        shifted.put("nominee", new Ref("Q1|Q2", "run together"));
        shifted.put("category", new Ref("1979", "shifted along"));
        shifted.put("year", "");
        assertNotEquals(identifier, Canonicalizer.identifier(
                ClassKind.STATEMENT, spec, reader(shifted), "GUID", "fb"),
                "a separator inside a value no longer reads as the boundary between two");
    }

    @Test
    void aCollectionKeyUsesEveryMemberAndIgnoresOrder() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("laureates");

        String first = Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                reader(Map.of("laureates", List.of(
                        new Ref("Q3", "Donna Strickland"),
                        new Ref("Q1", "Gérard Mourou"),
                        new Ref("Q2", "Arthur Ashkin")))), "statement-1", "fallback");
        String reordered = Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                reader(Map.of("laureates", List.of(
                        new Ref("Q2", "Arthur Ashkin"),
                        new Ref("Q3", "Donna Strickland"),
                        new Ref("Q1", "Gérard Mourou")))), "statement-2", "fallback");

        assertEquals(first, reordered,
                "the members decide it, not the order they arrived in");
    }

    @Test
    void collectionKeysWithDifferentMembersRemainDistinct() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("laureates");

        String shared = Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                reader(Map.of("laureates", List.of(new Ref("Q1", "A"), new Ref("Q2", "B")))),
                "statement-1", "fallback");
        String solo = Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                reader(Map.of("laureates", List.of(new Ref("Q1", "A")))),
                "statement-2", "fallback");

        assertFalse(shared.equals(solo));
    }

    @Test
    void aValueTypesStableFormWinsOverItsRendering() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("year");

        // The stable form still decides the identity; it is framed rather than
        // returned raw, so this asserts that two values with the same stable form agree
        // and the rendering does not enter into it.
        assertEquals(
                Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                        reader(Map.of("year", new StableScalar(
                                "canonical-date", "friendly rendered date"))),
                        "statement", "fallback"),
                Canonicalizer.identifier(ClassKind.STATEMENT, spec,
                        reader(Map.of("year", new StableScalar(
                                "canonical-date", "a different rendering entirely"))),
                        "statement", "fallback"),
                "the same stable form is the same identity, however it is displayed");
    }

    @Test
    void derivedIdentifierFallsBackWhenNoKeyResolves() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("missing");
        assertEquals("GUID", Canonicalizer.identifier(
                ClassKind.STATEMENT, spec, reader(new HashMap<>()), "x", "GUID"));
    }

    @Test
    void ownedIdentifierKeepsTheCompositionIdentityRatherThanUsingFieldKeys() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.keyFields().add("familyName");

        assertEquals("Name@Person.birthName|Q42", Canonicalizer.identifier(
                ClassKind.OWNED, spec, reader(Map.of("familyName", "Adams")),
                "Q42", "Name@Person.birthName|Q42"));
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
        // from CanonicalSpec + the generated @Hidden `name`. A data field
        // named name/qid was renamed away, so nothing is left that isNameField.
        long identityNames = c.fields().stream()
                .filter(GeneratedFieldModel::isNameField)
                .count();
        assertEquals(0, identityNames, "no vestigial model-level name field");
    }

    @Test void reservedIdentityNamesAreExposedForEditors() {
        assertTrue(GeneratedClassModel.isReservedFieldName("name"));
        assertTrue(GeneratedClassModel.isReservedFieldName(" QID "));
        assertFalse(GeneratedClassModel.isReservedFieldName("structuredName"));
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
