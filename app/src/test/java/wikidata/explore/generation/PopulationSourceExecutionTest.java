package wikidata.explore.generation;

import datasource.Datasources;
import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.acquisition.ClassPopulationOperation;
import datasource.api.acquisition.PopulationSelection;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationSourceExecutionTest {

    @Test void relationBindingOwnsMembershipWhileModelConstraintsSurvive() {
        RuleNode node = new RuleNode("Movie", "movie");
        node.sourceQid("Q_WRONG");
        node.propertyPid("P999");
        node.addIncludedQid("Q42");
        SourceExecutionPlan.Step step = step(new SourceRecipe(
                "wikidata", "statement-membership",
                Map.of("property", "P31", "values", "Q11424,Q202866")));

        PopulationSourceExecution.apply(node, step);

        assertEquals("P31", node.propertyPid());
        assertEquals(RuleDirection.ITEM_TO_ROOT, node.direction(),
                "membership reads item → root; the default, stated so a change is caught");
        assertEquals("Q11424", node.sourceQid());
        assertEquals(List.of("Q202866"), List.copyOf(node.additionalSourceQids()));
        assertEquals(List.of("Q42"), List.copyOf(node.includedQids()),
                "an independent configured restriction is not part of membership");
    }

    @Test void explicitPopulationReplacesMembershipAndItsOldSeedProjection() {
        RuleNode node = new RuleNode("Movie", "movie");
        node.sourceQid("Q11424");
        node.addAdditionalSourceQid("Q202866");
        node.addIncludedQid("Q_OLD");
        SourceExecutionPlan.Step step = step(new SourceRecipe(
                "wikidata", "seed-list", Map.of("ids", "Q42,Q1")));

        PopulationSourceExecution.apply(node, step);

        assertTrue(node.sourceQid().isBlank());
        assertTrue(node.additionalSourceQids().isEmpty());
        assertEquals(List.of("Q42", "Q1"), List.copyOf(node.includedQids()));
    }

    @Test void aStepThatIsNotAClassPopulationIsRefused() {
        SourceExecutionPlan.Step fieldStep = SourceExecutionPlan.compile(
                List.of(new SourceBinding(
                        SourceBindingTarget.fieldValue("Movie", "country",
                                SourceBindingSlot.PRIMARY_FIELD_VALUE),
                        new SourceRecipe("wikidata", "property-value",
                                Map.of("property", "P495")))),
                Datasources.standard()).steps().getFirst();

        assertThrows(IllegalArgumentException.class,
                () -> PopulationSourceExecution.apply(new RuleNode("Movie", "movie"), fieldStep));
        assertThrows(IllegalArgumentException.class,
                () -> PopulationSourceExecution.apply(new RuleNode("Movie", "movie"), null));
        assertThrows(IllegalArgumentException.class,
                () -> PopulationSourceExecution.apply(null, step(new SourceRecipe(
                        "wikidata", "seed-list", Map.of("ids", "Q42")))));
    }

    /**
     * The two refusals no installed provider can currently reach. Both describe a
     * population this extraction boundary cannot express, and neither is the kind of
     * thing to discover by generating half a domain — so they are exercised with an
     * operation built to say those things.
     */
    @Test void aPopulationThisBoundaryCannotExpressIsRefusedRatherThanApproximated() {
        RuleNode node = new RuleNode("Movie", "movie");

        assertThrows(IllegalArgumentException.class, () -> PopulationSourceExecution.apply(
                node, saying(PopulationSelection.relation("dbpedia", "P31",
                        List.of(new EntityRef("dbpedia", "Film")), false))),
                "a population of identifiers this boundary cannot resolve");

        assertThrows(IllegalArgumentException.class, () -> PopulationSourceExecution.apply(
                node, saying(PopulationSelection.relation(EntityRef.WIKIDATA, "P31",
                        List.of(EntityRef.wikidata("Q11424")), true))),
                "subclass closure, which the rule cannot carry");
    }

    /** A plan step whose operation reports the given selection. Built directly because
     *  no installed provider can produce one. */
    private static SourceExecutionPlan.Step saying(PopulationSelection selection) {
        ClassPopulationOperation operation = new ClassPopulationOperation() {
            @Override public PopulationSelection selection(SourceRecipe recipe) {
                return selection;
            }
            @Override public String id() { return "test-population"; }
            @Override public String displayName() { return "Test population"; }
            @Override public BindingScope scope() { return BindingScope.CLASS_POPULATION; }
            @Override public List<ParameterDescriptor> parameters() { return List.of(); }
            @Override public SourceValueSchema outputSchema() {
                return new SourceValueSchema(SourceValueKind.ENTITY_REFERENCE, true, "");
            }
        };
        return new SourceExecutionPlan.Step(
                new SourceBinding(SourceBindingTarget.classPopulation("Movie"),
                        new SourceRecipe("wikidata", "seed-list", Map.of("ids", "Q42"))),
                operation, SourceExecutionPlan.Mode.DECLARATION);
    }

    private static SourceExecutionPlan.Step step(SourceRecipe recipe) {
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.classPopulation("Movie"), recipe);
        return SourceExecutionPlan.compile(List.of(binding), Datasources.standard())
                .steps().getFirst();
    }
}
