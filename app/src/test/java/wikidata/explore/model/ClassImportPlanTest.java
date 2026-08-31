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
}
