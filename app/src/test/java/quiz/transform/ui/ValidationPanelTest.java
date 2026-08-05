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

    @Test void coverageOfSubtypeFieldExcludesOrdinaryBaseInstances() {
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

        FieldCoverageColumns columns = new FieldCoverageColumns(
                domain, () -> "State",
                () -> List.of(state, usStateWithDate, usStateWithoutDate));
        FieldCoverageColumns.Coverage coverage =
                columns.coverage("@subtype:USState.admissionDate");

        assertEquals(2, coverage.eligible());
        assertEquals(1, coverage.present());
        assertEquals(1, coverage.missing());
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

    @Test void coverageFansOutMapAndArrayIntermediates() {
        WikidataDynamicObject first = object("first", "Nested");
        first.put("value", "present");
        WikidataDynamicObject root = object("root", "Root");
        root.put("mapped", java.util.Map.of("first", first));
        root.put("array", new WikidataDynamicObject[] {first});

        org.junit.jupiter.api.Assertions.assertTrue(
                FieldCoverageColumns.hasValue(root, "mapped.value"));
        org.junit.jupiter.api.Assertions.assertTrue(
                FieldCoverageColumns.hasValue(root, "array.value"));
    }

    @Test void coverageCanBeInvalidatedAfterInPlaceCuration() {
        WikidataDynamicObject state = object("Q1", "State");
        List<WikidataDynamicObject> members = List.of(state);
        SnapshotDomain domain = new SnapshotDomain(members);
        FieldCoverageColumns columns = new FieldCoverageColumns(
                domain, () -> "State", () -> members);

        assertEquals(0, columns.coverage("capital").present());
        state.put("capital", "Example");
        assertEquals(0, columns.coverage("capital").present());
        columns.invalidate();
        assertEquals(1, columns.coverage("capital").present());
    }

    private static WikidataDynamicObject object(String id, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        return object;
    }
}
