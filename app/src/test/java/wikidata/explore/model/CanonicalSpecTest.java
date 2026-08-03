package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CanonicalSpecTest {

    @Test void freshClassHasExplicitEntityIdentity() {
        CanonicalSpec spec = new GeneratedClassModel("Person").canonical();
        assertEquals(CanonicalSpec.Kind.WIKIDATA_ENTITY, spec.kind());
        assertEquals(CanonicalSpec.DisplayNameMode.LABEL, spec.displayNameMode());
        assertEquals("en", spec.labelLanguage());
    }

    @Test void statementSourceMakesTheExplicitIdentityDerived() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource(
                "OscarNominations", "P1411"));
        assertEquals(CanonicalSpec.Kind.DERIVED, nomination.canonical().kind());
    }

    @Test void copyDeepCopiesCanonical() {
        GeneratedClassModel model = new GeneratedClassModel("Nomination");
        model.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee"));
        model.canonical().keyFields().add("nominee");

        GeneratedClassModel copy = model.copy();
        copy.canonical().keyFields().add("category");
        copy.canonical().displayNameField("category");

        assertEquals("nominee", model.canonical().displayNameField());
        assertEquals(1, model.canonical().keyFields().size());
    }

    @Test void canonicalSpecRoundTripsThroughStore() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel source = new GeneratedClassModel("OscarNominations");
        project.addClass(source);

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource(
                "OscarNominations", "P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nomination.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");
        nomination.canonical().keyFields().add("nominee");
        project.addClass(nomination);

        java.io.File file = java.io.File.createTempFile("model-canonical", ".json");
        file.deleteOnExit();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);

        GeneratedClassModel loaded = store.load(file).findClass("Nomination");
        assertNotNull(loaded);
        assertEquals(CanonicalSpec.Kind.DERIVED, loaded.canonical().kind());
        assertEquals(java.util.List.of("nominee"), loaded.canonical().keyFields());
    }
}
