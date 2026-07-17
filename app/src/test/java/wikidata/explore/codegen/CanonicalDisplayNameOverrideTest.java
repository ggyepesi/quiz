package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;
import quiz.Quizable;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.Canonicalizer;
import wikidata.explore.model.GeneratedClassModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The mapper's decision to override displayName from a class's CanonicalSpec —
 * tested without the codegen runtime (that's exercised end-to-end when you
 * regenerate). Confirms: only an EXPLICIT derived spec applies, and a nested
 * reference resolves via its displayName.
 */
class CanonicalDisplayNameOverrideTest {

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
    void explicitDerivedFieldSpecOverridesWithReferenceLabel() {
        GeneratedClassModel model = new GeneratedClassModel("Nomination");
        model.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee"));

        Map<String, Object> fields = Map.of("nominee", new Ref("Q1", "Al Pacino"));

        String dn = GeneratedQuizableMapper.canonicalDisplayNameOverride(
                model, reader(fields), "Q123-GUID");

        assertEquals("Al Pacino", dn);
    }

    @Test
    void legacyModelWithoutExplicitSpecDoesNotOverride() {
        // reifiesStatements would make effectiveCanonical() DERIVED, but with no
        // EXPLICIT spec we must leave the loaded label alone (safe migration).
        GeneratedClassModel model = new GeneratedClassModel("Nomination");
        model.statementSourceClass("OscarNominations");

        assertNull(GeneratedQuizableMapper.canonicalDisplayNameOverride(
                model, reader(Map.of("nominee", new Ref("Q1", "Al Pacino"))), "label"));
    }

    @Test
    void entityLabelSpecDoesNotOverride() {
        GeneratedClassModel model = new GeneratedClassModel("Person");
        model.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.WIKIDATA_ENTITY)
                .displayNameMode(CanonicalSpec.DisplayNameMode.LABEL));

        assertNull(GeneratedQuizableMapper.canonicalDisplayNameOverride(
                model, reader(Map.of()), "Al Pacino"));
    }

    @Test
    void blankResolutionLeavesDefault() {
        GeneratedClassModel model = new GeneratedClassModel("Nomination");
        model.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("missing"));

        // Field absent -> Canonicalizer returns the fallback -> override equals the
        // fallback, which is a no-op signal (non-null but == default is fine); here
        // we assert it doesn't invent something and simply yields the fallback.
        assertEquals("label", GeneratedQuizableMapper.canonicalDisplayNameOverride(
                model, reader(new HashMap<>()), "label"));
    }
}
