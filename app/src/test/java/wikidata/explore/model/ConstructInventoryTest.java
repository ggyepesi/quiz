package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Load, Show and Save read one construct list, not four lifecycle implementations. */
class ConstructInventoryTest {

    @Test void everyConstructEntersTheSameStableIdentityInventory() {
        GeneratedProjectModel project = project();

        ConstructInventory inventory = ConstructInventory.of(project);

        assertEquals(List.of(
                ConstructInventory.Kind.CLASS,
                ConstructInventory.Kind.GRAPH,
                ConstructInventory.Kind.POPULATION,
                ConstructInventory.Kind.SELECTION),
                inventory.entries().stream().map(ConstructInventory.Entry::kind).toList());
        assertTrue(inventory.entries().stream()
                .allMatch(entry -> !entry.declarationId().isBlank()));
    }

    @Test void retractingRemovesOnlyClaimsWhoseConstructNoLongerExists() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject retained = new WikidataDynamicObject("Q1", "Retained");
        retained.type("Position");
        retained.assignClass("RemovedClass");
        WikidataDynamicObject removed = new WikidataDynamicObject("Q2", "Removed");
        removed.type("RemovedClass");

        ConstructInventory.of(project).retractRemovedClaims(List.of(retained, removed));

        assertEquals(java.util.Set.of("Position"), retained.directClassNames());
        assertFalse(removed.directClassNames().contains("RemovedClass"));
    }

    /**
     * Asking which objects are the member roots is a question. It used to answer by
     * retracting claims from the pool it was handed, so a reader of the inventory
     * rewrote the objects it was reading.
     */
    @Test void askingWhichObjectsAreRootsChangesNoneOfThem() {
        GeneratedProjectModel project = project();
        WikidataDynamicObject retained = new WikidataDynamicObject("Q1", "Retained");
        retained.type("Position");
        retained.assignClass("RemovedClass");
        WikidataDynamicObject removed = new WikidataDynamicObject("Q2", "Removed");
        removed.type("RemovedClass");

        List<WikidataDynamicObject> roots = ConstructInventory.of(project)
                .memberRoots(List.of(retained, removed));

        assertEquals(List.of(retained), roots,
                "only an object claiming a current construct is a root");
        assertTrue(retained.directClassNames().contains("RemovedClass"),
                "and reading that leaves the object exactly as it was");
    }

    /**
     * A renamed class is the same declaration under a new name, not a removed one.
     * Read as removed, Save retracted every claim on it and the class's members left the
     * snapshot (#309).
     */
    @Test void aRenameIsPairedByDeclarationIdAndRetractsNothing() {
        GeneratedProjectModel project = project();
        ConstructInventory before = ConstructInventory.of(project);
        WikidataDynamicObject member = new WikidataDynamicObject("Q1", "Member");
        member.type("Position");

        project.renameClass("Position", "Office");
        project.renameClass("PositionGraph", "OfficeGraph");
        ConstructInventory after = ConstructInventory.of(project);
        java.util.Map<String, String> renames = after.renamesSince(before);
        wikidata.explore.generation.GenerationRuns.renameClasses(List.of(member), renames);

        assertEquals(java.util.Map.of("Position", "Office", "PositionGraph", "OfficeGraph"),
                renames);
        assertEquals(List.of(member),
                after.memberRoots(after.retractRemovedClaims(List.of(member))));
        assertEquals("Office", member.typeName());
        assertTrue(after.renamesSince(after).isEmpty());
    }

    /** Applied at once, so two classes trading names do not chain into one. */
    @Test void renamesApplySimultaneously() {
        WikidataDynamicObject a = new WikidataDynamicObject("Q1", "A");
        a.type("Left");
        WikidataDynamicObject b = new WikidataDynamicObject("Q2", "B");
        b.type("Right");

        wikidata.explore.generation.GenerationRuns.renameClasses(List.of(a, b),
                java.util.Map.of("Left", "Right", "Right", "Left"));

        assertEquals(java.util.Set.of("Right"), a.directClassNames());
        assertEquals(java.util.Set.of("Left"), b.directClassNames());
    }

    /** A part is keyed by its site, the class it is at its owner's field; both ends follow
     *  a rename, and so do the fetched-declaration records naming the class. */
    @Test void aPartsSiteAndTheFetchedDeclarationsFollowTheRename() {
        WikidataDynamicObject owner = new WikidataDynamicObject("Q1", "Owner");
        owner.type("Person");
        WikidataDynamicObject part = new WikidataDynamicObject("Q1", "Owner");
        part.type("BirthName");
        part.typeKey("BirthName@Person.birthName");
        part.part(true);
        owner.put("birthName", part);
        java.util.Map<String, String> renames = java.util.Map.of("Person", "Human");

        wikidata.explore.generation.GenerationRuns.renameClasses(List.of(owner), renames);

        assertEquals("BirthName@Human.birthName", part.typeKey());
        assertEquals("BirthName", part.typeName());
        assertEquals("Human.birthDate:P569",
                wikidata.explore.generation.GenerationRuns.renamedDeclarations(List.of(
                        new wikidata.explore.extract.LoadedDeclaration(
                                "Person", "birthDate", "P569", List.of("Q1"))), renames)
                        .getFirst().key());
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        project.rootClass(position);
        GeneratedClassModel graph = new GeneratedClassModel("PositionGraph");
        graph.classKind(ClassKind.GRAPH);
        project.addClass(graph);
        PopulationSelection population = new PopulationSelection("PositionsForHistory");
        population.className("Position");
        population.instanceQids(List.of("Q1"));
        project.addSelection(population);
        VocabularySelection vocabulary = new VocabularySelection("PositionKinds");
        vocabulary.valueQids(List.of("Q4164871"));
        project.addSelection(vocabulary);
        return project;
    }
}
