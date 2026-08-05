package quiz.transform.pipeline.ui;

import objectview.field.FieldKind;
import objectview.viewconfig.FieldRow;
import flag.State;
import flag.USState;
import org.junit.jupiter.api.Test;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.ui.TransformController;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.SnapshotFieldGraph;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStepsPanelFieldShapeTest {

    @Test
    void snapshotMediaCollectionKeepsItsCollectionShapeForSizeFilters() {
        WikidataDynamicObject state =
                new WikidataDynamicObject("United States", "United States");
        state.type("State");
        state.put("armsVersions", List.of(
                new WikidataMediaValue("obverse", "file:/obverse.svg", true),
                new WikidataMediaValue("reverse", "file:/reverse.svg", true)));

        SnapshotDomain domain = new SnapshotDomain(
                List.of(state), SnapshotFieldGraph.derive(List.of(state)));
        TransformController controller = new TransformController(domain, null);
        controller.selectType("State");

        // Dynamic snapshot rows intentionally have no reflected Field. Their
        // authoritative DomainField must therefore supply collection cardinality.
        FieldRow row = FieldRow.dynamic(
                "armsVersions", "Collection<WikidataMediaValue>", null);
        var field = ViewStepsPanel.domainFieldForRow(controller, "State", row);

        assertTrue(field.collection());
        assertEquals(FieldKind.COLLECTION, field.kind());
        assertTrue(FilterOperator.SIZE_EQUALS.appliesTo(field.kind()));
        assertTrue(FilterOperator.SIZE_GREATER_THAN.appliesTo(field.kind()));
    }

    @Test
    void subtypeBranchResolvesToItsOwningClassAndPlainFieldPath() {
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(new State("France"), new USState("Alabama"))),
                null);
        controller.selectType("State");

        FieldRow row = FieldRow.path(
                "admissionDate", "@subtype:USState.admissionDate",
                1, false, "FlexibleDate");
        var field = ViewStepsPanel.domainFieldForRow(controller, "State", row);

        assertEquals("USState", field.type());
        assertEquals("admissionDate", field.field());
    }
}
