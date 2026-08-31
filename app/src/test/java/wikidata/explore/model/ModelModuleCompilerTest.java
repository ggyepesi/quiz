package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.ProjectModelCompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A module pin is reproducible schema composition, not a class-copy shortcut. */
class ModelModuleCompilerTest {

    @TempDir Path temp;

    @Test void importedAndLocallyAuthoredClassesCompileToTheSameShape() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModuleImport people = modules.save(people("1"));
        GeneratedProjectModel imported = domain("Nobel");
        imported.addImport(people);

        GeneratedProjectModel local = domain("Nobel");
        addPeopleDeclarations(local, false);

        CompiledClass importedPerson = ProjectModelCompiler.compile(imported, modules)
                .findClass("Person").orElseThrow();
        CompiledClass localPerson = ProjectModelCompiler.compile(local)
                .findClass("Person").orElseThrow();

        assertEquals(localPerson.classKind(), importedPerson.classKind());
        assertEquals(localPerson.ownFields().stream().map(field -> List.of(
                        field.name(), field.type(), field.cardinality(),
                        field.entityClassName())).toList(),
                importedPerson.ownFields().stream().map(field -> List.of(
                        field.name(), field.type(), field.cardinality(),
                        field.entityClassName())).toList());
        assertTrue(importedPerson.field("structuredName").isPresent());
    }

    @Test void aDomainPersistsOnlyTheExactModulePin() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModuleImport pin = modules.save(people("1"));
        GeneratedProjectModel domain = domain("Nobel");
        domain.addImport(pin);
        String importedClassId = pin.declarationIds().getFirst();
        domain.replaceModulePresentationOverlay(new ModelClassPresentationOverlay(
                importedClassId, CanonicalSpec.DisplayNameMode.FIELD,
                "structuredName", ""));
        var file = temp.resolve("nobel.model.json").toFile();

        new GeneratedProjectModelStore(modules).save(domain, file);
        GeneratedProjectModel loaded = new GeneratedProjectModelStore(modules).load(file);

        assertEquals(1, loaded.imports().size());
        assertEquals(pin.coordinate(), loaded.imports().getFirst().coordinate());
        assertEquals(pin.contentDigest(), loaded.imports().getFirst().contentDigest());
        assertEquals(pin.declarationIds(), loaded.imports().getFirst().declarationIds());
        assertEquals("structuredName", loaded.modulePresentationOverlay(importedClassId)
                .displayNameField());
        assertFalse(loaded.classes().stream().anyMatch(c -> "Person".equals(c.className())),
                "saving an import must not embed a private class copy");
    }

    @Test void anImmutableVersionCannotBeOverwritten() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        modules.save(people("1"));
        ModelModule changed = people("1");
        changed.classes().stream().filter(c -> "Person".equals(c.className()))
                .findFirst().orElseThrow()
                .addField("portrait", FieldType.IMAGE, FieldCardinality.SINGLE);

        IOException refused = assertThrows(IOException.class, () -> modules.save(changed));

        assertTrue(refused.getMessage().contains("Immutable"), refused.getMessage());
    }

    @Test void missingAndDigestChangedModulesRefuseCompilation() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModuleImport pin = modules.save(people("1"));
        GeneratedProjectModel missing = domain("Missing");
        missing.addImport(new ModelModuleImport("absent", "1", "sha256:none",
                List.of("module:absent:class:Thing")));
        GeneratedProjectModel changed = domain("Changed");
        changed.addImport(new ModelModuleImport(pin.moduleId(), pin.version(),
                "sha256:wrong", pin.declarationIds()));

        var absent = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(missing, modules));
        var drift = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(changed, modules));

        assertTrue(absent.getMessage().contains("not found"), absent.getMessage());
        assertTrue(drift.getMessage().contains("digest mismatch"), drift.getMessage());
    }

    @Test void cyclesAndIncompatibleVersionsRefuseCompilation() throws Exception {
        ModelModule a = new ModelModule("a", "1");
        GeneratedClassModel aClass = new GeneratedClassModel("A");
        aClass.declarationId(DeclarationIds.module("a", "class", "A"));
        a.addClass(aClass);
        ModelModule b = new ModelModule("b", "1");
        GeneratedClassModel bClass = new GeneratedClassModel("B");
        bClass.declarationId(DeclarationIds.module("b", "class", "B"));
        b.addClass(bClass);
        b.addImport(new ModelModuleImport("a", "1", "sha256:cycle",
                List.of(aClass.declarationId())));
        seal(b);
        a.addImport(pin(b));
        seal(a);
        Map<String, ModelModule> cycleModules = Map.of("a@1", a, "b@1", b);
        GeneratedProjectModel cyclic = domain("Cyclic");
        cyclic.addImport(pin(a));

        var cycle = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(cyclic,
                        (id, version) -> cycleModules.get(id + "@" + version)));
        assertTrue(cycle.getMessage().contains("Cyclic"), cycle.getMessage());

        ModelModule one = simple("people", "1", "PersonOne");
        ModelModule two = simple("people", "2", "PersonTwo");
        seal(one);
        seal(two);
        Map<String, ModelModule> versions = new LinkedHashMap<>();
        versions.put("people@1", one);
        versions.put("people@2", two);
        GeneratedProjectModel incompatible = domain("Incompatible");
        incompatible.addImport(pin(one));
        incompatible.addImport(pin(two));

        var conflict = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(incompatible,
                        (id, version) -> versions.get(id + "@" + version)));
        assertTrue(conflict.getMessage().toLowerCase().contains("incompatible"),
                conflict.getMessage());
    }

    @Test void anImportCannotShadowALocalDeclaration() throws Exception {
        ModelModule people = people("1");
        seal(people);
        GeneratedProjectModel domain = domain("Collision");
        domain.addClass(new GeneratedClassModel("Person"));
        domain.addImport(pin(people));

        var collision = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(domain, (id, version) -> people));

        assertTrue(collision.getMessage().contains("collides"), collision.getMessage());
    }

    /**
     * A kind rule is identified by the class it stamps and the property it reads —
     * exactly the pair {@code replaceEntityKindRule} overwrites on. Classes and
     * selections were refused on collision while rules were appended unchecked, so a
     * module carrying the rule a domain already had left two entries for one rule.
     * Nobel's own {@code Person <- Q5} is that case: a people module would carry it.
     */
    @Test void anImportedKindRuleCannotDuplicateALocalOne() throws Exception {
        ModelModule people = people("1");
        people.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));
        seal(people);

        GeneratedProjectModel domain = domain("Nobel");
        domain.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));
        domain.addImport(pin(people));

        var collision = assertThrows(ProjectModelCompiler.ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(domain, (id, version) -> people));

        assertTrue(collision.getMessage().contains("kind rule")
                        && collision.getMessage().contains("Person"),
                collision.getMessage());
    }

    @Test void aKindRuleForAnotherClassOrPropertyIsNotACollision() throws Exception {
        ModelModule people = people("1");
        people.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));
        seal(people);

        GeneratedProjectModel domain = domain("Nobel");
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        domain.addClass(laureate);
        domain.addEntityKindRule(new EntityKindRule("Laureate", java.util.List.of("Q43229")));
        domain.addImport(pin(people));

        var compiled = ProjectModelCompiler.compile(domain, (id, version) -> people);

        assertNotNull(compiled, "a rule about a different class stamps something else");
    }

    @Test void replacingAKindRuleUsesTheSameIdentityAwareTargetAsImportResolution() {
        GeneratedProjectModel model = domain("Replacement");
        EntityKindRule existing = new EntityKindRule("Old person name", List.of("Q5"));
        existing.classId("class:person");
        model.addEntityKindRule(existing);
        EntityKindRule replacement = new EntityKindRule("Renamed Person", List.of("Q215627"));
        replacement.classId("class:person");

        model.replaceEntityKindRule(replacement);

        assertEquals(List.of(replacement), model.entityKindRules());
    }

    @Test void moduleChangesArePreviewedAndAppliedOnlyOnCommand() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModuleImport one = modules.save(people("1"));
        ModelModule versionTwo = people("2");
        versionTwo.classes().stream().filter(c -> c.className().equals("Person"))
                .findFirst().orElseThrow()
                .addField("portrait", FieldType.IMAGE, FieldCardinality.SINGLE);
        ModelModuleImport two = modules.save(versionTwo);
        GeneratedProjectModel domain = domain("Nobel");

        ModelModuleChangePlan add = ModelModuleChangePlan.add(domain, modules, one);
        assertTrue(domain.imports().isEmpty(), "preview must not mutate the domain");
        add.apply();
        assertEquals("people@1", domain.imports().getFirst().coordinate());

        ModelModuleChangePlan update = ModelModuleChangePlan.update(
                domain, modules, one, two);
        assertTrue(update.impact().stream().anyMatch(line -> line.contains("Changes class Person")));
        assertEquals("people@1", domain.imports().getFirst().coordinate());
        update.apply();
        assertEquals("people@2", domain.imports().getFirst().coordinate());

        ModelModuleChangePlan remove = ModelModuleChangePlan.remove(domain, modules, two);
        assertFalse(domain.imports().isEmpty());
        remove.apply();
        assertTrue(domain.imports().isEmpty());
    }

    @Test void theModuleCatalogueListsEveryImmutableVersion() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        modules.save(people("1"));
        modules.save(people("2"));

        assertEquals(List.of("people@1", "people@2"), modules.available().stream()
                .map(ModelModuleImport::coordinate).toList());
    }

    @Test void aLocalPresentationOverlayChangesOnlyTheComposedImportedClass()
            throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModuleImport peoplePin = modules.save(people("1"));
        GeneratedProjectModel domain = domain("Nobel");
        domain.addImport(peoplePin);
        String personId = peoplePin.declarationIds().stream()
                .filter(id -> id.endsWith(":Person")).findFirst().orElseThrow();
        domain.replaceModulePresentationOverlay(new ModelClassPresentationOverlay(
                personId, CanonicalSpec.DisplayNameMode.FIELD,
                "structuredName", ""));

        CompiledClass compiled = ProjectModelCompiler.compile(domain, modules)
                .findClass("Person").orElseThrow();
        ModelModule stored = modules.resolve("people", "1");

        assertEquals(CanonicalSpec.DisplayNameMode.FIELD,
                compiled.canonical().displayNameMode());
        assertEquals("structuredName", compiled.canonical().displayNameField());
        assertEquals(CanonicalSpec.DisplayNameMode.LABEL,
                stored.classes().stream().filter(c -> c.className().equals("Person"))
                        .findFirst().orElseThrow().canonical().displayNameMode(),
                "a domain overlay must not mutate the shared module");
    }

    @Test void aModuleCanReferenceADeclarationFromAPinnedDependency() throws Exception {
        ModelModuleStore modules = new ModelModuleStore(temp.resolve("modules"));
        ModelModule names = simple("names", "1", "Name");
        names.classes().getFirst().classKind(ClassKind.OWNED);
        ModelModuleImport namesPin = modules.save(names);

        ModelModule people = simple("people", "1", "Person");
        people.addImport(namesPin);
        GeneratedFieldModel structuredName = people.classes().getFirst()
                .addField("structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.entityDeclarationId(names.classes().getFirst().declarationId());
        ModelModuleImport peoplePin = modules.save(people);
        GeneratedProjectModel domain = domain("Nobel");
        domain.addImport(peoplePin);

        var compiled = ProjectModelCompiler.compile(domain, modules);

        assertTrue(compiled.findClass("Person").orElseThrow()
                .field("structuredName").isPresent());
        assertTrue(compiled.findClass("Name").isPresent());
    }

    private static GeneratedProjectModel domain(String name) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name(name);
        model.rootClass(new GeneratedClassModel("Prize"));
        return model;
    }

    private static ModelModule people(String version) {
        ModelModule module = new ModelModule("people", version);
        GeneratedProjectModel declarations = domain("people");
        addPeopleDeclarations(declarations, true);
        declarations.classes().stream().filter(c -> !"Prize".equals(c.className()))
                .forEach(module::addClass);
        return module;
    }

    private static void addPeopleDeclarations(
            GeneratedProjectModel model, boolean moduleIds) {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.classKind(ClassKind.OWNED);
        if (moduleIds) {
            person.declarationId(DeclarationIds.module("people", "class", "Person"));
            name.declarationId(DeclarationIds.module("people", "class", "Name"));
        }
        person.addField("structuredName", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Name");
        name.addField("givenName", FieldType.STRING, FieldCardinality.COLLECTION)
                .mapping().propertyPid("P735");
        name.addField("familyName", FieldType.STRING, FieldCardinality.COLLECTION)
                .mapping().propertyPid("P734");
        model.addClass(person);
        model.addClass(name);
    }

    private static ModelModule simple(String id, String version, String className) {
        ModelModule module = new ModelModule(id, version);
        GeneratedClassModel clazz = new GeneratedClassModel(className);
        clazz.declarationId(DeclarationIds.module(id, "class", className));
        module.addClass(clazz);
        return module;
    }

    private static void seal(ModelModule module) throws Exception {
        module.normalizeDeclarations();
        module.contentDigest(ModelModuleStore.digest(module));
    }

    private static ModelModuleImport pin(ModelModule module) {
        return new ModelModuleImport(module.moduleId(), module.version(),
                module.contentDigest(), module.declarationIds());
    }
}
