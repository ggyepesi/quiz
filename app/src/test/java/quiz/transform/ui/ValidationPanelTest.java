package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.curation.ScopeFilter;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.SnapshotFieldGraph;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test void selectedFieldUsesExplicitMissingPresentOrAllScope() {
        WikidataDynamicObject state = object("Q1", "State");
        WikidataDynamicObject usStateWithDate = object("Q2", "USState");
        usStateWithDate.put("admissionDate", "1959-01-03");
        WikidataDynamicObject usStateWithoutDate = object("Q3", "USState");

        SnapshotFieldGraph graph = new SnapshotFieldGraph();
        SnapshotFieldGraph.TypeShape stateType = new SnapshotFieldGraph.TypeShape();
        stateType.name = "State";
        stateType.member = true;
        SnapshotFieldGraph.TypeShape usStateType = new SnapshotFieldGraph.TypeShape();
        usStateType.name = "USState";
        usStateType.baseType = "State";
        usStateType.member = true;
        graph.types.put("State", stateType);
        graph.types.put("USState", usStateType);
        SnapshotDomain domain = new SnapshotDomain(
                List.of(state, usStateWithDate, usStateWithoutDate), graph);

        List<WikidataDynamicObject> all =
                List.of(state, usStateWithDate, usStateWithoutDate);
        assertEquals(List.of(usStateWithDate), ValidationPanel.membersWithFieldScope(
                domain, all, "USState", "admissionDate", ScopeFilter.PRESENT));
        assertEquals(List.of(usStateWithoutDate), ValidationPanel.membersWithFieldScope(
                domain, all, "USState", "admissionDate", ScopeFilter.MISSING));
        assertEquals(List.of(usStateWithDate, usStateWithoutDate),
                ValidationPanel.membersWithFieldScope(
                        domain, all, "USState", "admissionDate", ScopeFilter.ALL));
    }

    @Test void identityCoverageDerivesFromTheStableIdentity() {
        // A QID identity IS a Wikidata identity; a non-QID key has none. There is no
        // stored source field — coverage is read from the identity itself.
        WikidataDynamicObject identified = new WikidataDynamicObject("Q1", "Universe");
        WikidataDynamicObject unresolved =
                new WikidataDynamicObject("unresolved", "unresolved");

        assertEquals("Q1", quiz.source.SourceIdentities.wikidataQid(identified));
        assertNull(quiz.source.SourceIdentities.wikidataQid(unresolved));
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        return object;
    }
}
