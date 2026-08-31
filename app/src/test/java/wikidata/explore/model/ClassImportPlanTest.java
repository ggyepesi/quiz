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

        plan.apply(Set.of("Person", "Name"));

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
                () -> plan.apply(Set.of("Person")));

        assertTrue(failure.getMessage().contains("Person → Name"));
    }

    /**
     * Keeping a dependency this project already has is reached by deselecting it, not
     * by a policy: what is copied is what is selected. That is the honest form of the
     * question — the reader sees the class named and chooses — and it is why the
     * "keep and reuse" option was not worth a control of its own.
     */
    @Test void deselectingADependencyKeepsTheOneAlreadyHere() {
        GeneratedProjectModel target = history();
        GeneratedClassModel localName = new GeneratedClassModel("Name");
        localName.classKind(ClassKind.OWNED);
        localName.addField("local", FieldType.STRING, FieldCardinality.SINGLE);
        target.addClass(localName);
        ClassImportPlan plan = ClassImportPlan.of(oscarPeople(), target, "Person");

        plan.apply(Set.of("Person"));

        assertEquals("local", target.findClass("Name").fields().getFirst().name(),
                "the class already here is untouched by a copy that did not "
                        + "select it");
        assertNotNull(target.findClass("Person"));
    }

    @Test void replacementPreservesTheTargetsRootPosition() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel target = history();
        GeneratedClassModel old = new GeneratedClassModel("Person");
        old.alias("Local person");
        target.addClass(old);

        ClassImportPlan.of(source, target, "Person").apply(
                Set.of("Person", "Name"));

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
                .apply(Set.of("Person", "Name"));

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
                .apply(Set.of("Name"));
        assertNotNull(model.findClass("Name"));

        ClassImportPlan.of(source, model, "Person")
                .apply(Set.of("Person"));

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
                Set.of("NobelPrize", "Laureate", "Person", "Name"));

        for (String name : java.util.List.of(
                "NobelPrize", "Laureate", "Person", "Name")) {
            assertNotNull(model.findClass(name), name + " arrived");
        }
    }

    /**
     * A copy is the copying project's, entirely. It carries no claim from wherever it
     * was copied, because copying eases configuring a class that resembles another —
     * the resemblance is a starting point, not a relationship.
     */
    @Test void aCopiedClassBelongsToTheProjectThatCopiedIt() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"));

        assertEquals("", model.findClass("Person").importedFrom());
        assertFalse(model.findClass("Person").isImported());
        assertFalse(model.findClass("Name").isImported());

        model.findClass("Name").addField(
                "nickname", FieldType.STRING, FieldCardinality.SINGLE);
        assertTrue(model.renameClass("Name", "PersonName"));

        assertEquals(2, model.findClass("PersonName").fields().size(),
                "a copy is edited like any class this project wrote itself");
        assertEquals(1, oscarPeople().findClass("Name").fields().size(),
                "and the project it was copied from is untouched");
    }

    /**
     * An import leaves the class owned by the model it names, which is what makes it a
     * use of that model rather than a duplicate of it.
     */
    @Test void anImportedClassStaysOwnedByTheModelItComesFrom() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"),
                        ClassImportPlan.Ownership.IMPORT);

        assertEquals("Oscars", model.findClass("Person").importedFrom());
        assertEquals("Oscars", model.findClass("Name").importedFrom());
        assertTrue(model.findClass("Person").isImported());
        assertEquals("Oscars.Person", model.findClass("Person").qualifiedClassName());
    }

    /** A class this project wrote is owned by nobody else. */
    @Test void aClassAuthoredHereIsNotImported() {
        assertEquals("", oscarPeople().findClass("Person").importedFrom());
        assertFalse(oscarPeople().findClass("Person").isImported());
    }

    /**
     * Ownership survives being passed along. Importing a class the source had itself
     * imported still names the model that owns it — the project in the middle is a
     * relay, and relaying does not transfer ownership to the relay.
     */
    @Test void importedOwnershipNamesTheOwnerNotTheLastHop() {
        GeneratedProjectModel relay = emptyModel();
        relay.name("Relay");
        ClassImportPlan.of(oscarPeople(), relay, "Person")
                .apply(Set.of("Person", "Name"),
                        ClassImportPlan.Ownership.IMPORT);

        GeneratedProjectModel destination = emptyModel();
        destination.name("Nobel");
        ClassImportPlan.of(relay, destination, "Person")
                .apply(Set.of("Person", "Name"),
                        ClassImportPlan.Ownership.IMPORT);

        assertEquals("Oscars", destination.findClass("Person").importedFrom());
    }

    /**
     * Copying an imported class takes the configuration and drops the claim: the result
     * is an ordinary class of the copying project. Otherwise there would be no way to
     * take a model's class and then diverge from it.
     */
    @Test void copyingAnImportedClassYieldsAnOrdinaryOne() {
        GeneratedProjectModel relay = emptyModel();
        relay.name("Relay");
        ClassImportPlan.of(oscarPeople(), relay, "Name")
                .apply(Set.of("Name"),
                        ClassImportPlan.Ownership.IMPORT);
        assertTrue(relay.findClass("Name").isImported());

        GeneratedProjectModel destination = emptyModel();
        destination.name("Nobel");
        ClassImportPlan.of(relay, destination, "Name")
                .apply(Set.of("Name"));

        assertFalse(destination.findClass("Name").isImported(),
                "a copy claims nothing, whatever it was copied from");
    }

    /** The model that owns a class is not locked out of its own class. */
    @Test void theOwningModelStillEditsWhatOthersImport() {
        GeneratedProjectModel source = oscarPeople();
        GeneratedProjectModel importer = emptyModel();
        ClassImportPlan.of(source, importer, "Name")
                .apply(Set.of("Name"),
                        ClassImportPlan.Ownership.IMPORT);

        assertTrue(importer.findClass("Name").isImported());
        assertFalse(source.findClass("Name").isImported());
        assertTrue(source.renameClass("Name", "FullName"));
    }

    /**
     * The name is how an imported class names what owns it, so the importing project
     * cannot change it: renaming Name to PersonName while still pointing at Oscars
     * would assert a class Oscars does not have.
     */
    @Test void anImportedClassCannotBeRenamedByTheProjectThatImportedIt() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Person")
                .apply(Set.of("Person", "Name"),
                        ClassImportPlan.Ownership.IMPORT);

        assertTrue(model.findClass("Name").isImported());
        assertFalse(model.renameClass("Name", "PersonName"));
        assertNotNull(model.findClass("Name"), "the class keeps its name");
        assertNull(model.findClass("PersonName"));
        assertEquals("Oscars.Name", model.findClass("Name").qualifiedClassName(),
                "the qualified form still names a class the origin actually has");
    }

    /**
     * The class editors call renameClass on every Apply with the name unchanged. That
     * must stay a no-op success for an imported class, or merely saving one would
     * report a rename failure.
     */
    @Test void savingAnImportedClassUnchangedIsNotARenameFailure() {
        GeneratedProjectModel model = emptyModel();
        ClassImportPlan.of(oscarPeople(), model, "Name")
                .apply(Set.of("Name"),
                        ClassImportPlan.Ownership.IMPORT);

        assertTrue(model.renameClass("Name", "Name"),
                "an unchanged name is a no-op, not a refused rename");
    }

    /** A class authored here is renamed as it always was. */
    @Test void aClassAuthoredHereIsStillRenamable() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("Nobel");
        project.rootClass(new GeneratedClassModel("Prize"));
        project.addClass(new GeneratedClassModel("Ceremony"));

        assertFalse(project.findClass("Ceremony").isImported());
        assertTrue(project.renameClass("Ceremony", "Edition"));
        assertNotNull(project.findClass("Edition"));
    }
}
