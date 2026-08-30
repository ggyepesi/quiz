package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CanonicalSpecTest {

    @Test void existingModelsKeepOneDuplicateUnlessMergeIsDeclared() {
        CanonicalSpec spec = new CanonicalSpec();
        assertEquals(CanonicalSpec.DuplicatePolicy.KEEP_ONE, spec.duplicatePolicy());

        spec.duplicatePolicy(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS);
        assertEquals(CanonicalSpec.DuplicatePolicy.MERGE_RECORDS,
                spec.copy().duplicatePolicy());
    }

    @Test void aFreshClassTakesItsIdentityAndNameFromItsSource() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        CanonicalSpec spec = person.canonical();

        assertTrue(person.classKind().identityFromSource(),
                "how a class is built decides the regime; nothing else states it");
        assertEquals(CanonicalSpec.DisplayNameMode.LABEL, spec.displayNameMode());
        assertEquals("en", spec.labelLanguage());
    }

    @Test void aStatementSourceMakesTheClassDeriveItsIdentity() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource(
                "OscarNominations", "P1411"));

        assertEquals(ClassKind.STATEMENT, nomination.classKind());
        assertTrue(!nomination.classKind().identityFromSource(),
                "a reified record is not identified by a source's id");
    }

    @Test void copyDeepCopiesCanonical() {
        GeneratedClassModel model = new GeneratedClassModel("Nomination");
        model.canonical(new CanonicalSpec()
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
        assertEquals(ClassKind.STATEMENT, loaded.classKind(),
                "the regime survives the round trip because the class kind does");
        assertEquals(java.util.List.of("nominee"), loaded.canonical().keyFields());
    }

    @Test void retiredCanonicalKindIsMigratedBeforeJacksonBinding() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass().canonical().displayNameMode(
                CanonicalSpec.DisplayNameMode.TEMPLATE).displayNameTemplate("{title}");
        java.io.File file = java.io.File.createTempFile("old-canonical-kind", ".json");
        file.deleteOnExit();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);

        ObjectMapper plain = new ObjectMapper();
        JsonNode tree = plain.readTree(file);
        addRetiredKind(tree);
        plain.writerWithDefaultPrettyPrinter().writeValue(file, tree);

        GeneratedProjectModel loaded = store.load(file);

        assertEquals(ClassKind.SOURCE, loaded.rootClass().classKind());
        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                loaded.rootClass().canonical().displayNameMode());
    }

    private static void addRetiredKind(JsonNode node) {
        if (node instanceof ObjectNode object
                && object.get("canonical") instanceof ObjectNode canonical) {
            canonical.put("kind", "WIKIDATA_ENTITY");
        }
        node.forEach(CanonicalSpecTest::addRetiredKind);
    }
}
