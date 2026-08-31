package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassImportPlanTest {

    private static GeneratedProjectModel oscarPeople() {
        GeneratedProjectModel source = new GeneratedProjectModel();
        source.name("Oscars");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        source.rootClass(nomination);

        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.classKind(ClassKind.OWNED);
        name.addField("givenName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P735");
        source.addClass(name);

        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        person.instanceMapping().propertyPid("P31");
        GeneratedFieldModel structured = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structured.entityClassName("Name");
        structured.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        source.addClass(person);
        source.addSelection(new RoleSelection(
                "Nominees", "Person", "structuredName"));
        source.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));
        return source;
    }

    private static GeneratedProjectModel history() {
        GeneratedProjectModel target = new GeneratedProjectModel();
        target.name("History");
        target.rootClass(new GeneratedClassModel("Event"));
        return target;
    }

    @Test void copiesAClassWithItsAuthoredDependencyClosure() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel target = history();
        ClassImportPlan plan = ClassImportPlan.of(source, target, "Person");

        assertEquals(Set.of("Name"), plan.dependencyClassNames());
        assertEquals(java.util.List.of("Nominees"),
                plan.selections().stream().map(Selection::name).toList());

        plan.apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertNotNull(target.findClass("Person"));
        assertNotNull(target.findClass("Name"));
        assertNotNull(target.findSelection("Nominees"));
        assertEquals("Name", target.findClass("Person").fields().getFirst()
                .entityClassName());
        assertEquals(1, target.entityKindRules().stream()
                .filter(r -> r.className().equals("Person")).count());
        assertNotSame(source.findClass("Person"), target.findClass("Person"));
    }

    @Test void refusesToCreateADanglingTargetWhenDependencyIsDeselected() {
        ClassImportPlan plan = ClassImportPlan.of(oscarPeople(), history(), "Person");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> plan.apply(Set.of("Person"),
                        ClassImportPlan.ConflictPolicy.REPLACE));

        assertTrue(failure.getMessage().contains("Person → Name"));
    }

    @Test void aTargetDependencyMayBeReusedWithoutCopyingIt() {
        GeneratedProjectModel target = history();
        GeneratedClassModel localName = new GeneratedClassModel("Name");
        localName.classKind(ClassKind.OWNED);
        localName.addField("local", FieldType.STRING, FieldCardinality.SINGLE);
        target.addClass(localName);
        ClassImportPlan plan = ClassImportPlan.of(oscarPeople(), target, "Person");

        plan.apply(Set.of("Person"), ClassImportPlan.ConflictPolicy.REUSE_TARGET);

        assertEquals("local", target.findClass("Name").fields().getFirst().name(),
                "reuse keeps the target declaration rather than the source dependency");
        assertNotNull(target.findClass("Person"));
    }

    @Test void replacementPreservesTheTargetsRootPosition() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel target = history();
        GeneratedClassModel old = new GeneratedClassModel("Person");
        old.alias("Local person");
        target.addClass(old);

        ClassImportPlan.of(source, target, "Person").apply(
                Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertNotSame(old, target.findClass("Person"));
        assertEquals("Event", target.rootClass().className());
    }

    private static GeneratedProjectModel emptyModel() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("People");
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        return model;
    }

    /**
     * The reason models exist: a class configured while building one domain becomes
     * reusable configuration. Copy is the route, and it does not care that the target
     * acquires nothing.
     */
    @Test void aDomainsClassCanBeCopiedIntoAModel() {
        GeneratedProjectModel model = emptyModel();

        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertNotNull(model.findClass("Person"));
        assertNotNull(model.findClass("Name"));
        assertEquals("Name",
                model.findClass("Person").fields().getFirst().entityClassName());
        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind(),
                "copying configuration in does not turn a model into a domain");
    }

    /**
     * One class at a time works, but only in dependency order: Name first, then Person
     * reusing it. Person first refuses, because it would land pointing at nothing.
     */
    @Test void oneClassAtATimeWorksInDependencyOrder() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel model = emptyModel();

        ClassImportPlan.of(source, model, "Name")
                .apply(Set.of("Name"), ClassImportPlan.ConflictPolicy.REPLACE);
        assertNotNull(model.findClass("Name"));

        ClassImportPlan.of(source, model, "Person")
                .apply(Set.of("Person"), ClassImportPlan.ConflictPolicy.REUSE_TARGET);

        assertNotNull(model.findClass("Person"));
        assertEquals("Name",
                model.findClass("Person").fields().getFirst().entityClassName());
    }

    /**
     * The Nobel shape: a prize reaches its laureates, a laureate its person, a person its
     * structured name. One copy takes the whole chain, at any depth — the closure is a
     * worklist, not a single hop — so "one class at a time" is a choice rather than a
     * ceiling.
     */
    @Test void copyReachesTheWholeChainNotJustTheFirstHop() {
        GeneratedProjectModel source = oscarPeople();

        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        laureate.addField("person", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        source.addClass(laureate);

        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.addField("laureates", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Laureate");
        source.addClass(prize);

        ClassImportPlan plan = ClassImportPlan.of(source, emptyModel(), "NobelPrize");

        assertEquals(Set.of("Laureate", "Person", "Name"), plan.dependencyClassNames(),
                "three hops from the requested class, all reached");

        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(source, model, "NobelPrize").apply(
                Set.of("NobelPrize", "Laureate", "Person", "Name"),
                ClassImportPlan.ConflictPolicy.REPLACE);

        for (String name : java.util.List.of(
                "NobelPrize", "Laureate", "Person", "Name")) {
            assertNotNull(model.findClass(name), name + " arrived");
        }
    }

    /**
     * An adopted class records where it came from, and its fields are the owning
     * model's. How an adopting project may reshape them is a later decision; until it
     * is made, the origin is the single authority over the field configuration.
     */
    @Test void anAdoptedClassRemembersItsOriginAndItsFieldsAreLocked() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertEquals("Oscars", model.findClass("Person").originModel());
        assertEquals("Oscars", model.findClass("Name").originModel());
        assertTrue(model.findClass("Person").fieldsLocked());
        assertTrue(model.findClass("Name").fieldsLocked());
    }

    /** A class authored here has no origin; nothing to inherit and nothing to display. */
    @Test void aClassAuthoredHereHasNoOrigin() {
        assertEquals("", oscarPeople().findClass("Person").originModel());
    }

    /**
     * Origin survives being passed along. Adopting Person from People into a domain and
     * then copying it onward still names People — the project in the middle relayed it,
     * it did not author it.
     */
    @Test void originNamesTheAuthorNotTheLastHop() {
        GeneratedProjectModel relay = emptyModel();
        relay.name("Relay");
        ClassImportPlan.of(oscarPeople(), relay, "Person")
                .apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        GeneratedProjectModel destination = emptyModel();
        destination.name("Nobel");
        ClassImportPlan.of(relay, destination, "Person")
                .apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertEquals("Oscars", destination.findClass("Person").originModel());
    }

    /**
     * Two projects adopting the same class each get their own copy, both locked to the
     * origin. Separate copies are what will later let them diverge; nothing shares an
     * object across projects today.
     */
    @Test void eachAdoptingProjectGetsItsOwnLockedCopy() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel first = emptyModel();
        first.name("First");
        GeneratedProjectModel second = emptyModel();
        second.name("Second");

        ClassImportPlan.of(source, first, "Name")
                .apply(Set.of("Name"), ClassImportPlan.ConflictPolicy.REPLACE);
        ClassImportPlan.of(source, second, "Name")
                .apply(Set.of("Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertNotSame(first.findClass("Name"), second.findClass("Name"));
        assertNotSame(source.findClass("Name"), first.findClass("Name"));
        assertTrue(first.findClass("Name").fieldsLocked());
        assertTrue(second.findClass("Name").fieldsLocked());
        assertFalse(source.findClass("Name").fieldsLocked(),
                "the owning model is not locked out of its own class");
    }

    /**
     * The name is how an adopted class claims its origin, so it cannot be changed here.
     * Renaming and keeping the origin would assert a class the origin does not have;
     * renaming and dropping the origin would make rename a way around the field lock.
     */
    @Test void anAdoptedClassCannotBeRenamedByTheProjectThatAdoptedIt() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertTrue(model.findClass("Name").nameLocked());
        assertFalse(model.renameClass("Name", "PersonName"));
        assertNotNull(model.findClass("Name"), "the class keeps its name");
        assertNull(model.findClass("PersonName"));
        assertEquals("Oscars.Name", model.findClass("Name").qualifiedClassName(),
                "the qualified form still names a class the origin actually has");
    }

    /**
     * The class editors call renameClass on every Apply with the name unchanged. That
     * must stay a no-op success for an adopted class, or merely saving one would report
     * a rename failure.
     */
    @Test void savingAnAdoptedClassUnchangedIsNotARenameFailure() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Name")
                .apply(Set.of("Name"), ClassImportPlan.ConflictPolicy.REPLACE);

        assertTrue(model.renameClass("Name", "Name"),
                "an unchanged name is a no-op, not a refused rename");
    }

    /** A class authored here is renamed as it always was. */
    @Test void aClassAuthoredHereIsStillRenamable() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Nobel");
        project.rootClass(new GeneratedClassModel("Prize"));
        project.addClass(new GeneratedClassModel("Ceremony"));

        assertFalse(project.findClass("Ceremony").nameLocked());
        assertTrue(project.renameClass("Ceremony", "Edition"));
        assertNotNull(project.findClass("Edition"));
    }
}
