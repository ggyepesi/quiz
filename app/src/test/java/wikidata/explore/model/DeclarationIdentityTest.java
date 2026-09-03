package wikidata.explore.model;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;
import datasource.api.SourceBindingSlot;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.compiled.ProjectModelCompiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeclarationIdentityTest {

    @TempDir Path temp;

    @Test void legacyNameReferencesAcquireOneStableIdentity() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.declarationId("");
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        laureate.baseClassName("Person");
        GeneratedFieldModel spouse = laureate.addField(
                "spouse", FieldType.ENTITY, FieldCardinality.SINGLE);
        spouse.entityClassName("Person");
        model.rootClass(person);
        model.addClass(laureate);

        model.ensureDeclarationIdentities();

        assertFalse(person.declarationId().isBlank());
        assertEquals(person.declarationId(), laureate.baseClassId());
        assertEquals(person.declarationId(), spouse.entityDeclarationId());
    }

    @Test void legacySelectionsReceiveDeterministicIdentities() {
        VocabularySelection first = new VocabularySelection();
        first.name("Categories");
        GeneratedProjectModel firstModel = new GeneratedProjectModel();
        firstModel.name("Awards");
        firstModel.addSelection(first);

        VocabularySelection second = new VocabularySelection();
        second.name("Categories");
        GeneratedProjectModel secondModel = new GeneratedProjectModel();
        secondModel.name("Awards");
        secondModel.addSelection(second);

        firstModel.ensureDeclarationIdentities();
        secondModel.ensureDeclarationIdentities();

        assertFalse(first.declarationId().isBlank());
        assertEquals(first.declarationId(), second.declarationId());
    }

    @Test void renameChangesHintsButNotReferenceIdentity() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel holder = new GeneratedClassModel("Holder");
        holder.baseClassName("Person");
        GeneratedFieldModel personField = holder.addField(
                "person", FieldType.ENTITY, FieldCardinality.SINGLE);
        personField.entityClassName("Person");
        personField.sourceBindings().add(new SourceBinding(
                SourceBindingTarget.fieldValue("Holder", "person",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("test", "value", Map.of())));
        StatementClassSource statement = new StatementClassSource("Person", "P166");
        holder.statementSource(statement);
        EntityKindRule kind = new EntityKindRule("Person", List.of("Q5"));
        RoleSelection role = new RoleSelection("People", "Person", "spouse");
        model.rootClass(person);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        holder.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(holder));
        model.addClass(holder);
        model.addEntityKindRule(kind);
        model.addSelection(role);
        model.ensureDeclarationIdentities();
        String id = person.declarationId();

        assertTrue(model.renameClass("Person", "Human"));

        assertEquals(id, person.declarationId());
        assertEquals(id, holder.baseClassId());
        assertEquals("Human", holder.baseClassName());
        assertEquals(id, personField.entityDeclarationId());
        assertEquals("Human", personField.entityClassName());
        assertEquals(id, holder.statementSource().sourceClassId());
        assertEquals(id, kind.classId());
        assertEquals(id, role.ownerClassId());
    }

    @Test void compilerResolvesAStaleNameHintByIdentity() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel holder = new GeneratedClassModel("Holder");
        GeneratedFieldModel field = holder.addField(
                "person", FieldType.ENTITY, FieldCardinality.SINGLE);
        field.entityClassName("Person");
        model.rootClass(person);
        model.addClass(holder);
        model.ensureDeclarationIdentities();

        // Simulates a module version changing the readable declaration name while a
        // consuming model still carries its old hint.
        person.className("Human");
        var compiled = ProjectModelCompiler.compile(model);

        assertEquals("Human", compiled.findClass("Holder").orElseThrow()
                .field("person").orElseThrow().entityClassName());
        assertEquals(person.declarationId(), compiled.findClass("Holder").orElseThrow()
                .field("person").orElseThrow().entityDeclarationId());
    }

    @Test void duplicateDeclarationIdsAreRejected() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel first = new GeneratedClassModel("First");
        GeneratedClassModel second = new GeneratedClassModel("Second");
        second.declarationId(first.declarationId());
        model.rootClass(first);
        model.addClass(second);

        var validation = GeneratedProjectModelValidator.validate(model);

        assertFalse(validation.valid());
        assertTrue(validation.format().contains("Declaration identity is also used"),
                validation.format());
    }

    @Test void identitiesAndReferencesRoundTripThroughTheModelStore() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Awards");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        laureate.baseClassName("Person");
        laureate.addField("person", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        model.rootClass(person);
        model.addClass(laureate);
        model.ensureDeclarationIdentities();

        var file = temp.resolve("awards.model.json").toFile();
        new GeneratedProjectModelStore().save(model, file);
        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file);

        GeneratedClassModel loadedPerson = loaded.findClass("Person");
        GeneratedClassModel loadedLaureate = loaded.findClass("Laureate");
        assertEquals(person.declarationId(), loadedPerson.declarationId());
        assertEquals(loadedPerson.declarationId(), loadedLaureate.baseClassId());
        assertEquals(loadedPerson.declarationId(),
                loadedLaureate.fields().stream()
                        .filter(field -> "person".equals(field.name()))
                        .findFirst().orElseThrow().entityDeclarationId());
    }
}
