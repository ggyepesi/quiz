package wikidata.explore.generation;

import datasource.Datasources;
import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.acquisition.ClassPopulationOperation;
import datasource.api.acquisition.PopulationRequest;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.PopulationSelection;
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

    @Test void importedPopulationIsTheExactRootSetNotAFilterOverTheOldRule() {
        RuleNode node = new RuleNode("Position", "position");
        node.sourceQid("Q4164871");
        node.propertyPid("P31");
        node.membershipIncludesDescendants(true);
        node.membershipPid("P31");
        node.membershipQid("Q5");
        node.requireSitelink(true);
        node.rankBySitelinks(true);
        node.rankPropertyPid("P1082");
        node.sortFieldName("label");
        node.labelConfig().requireLabel(true);
        wikidata.explore.rule.RuleIncludedField required =
                new wikidata.explore.rule.RuleIncludedField(
                        "holderCount", "P39", "position held",
                        wikidata.explore.rule.RuleIncludedField.FieldKind.AUTO, false);
        node.addIncludedField(required);
        node.addMembershipConstraint(required);
        node.addIncludedQid("Q_OLD");
        node.addExcludedQid("Q2");
        node.addValueFilter(new wikidata.explore.filter.WikidataValueFilter());

        PopulationSourceExecution.applyExact(node, List.of("Q1", "Q2", "Q3"));

        assertTrue(node.sourceQid().isBlank());
        assertTrue(node.propertyPid().isBlank());
        assertTrue(node.additionalSourceQids().isEmpty());
        assertTrue(node.membershipPid().isBlank());
        assertTrue(node.membershipQid().isBlank());
        assertTrue(node.membershipConstraints().isEmpty());
        assertTrue(node.includedFields().getFirst().optional());
        assertTrue(!node.labelConfig().requireLabel());
        assertTrue(node.excludedQids().isEmpty());
        assertTrue(node.valueFilters().isEmpty());
        assertEquals(List.of("Q1", "Q2", "Q3"), List.copyOf(node.includedQids()));
        assertEquals(Integer.MAX_VALUE, node.limit(),
                "label rows must not consume a limit before every saved QID is visited");
    }

    @Test void importedClassResolutionChoosesTheReferencedPopulationNotItsLocalStep() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("History");
        GeneratedClassModel consumer = new GeneratedClassModel("OfficeHolding");
        consumer.membership(wikidata.explore.model.EntityBound.vocabulary(
                "PositionsForHistory"));
        project.rootClass(consumer);
        GeneratedClassModel imported = new GeneratedClassModel("Position");
        imported.importedFrom("Historical Positions");
        project.addClass(imported);
        PopulationSelection population = new PopulationSelection("PositionsForHistory");
        population.className("Position");
        population.instanceQids(List.of("Q1", "Q2"));
        population.importedFrom("Historical Positions");
        project.addSelection(population);

        PopulationSourceExecution.Resolution resolved =
                PopulationSourceExecution.resolve(project, imported,
                        step(new SourceRecipe("wikidata", "statement-membership",
                                Map.of("property", "P31", "values", "Q4164871"))));
        RuleNode node = new RuleNode("Position", "position");
        resolved.apply(node, step(new SourceRecipe(
                "wikidata", "statement-membership",
                Map.of("property", "P31", "values", "Q4164871"))));

        assertTrue(resolved.importedPopulation());
        assertEquals(List.of("Q1", "Q2"), resolved.qids());
        assertTrue(node.propertyPid().isBlank(),
                "the imported producer's P31 rule must never reach preview or generation");
        assertEquals(List.of("Q1", "Q2"), List.copyOf(node.includedQids()));
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
    @Test void aForeignPopulationIsRefusedButWikidataSubclassClosureIsRetained() {
        RuleNode node = new RuleNode("Movie", "movie");

        assertThrows(IllegalArgumentException.class, () -> PopulationSourceExecution.apply(
                node, saying(PopulationRequest.relation("dbpedia", "P31",
                        List.of(new EntityRef("dbpedia", "Film")), false))),
                "a population of identifiers this boundary cannot resolve");

        PopulationSourceExecution.apply(
                node, saying(PopulationRequest.relation(EntityRef.WIKIDATA, "P31",
                        List.of(EntityRef.wikidata("Q11424")), true)));
        assertTrue(node.membershipIncludesDescendants());
    }

    /** A plan step whose operation reports the given selection. Built directly because
     *  no installed provider can produce one. */
    private static SourceExecutionPlan.Step saying(PopulationRequest selection) {
        ClassPopulationOperation operation = new ClassPopulationOperation() {
            @Override public PopulationRequest selection(SourceRecipe recipe) {
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
