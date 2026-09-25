package wikidata.explore.workbench;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PopulationSelectionSourceTest {
    @Test void populationSourcesComeFromShownInstanceSectionsNotConfigSelection() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass().className("Person");
        project.addClass(new GeneratedClassModel("Position"));
        GeneratedClassModel graph = new GeneratedClassModel("PositionDiscovery");
        graph.classKind(ClassKind.GRAPH);
        project.addClass(graph);

        Viewable position = object("Q1", "Position");
        Viewable annotation = object("Q1", "PositionDiscovery");
        LinkedHashMap<String, List<Viewable>> shown = new LinkedHashMap<>();
        shown.put("Position", List.of(position));
        shown.put("PositionDiscovery", List.of(annotation));

        var choices = ModelBuilderFrame.populationSourcesShownIn(shown, project);

        assertEquals(List.of("Position"), List.copyOf(choices.keySet()),
                "the graph annotation is not a population source and the unrelated "
                        + "configured Person class is not consulted");
        assertEquals(List.of(position), choices.get("Position"));
    }

    private static WikidataDynamicObject object(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, type);
        value.type(type);
        return value;
    }
}
