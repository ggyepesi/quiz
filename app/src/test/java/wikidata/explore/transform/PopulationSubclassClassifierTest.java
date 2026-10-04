package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.model.SubclassCondition;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PopulationSubclassClassifierTest {

    @Test void outsidePopulationPositionsBecomeTheConfiguredSubclass() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        model.rootClass(position);
        GeneratedClassModel boundary = new GeneratedClassModel("ReachablePosition");
        boundary.baseClassName("Position");
        PopulationSelection admitted = population(
                "PositionWithHoldersPopulation", "Position", List.of("Q1"));
        model.addSelection(admitted);
        boundary.subclassCondition(SubclassCondition.outsidePopulation(
                admitted.name(), admitted.declarationId()));
        model.addClass(boundary);

        WikidataDynamicObject inside = entity("Q1", "Position");
        WikidataDynamicObject outside = entity("Q2", "Position");
        ArrayList<WikidataDynamicObject> pool = new ArrayList<>(List.of(inside, outside));

        PopulationSubclassClassifier.Result first =
                PopulationSubclassClassifier.apply(model, pool);
        assertEquals(1, first.changed());
        assertEquals(java.util.Set.of("Position"), inside.directClassNames());
        assertEquals(java.util.Set.of("ReachablePosition"), outside.directClassNames());
        assertEquals("ReachablePosition", outside.typeName());
        assertEquals(0, PopulationSubclassClassifier.apply(model, pool).changed(),
                "classification is fixed-point work, not a repeated mutation");
    }

    @Test void changingThePopulationRetractsAFormerBoundaryClassification() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        PopulationSelection admitted = population("Positions", "Position", List.of());
        model.addSelection(admitted);
        GeneratedClassModel boundary = new GeneratedClassModel("ReachablePosition");
        boundary.baseClassName("Position");
        boundary.subclassCondition(SubclassCondition.outsidePopulation(
                admitted.name(), admitted.declarationId()));
        model.addClass(boundary);
        WikidataDynamicObject value = entity("Q2", "Position");
        ArrayList<WikidataDynamicObject> pool = new ArrayList<>(List.of(value));
        PopulationSubclassClassifier.apply(model, pool);

        admitted.instanceQids().add("Q2");
        assertEquals(1, PopulationSubclassClassifier.apply(model, pool).changed());
        assertEquals(java.util.Set.of("Position"), value.directClassNames());
        assertEquals("Position", value.typeName());
    }

    private static WikidataDynamicObject entity(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }

    private static PopulationSelection population(
            String name, String className, List<String> qids) {
        PopulationSelection selection = new PopulationSelection(name);
        selection.className(className);
        selection.instanceQids(qids);
        return selection;
    }
}
