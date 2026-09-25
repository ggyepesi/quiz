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
