package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalSpecTest {

    @Test
    void plainEntityClassInfersWikidataIdentityAndLabel() {
        GeneratedClassModel entity = new GeneratedClassModel("Person");

        assertNull(entity.canonical(), "a fresh class has no explicit spec");

        CanonicalSpec spec = entity.effectiveCanonical();
        assertEquals(CanonicalSpec.Kind.WIKIDATA_ENTITY, spec.kind());
        assertEquals(CanonicalSpec.DisplayNameMode.LABEL, spec.displayNameMode());
        assertEquals("en", spec.labelLanguage());
    }

    @Test
    void reifiedClassInfersDerivedGrainAndFieldDisplayName() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSourceClass("OscarNominations");   // => reified/derived

        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nomination.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nomination.addField("year", FieldType.STRING, FieldCardinality.SINGLE);
        // A collection field must NOT be a key field or the displayName.
        nomination.addField("otherNominees", FieldType.ENTITY, FieldCardinality.COLLECTION);

        CanonicalSpec spec = nomination.effectiveCanonical();

        assertEquals(CanonicalSpec.Kind.DERIVED, spec.kind());
        assertEquals(CanonicalSpec.DisplayNameMode.FIELD, spec.displayNameMode());
        assertEquals("nominee", spec.displayNameField(),
                "first single-valued non-name field is the proposed displayName");

        assertTrue(spec.keyFields().contains("nominee"));
        assertTrue(spec.keyFields().contains("category"));
        assertTrue(spec.keyFields().contains("year"));
        assertFalse(spec.keyFields().contains("otherNominees"),
                "a COLLECTION field can't be a natural-key field");
        assertFalse(spec.keyFields().contains("name"),
                "the identity name field is never a key field");
    }

    @Test
    void inferredKeyExcludesDerivedFields() {
        // Derived fields (COMPANION_MATCH like `won`, INVERT) are produced AFTER
        // reify — they must not enter the identity key, or e.g. won=null vs won=<x>
        // would split the two denormalized copies of one statement.
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("OscarNominations");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.COMPANION_MATCH);

        CanonicalSpec spec = nom.effectiveCanonical();
        assertTrue(spec.keyFields().contains("category"));
        assertFalse(spec.keyFields().contains("won"),
                "a COMPANION_MATCH (derived) field must not be in the identity key");
    }

    @Test
    void reDeriveClearsAStaleSavedSpec() {
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("OscarNominations");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);

        // A stale saved spec from an earlier field set.
        CanonicalSpec stale = new CanonicalSpec().kind(CanonicalSpec.Kind.DERIVED);
        stale.keyFields().add("removedField");
        nom.canonical(stale);
        assertTrue(nom.effectiveCanonical().keyFields().contains("removedField"),
                "a saved spec is used as-is");

        // "Re-derive identity" = clear the saved spec → inferred fresh from fields.
        nom.canonical(null);
        CanonicalSpec fresh = nom.effectiveCanonical();
        assertFalse(fresh.keyFields().contains("removedField"));
        assertTrue(fresh.keyFields().contains("category"));
        assertTrue(fresh.keyFields().contains("nominee"));
    }

    @Test
    void explicitSpecOverridesInference() {
        GeneratedClassModel c = new GeneratedClassModel("Nomination");
        c.statementSourceClass("OscarNominations");

        CanonicalSpec explicit = new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE)
                .displayNameTemplate("{nominee} · {year}");
        explicit.keyFields().add("nominee");
        c.canonical(explicit);

        assertTrue(c.hasCanonical());
        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                c.effectiveCanonical().displayNameMode());
        assertEquals("{nominee} · {year}",
                c.effectiveCanonical().displayNameTemplate());
    }

    @Test
    void copyDeepCopiesCanonical() {
        GeneratedClassModel c = new GeneratedClassModel("Nomination");
        c.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee"));
        c.canonical().keyFields().add("nominee");

        GeneratedClassModel copy = c.copy();
        copy.canonical().keyFields().add("category");
        copy.canonical().displayNameField("category");

        assertEquals("nominee", c.canonical().displayNameField(),
                "mutating the copy must not affect the original");
        assertEquals(1, c.canonical().keyFields().size());
    }

    @Test
    void explicitSpecRoundTripsThroughStore() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("roundtrip");

        GeneratedClassModel c = new GeneratedClassModel("Nomination");
        c.statementSourceClass("OscarNominations");
        c.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee")
                .labelLanguage("en"));
        c.canonical().keyFields().add("nominee");
        c.canonical().keyFields().add("category");
        project.addClass(c);

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        java.io.File tmp = java.io.File.createTempFile("model-canonical", ".json");
        tmp.deleteOnExit();
        store.save(project, tmp);

        GeneratedProjectModel loaded = store.load(tmp);
        GeneratedClassModel lc = loaded.findClass("Nomination");
        assertNotNull(lc);
        assertTrue(lc.hasCanonical(), "canonical must round-trip");
        assertEquals(CanonicalSpec.Kind.DERIVED, lc.canonical().kind());
        assertEquals("nominee", lc.canonical().displayNameField());
        assertEquals(java.util.List.of("nominee", "category"),
                lc.canonical().keyFields());
    }

    @Test
    void legacyModelWithoutCanonicalLoadsAndInfers() throws Exception {
        // A model.json written before canonicalization has no "canonical" key.
        String legacyJson = """
                {
                  "@class" : "wikidata.explore.model.GeneratedProjectModel",
                  "name" : "legacy",
                  "classes" : [ "java.util.ArrayList", [ {
                    "@class" : "wikidata.explore.model.GeneratedClassModel",
                    "className" : "Person"
                  } ] ]
                }
                """;

        java.io.File tmp = java.io.File.createTempFile("model-legacy", ".json");
        tmp.deleteOnExit();
        java.nio.file.Files.writeString(tmp.toPath(), legacyJson);

        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(tmp);
        GeneratedClassModel person = loaded.findClass("Person");
        assertNotNull(person);
        assertNull(person.canonical(), "no explicit spec on a legacy file");

        CanonicalSpec inferred = person.effectiveCanonical();
        assertEquals(CanonicalSpec.Kind.WIKIDATA_ENTITY, inferred.kind());
        assertEquals(CanonicalSpec.DisplayNameMode.LABEL, inferred.displayNameMode());
    }
}
