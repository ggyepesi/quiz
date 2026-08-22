package quiz.transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import quiz.transform.ui.TransformController;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import domain.DomainModel;
import domain.DomainField;

class TypeSpecGroupTest {

    @Test void admitsOwnersByActualReferencedClassAndSupportsAlternatives() {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject film = entity("Q2", "Film");
        WikidataDynamicObject n1 = entity("N1", "Nomination"); n1.put("nominee", person);
        WikidataDynamicObject n2 = entity("N2", "Nomination"); n2.put("nominee", film);
        SnapshotDomain domain = new SnapshotDomain(List.of(n1, n2, person, film));

        TypeSpecGroup group = new TypeSpecGroup("People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))), domain);
        group.reproduce(List.of(n1, n2));
        assertEquals(List.of(n1), group.getMembers());

        TypeSpecGroup either = new TypeSpecGroup("Known",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person", "Film"))), domain);
        either.reproduce(List.of(n1, n2));
        assertEquals(2, either.getMembers().size());
    }

    @Test void persistedRuleReconstructsAndChildrenInheritParentMembership() {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject film = entity("Q2", "Film");
        WikidataDynamicObject n1 = entity("N1", "Nomination"); n1.put("nominee", person); n1.put("forWork", film);
        WikidataDynamicObject n2 = entity("N2", "Nomination"); n2.put("nominee", film); n2.put("forWork", film);
        SnapshotDomain domain = new SnapshotDomain(List.of(n1, n2, person, film));

        EditableGroup root = new EditableGroup("All nominations");
        root.replaceMembers(List.of(n1, n2));
        TypeSpecGroup parent = new TypeSpecGroup("Person nominees",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))), domain);
        TypeSpecGroup child = new TypeSpecGroup("In films",
                new TypeSpec("Nomination", Map.of("forWork", Set.of("Film"))), domain);
        parent.addGroup(child); root.addGroup(parent); root.reproduceDescendants();

