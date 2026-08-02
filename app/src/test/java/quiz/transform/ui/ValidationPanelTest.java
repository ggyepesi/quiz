package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.SnapshotFieldGraph;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidationPanelTest {

    @Test void baseTypeCountIncludesSubtypeInstances() {
        WikidataDynamicObject state = object("Q1", "State");
        WikidataDynamicObject usState = object("Q2", "USState");

        SnapshotFieldGraph graph = new SnapshotFieldGraph();
        SnapshotFieldGraph.TypeShape stateType =
                new SnapshotFieldGraph.TypeShape();
        stateType.name = "State";
        stateType.member = true;
        SnapshotFieldGraph.TypeShape usStateType =
                new SnapshotFieldGraph.TypeShape();
        usStateType.name = "USState";
        usStateType.baseType = "State";
        usStateType.member = true;
        graph.types.put("State", stateType);
        graph.types.put("USState", usStateType);

        SnapshotDomain domain = new SnapshotDomain(List.of(state, usState), graph);

        assertEquals(2,
                ValidationPanel.membersOf(domain, domain.instances(), "State").size());
        assertEquals(1,
                ValidationPanel.membersOf(domain, domain.instances(), "USState").size());
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        return object;
    }
}
