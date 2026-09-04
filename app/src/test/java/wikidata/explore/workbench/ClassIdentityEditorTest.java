package wikidata.explore.workbench;

import canonical.Reduction;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One editor for identity, and a key that keeps the order it was given.
 *
 * <p>Three editors asked this question before — a checkbox grid on a statement class, a
 * space-separated text field on an entity class, and nothing on an aggregate. The grid
 * carried a defect the others could not: it rebuilt the key in FIELD order on every
 * apply, and identity joins a key's values IN order, so reopening a class silently
 * changed every instance's identifier. It went unnoticed because all three shipped models
 * happen to have been authored in field order.
 */
class ClassIdentityEditorTest {

    private static GeneratedProjectModel holdingProject() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(new StatementClassSource("P39"));
        var subject = holding.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        holding.addField("position", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P39");
        holding.addField("startDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P580");
        holding.addField("aliases", FieldType.STRING, FieldCardinality.COLLECTION);
        project.addClass(holding);
        project.rootClass(holding);
        return project;
    }

    /** The defect this editor replaced: opening and applying reordered the key. */
    @Test void aKeyAuthoredOutOfFieldOrderSurvivesOpeningAndApplying() {
        GeneratedProjectModel project = holdingProject();
        GeneratedClassModel holding = project.findClass("OfficeHolding");
        holding.canonical().keyFields().addAll(
                List.of("startDate", "position", "source"));

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(holding);
        panel.applyEdits();

        assertEquals(List.of("startDate", "position", "source"),
                holding.canonical().keyFields(),
                "identity joins these values in order, so the order is part of it");
    }

    /** The editor exists on the statement panel, and is the shared one. */
    @Test void theStatementPanelUsesTheSharedEditor() {
        GeneratedProjectModel project = holdingProject();
        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(project.findClass("OfficeHolding"));

        assertNotNull(find(panel, ClassIdentityEditor.class),
                "one editor for identity, whatever the construct");
    }

    /**
     * A reducer is offered only where the field can hold the result. A union on a
     * single-valued field would produce a list the field cannot store, which is invalid
     * rather than merely unadvisable — so it is not offered at all.
     */
    @Test void onlyReducersTheFieldCanHoldAreOffered() {
        GeneratedProjectModel project = holdingProject();
        GeneratedClassModel holding = project.findClass("OfficeHolding");
        holding.canonical().keyFields().addAll(List.of("source", "position"));

        var plan = wikidata.explore.compiled.CanonicalizationPlans.of(holding);
        assertEquals(Reduction.REQUIRE_AGREEMENT, plan.reductionFor("startDate"),
                "a scalar cannot union");
        assertEquals(Reduction.UNION_DISTINCT, plan.reductionFor("aliases"),
                "and a collection already holds many values, so it loses nothing");
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