        EditableGroup restored = EditableGroup.copyOf(root, domain);
        restored.reproduceDescendants();
        TypeSpecGroup restoredParent = (TypeSpecGroup) restored.getChildren().iterator().next();
        TypeSpecGroup restoredChild = (TypeSpecGroup) restoredParent.getChildren().iterator().next();
        assertEquals(List.of(n1), restoredParent.getMembers());
        assertEquals(List.of(n1), restoredChild.getMembers());
    }

    @Test void selectedGroupRefinesFieldSchemaWithoutCreatingASubclass() {
        WikidataDynamicObject person = entity("Q1", "Person");
        person.put("birthDate", "1909-09-07");
        WikidataDynamicObject n1 = entity("N1", "Nomination"); n1.put("nominee", person);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");
        TypeSpec spec = new TypeSpec("Nomination", Map.of("nominee", Set.of("Person")));

        TypeSpecGroup group = controller.addTypeSpecGroup(root, "Person nominees", spec);
        controller.selectGroup(group);

        assertEquals("Person", controller.fieldTypes("Nomination")
                .field("nominee").typeLabel());
        org.junit.jupiter.api.Assertions.assertTrue(controller.fields("Nomination")
                .stream().anyMatch(field -> "nominee.birthDate".equals(field.field())));
        org.junit.jupiter.api.Assertions.assertFalse(controller.types().contains("PersonNomination"));
        assertEquals(List.of(n1), group.getMembers());
    }

    @Test void explicitTypeSpecSurvivesSnapshotWithTheOrdinaryClassBinding(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject n1 = entity("N1", "Nomination"); n1.put("nominee", person);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");
        controller.addTypeSpecGroup(root, "Person nominees", new TypeSpec("Nomination",
                        Map.of("nominee", Set.of("Person"))));

        var converted = ViewableToWdo.convertDomain(controller.domain().memberRoots(),
                controller.domain().groupRootBindings(), controller.domain());
        var bindings = converted.groupRootBindings().stream()
                .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                        binding.memberType(), binding.root())).toList();
        java.io.File file = dir.resolve("typed.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(converted.memberRoots(), bindings, file,
                controller.domain());
        var loaded = store.loadAllWithFieldGraph(file);

        assertEquals(1, loaded.groupRootBindings().stream()
                .filter(binding -> "Nomination".equals(binding.memberType())).count());
        objectview.group.ViewableGroup<?> adapted = quiz.transform.app.DynamicViewableGroup.adapt(
                loaded.groupRootBindings().getFirst().root());
        objectview.group.ViewableGroup<?> typed = adapted.getChildren().iterator().next();
        assertEquals("Nomination", typed.fields().read("typeSpecInstanceClass"));
        assertEquals("nominee=Person", typed.fields().read("typeSpecFields"));
    }

    @Test void ordinaryAndTypedChildrenInheritAndRefineTheParentSchema() {
        WikidataDynamicObject person = entity("Q1", "Person");
        person.put("birthDate", "1909");
        WikidataDynamicObject film = entity("Q2", "Film");
        film.put("releaseDate", "1950");
        WikidataDynamicObject nomination = entity("N1", "Nomination");
        nomination.put("nominee", person); nomination.put("forWork", film);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(nomination, person, film)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");
        TypeSpecGroup parent = controller.addTypeSpecGroup(root, "People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))));
        EditableGroup manual = controller.addManualGroup(parent, "Curated");
        manual.replaceMembers(parent.getMembers());
        controller.selectGroup(manual);
        org.junit.jupiter.api.Assertions.assertTrue(controller.fields("Nomination").stream()
                .anyMatch(field -> "nominee.birthDate".equals(field.field())));

        TypeSpecGroup child = controller.addTypeSpecGroup(parent, "Films",
                new TypeSpec("Nomination", Map.of("forWork", Set.of("Film"))));
        controller.selectGroup(child);
        Set<String> paths = controller.fields("Nomination").stream()
                .map(DomainField::field).collect(java.util.stream.Collectors.toSet());
        org.junit.jupiter.api.Assertions.assertTrue(paths.contains("nominee.birthDate"));
        org.junit.jupiter.api.Assertions.assertTrue(paths.contains("forWork.releaseDate"));
    }

    @Test void nestedPathValidationTraversesAllUnionBranches() {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject film = entity("Q2", "Film"); film.put("creator", person);
        WikidataDynamicObject musical = entity("Q3", "MusicalWork");
        WikidataDynamicObject nomination = entity("N1", "Nomination");
        nomination.put("forWork", film);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(nomination, film, musical, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");
        TypeSpec spec = new TypeSpec("Nomination", Map.of(
                "forWork", Set.of("MusicalWork", "Film"),
                "forWork.creator", Set.of("Person")));
        TypeSpecGroup group = controller.addTypeSpecGroup(root, "Created works", spec);
        assertEquals(List.of(nomination), group.getMembers());
    }

    @Test void referencedPersonRendersWithPersonSchemaNotNominationSchema() {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject nomination = entity("N1", "Nomination");
        nomination.put("nominee", person);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(nomination, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");
        TypeSpecGroup group = controller.addTypeSpecGroup(root, "People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))));
        org.junit.jupiter.api.Assertions.assertTrue(controller.selectGroup(group));
        org.junit.jupiter.api.Assertions.assertFalse(controller.selectGroup(group),
                "re-showing the same group must not rebuild and clear the field picker");

        assertEquals(controller.domain().fieldSchema("Person"),
                controller.renderedFieldSchema(person, "Nomination"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                controller.fieldSchema("Nomination"),
                controller.renderedFieldSchema(person, "Nomination"));
        assertEquals("Q1", person.getReferenceLabel(),
                "a fieldless Person still has its identity/display label");
    }

    @Test void savingAnEditedLoadedRootKeepsNewTypeSpecGroup(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject nomination = entity("N1", "Nomination");
        nomination.put("nominee", person);
        EditableGroup original = new EditableGroup("All Nomination");
        original.replaceMembers(List.of(nomination));
        DomainModel base =
                new SnapshotDomain(List.of(nomination, person));
        // Provide the loaded binding through a thin domain view.
        DomainModel loaded = new DomainModel() {
            @Override public List<String> types() { return base.types(); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return base.fieldSchema(type);
            }
            @Override public java.util.Collection<? extends objectview.Viewable> instances() {
                return base.instances();
            }
            @Override public java.util.List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
                return List.of(new objectview.viewconfig.DomainGroupRoot("Nomination", original));
            }
            @Override public Class<? extends objectview.Viewable> universe() { return base.universe(); }
        };
        TransformController controller = new TransformController(loaded, null);
        EditableGroup editable = (EditableGroup) controller.groupRoot("Nomination");
        controller.addTypeSpecGroup(editable, "People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))));

        var converted = ViewableToWdo.convertDomain(controller.domain().memberRoots(),
                controller.domain().groupRootBindings(), controller.domain());
        java.io.File file = dir.resolve("edited.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithGroupRootBindings(
                converted.memberRoots(), converted.groupRootBindings().stream()
                        .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                binding.memberType(), binding.root())).toList(),
                file, controller.domain());
        var reread = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);
        objectview.group.ViewableGroup<?> root = quiz.transform.app.DynamicViewableGroup.adapt(
                reread.groupRootBindings().getFirst().root());
        objectview.group.ViewableGroup<?> child = root.getChildren().stream()
                .filter(candidate -> "People".equals(candidate.fields().read("groupName")))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(child);
        assertEquals("typeSpec", child.fields().read("producer"));
        assertEquals("nominee=Person", child.fields().read("typeSpecFields"));
    }

    /** A path that cannot be typed from the schema is rejected loudly at creation,
     *  rather than silently admitting nothing (or diverging from the projected schema). */
    @Test void rejectsFieldPathsThatCannotBeTypedFromTheSchema() {
        WikidataDynamicObject person = entity("Q1", "Person");
        WikidataDynamicObject n1 = entity("N1", "Nomination");
        n1.put("nominee", person);
        n1.put("year", "1970");
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.addTypeSpecGroup(root, "bad",
                        new TypeSpec("Nomination", Map.of("nope", Set.of("Person")))));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.addTypeSpecGroup(root, "bad",
                        new TypeSpec("Nomination", Map.of("year", Set.of("Person")))));
    }

    /** A nested path whose intermediate is explicitly typed both validates and admits. */
    @Test void admitsThroughAWellTypedNestedPath() {
        WikidataDynamicObject spouse = entity("Q2", "Person");
        WikidataDynamicObject person = entity("Q1", "Person"); person.put("spouse", spouse);
        WikidataDynamicObject alone = entity("Q3", "Person");
        WikidataDynamicObject n1 = entity("N1", "Nomination"); n1.put("nominee", person);
        WikidataDynamicObject n2 = entity("N2", "Nomination"); n2.put("nominee", alone);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, n2, person, spouse, alone)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");

        TypeSpecGroup group = controller.addTypeSpecGroup(root, "married nominees",
                new TypeSpec("Nomination", Map.of(
                        "nominee", Set.of("Person"), "nominee.spouse", Set.of("Person"))));
        assertEquals(List.of(n1), group.getMembers());
    }

    /** A nested rule types its intermediate from the DECLARED target when the spec does
     *  not restrict it, so the group keeps every field the plain class had and refines
     *  only what the rule names. Projecting less than the base schema would make
     *  selecting the group a loss rather than a refinement. */
    @Test void projectsThroughAnIntermediateTypedByItsDeclaredTarget() {
        WikidataDynamicObject person = entity("Q1", "Person");
        person.put("birthDate", "1909");
        WikidataDynamicObject film = entity("Q2", "Film");
        film.put("releaseDate", "1950");
        film.put("creator", person);
        WikidataDynamicObject n1 = entity("N1", "Nomination");
        n1.put("forWork", film);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, film, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");

        // Only the nested path is constrained; `forWork` itself is left to its declared
        // target (Film) — the case the schema projection used to resolve to nothing.
        TypeSpecGroup group = controller.addTypeSpecGroup(root, "Created works",
                new TypeSpec("Nomination", Map.of("forWork.creator", Set.of("Person"))));
        assertEquals(List.of(n1), group.getMembers());
        controller.selectGroup(group);

        Set<String> paths = controller.fields("Nomination").stream()
                .map(DomainField::field)
                .collect(java.util.stream.Collectors.toSet());
        org.junit.jupiter.api.Assertions.assertTrue(paths.contains("forWork.releaseDate"),
                "the intermediate's own fields survive the projection: " + paths);
        org.junit.jupiter.api.Assertions.assertTrue(
                paths.contains("forWork.creator.birthDate"),
                "the constrained nested path resolves to Person: " + paths);
    }

    /** A collection field is admitted only when EVERY value is of the restricted class:
     *  the group declares that field IS that class, so one off-kind value would make the
     *  projected schema a lie. */
    @Test void everyValueOfACollectionFieldMustMatchTheRestriction() {
        WikidataDynamicObject writer = entity("Q1", "Person");
        WikidataDynamicObject composer = entity("Q2", "Person");
        WikidataDynamicObject film = entity("Q3", "Film");
        WikidataDynamicObject people = entity("N1", "Nomination");
        people.put("nominee", List.of(writer, composer));
        WikidataDynamicObject mixed = entity("N2", "Nomination");
        mixed.put("nominee", List.of(writer, film));
        WikidataDynamicObject none = entity("N3", "Nomination");
        none.put("nominee", List.of());
        SnapshotDomain domain = new SnapshotDomain(
                List.of(people, mixed, none, writer, composer, film));

        TypeSpecGroup group = new TypeSpecGroup("People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))), domain);
        group.reproduce(List.of(people, mixed, none));
        assertEquals(List.of(people), group.getMembers());
    }

    /** A multi-valued nested path is judged on ALL the values it reaches, not on whichever
     *  element happens to come first. */
    @Test void aNestedPathIsJudgedOnEveryValueItReaches() {
        WikidataDynamicObject spouse = entity("Q10", "Person");
        WikidataDynamicObject married = entity("Q1", "Person");
        married.put("spouse", spouse);
        WikidataDynamicObject single = entity("Q2", "Person");
        WikidataDynamicObject partly = entity("N1", "Nomination");
        partly.put("nominee", List.of(married, single));
        WikidataDynamicObject fully = entity("N2", "Nomination");
        fully.put("nominee", List.of(married));
        SnapshotDomain domain = new SnapshotDomain(
                List.of(partly, fully, married, single, spouse));

        TypeSpecGroup group = new TypeSpecGroup("Married nominees",
                new TypeSpec("Nomination", Map.of(
                        "nominee", Set.of("Person"), "nominee.spouse", Set.of("Person"))),
                domain);
        group.reproduce(List.of(partly, fully));
        assertEquals(List.of(fully), group.getMembers());
    }

    /** The classes offered as restrictions and the classes the rule accepts are ONE set.
     *  A stamped class with no fields of its own never reaches the field graph, so it is
     *  absent from {@code types()} — and it is exactly the case type-spec groups exist
     *  for: restricting a nominee to Person before Person has any fields. */
    @Test void aStampedFieldlessClassIsBothOfferedAndAccepted() {
        WikidataDynamicObject person = entity("Q1", "Person");   // no fields of its own
        WikidataDynamicObject n1 = entity("N1", "Nomination");
        n1.put("nominee", person);
        TransformController controller = new TransformController(
                new SnapshotDomain(List.of(n1, person)), null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");

        org.junit.jupiter.api.Assertions.assertFalse(controller.types().contains("Person"),
                "a fieldless class has no shape in the field graph");
        org.junit.jupiter.api.Assertions.assertTrue(
                controller.admissionClasses().contains("Person"),
                "but it is a valid class to admit BY: " + controller.admissionClasses());
        org.junit.jupiter.api.Assertions.assertFalse(
                controller.admissionClasses().contains(TransformController.ALL_ENTITIES),
                "the entity-universe pseudo-type is not a class");

        TypeSpecGroup group = controller.addTypeSpecGroup(root, "People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))));
        assertEquals(List.of(n1), group.getMembers());
    }

    /** A rule that admits nothing YET is a rule, not an error: the kinds may not be stamped
     *  and the referents may not be loaded. It is kept and re-applied when the scope is
     *  re-derived; the workbench confirms the empty count rather than refusing it. */
    @Test void aRuleThatAdmitsNothingYetIsStillCreatedAndFillsInLater() {
        WikidataDynamicObject unstamped = entity("Q1", "Nominee");
        WikidataDynamicObject n1 = entity("N1", "Nomination");
        n1.put("nominee", unstamped);
        WikidataDynamicObject person = entity("Q2", "Person");
        SnapshotDomain domain = new SnapshotDomain(List.of(n1, unstamped, person));
        TransformController controller = new TransformController(domain, null);
        EditableGroup root = (EditableGroup) controller.groupRoot("Nomination");

        TypeSpecGroup group = controller.addTypeSpecGroup(root, "People",
                new TypeSpec("Nomination", Map.of("nominee", Set.of("Person"))));
        org.junit.jupiter.api.Assertions.assertNotNull(group);
        assertEquals(List.of(), group.getMembers());

        // The kind arrives later; re-deriving the scope fills the group in.
        n1.put("nominee", person);
        root.reproduceDescendants();
        assertEquals(List.of(n1), group.getMembers());
    }

    private static WikidataDynamicObject entity(String id, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, id);
        value.type(type);
        return value;
    }
}
