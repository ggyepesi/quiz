package domain.build;

import domain.datasource.Datasources;
import domain.datasource.EntityRef;
import domain.datasource.api.BindingScope;
import domain.datasource.api.ParameterDescriptor;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceValueKind;
import domain.datasource.api.SourceValueSchema;
import domain.datasource.api.acquisition.ClassPopulationOperation;
import domain.datasource.api.acquisition.AcquiredPopulation;
import domain.datasource.api.acquisition.AcquiringClassPopulationOperation;
import domain.datasource.api.acquisition.PopulationAcquisition;
import domain.datasource.api.acquisition.PopulationRequest;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;
import domain.config.RuleDirection;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.PopulationSelection;
import domain.config.SubclassCondition;
import domain.compile.rule.RuleNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationSourceExecutionTest {

    @Test void aSelfAcquiredPopulationBecomesTheExactOrdinaryRootSet() throws Exception {
        AcquiringClassPopulationOperation operation = new AcquiringClassPopulationOperation() {
            @Override public PopulationAcquisition acquisition(SourceRecipe recipe) {
                return new PopulationAcquisition(EntityRef.WIKIDATA, "Example category",
                        new work.Query<AcquiredPopulation>() {
                            @Override public String purpose() { return "Acquire examples"; }
                            @Override public String skeleton() { return "bounded examples"; }
                            @Override public Map<String, String> parameters() {
                                return Map.of();
                            }
                            @Override public AcquiredPopulation execute(
                                    work.QueryContext context) {
                                return new AcquiredPopulation(List.of(
                                        EntityRef.wikidata("Q1"),
                                        EntityRef.wikidata("Q2")),
                                        List.of("Unresolved page"));
                            }
                            @Override public int rowCount(AcquiredPopulation result) {
                                return result.members().size();
                            }
                        });
            }
            @Override public String id() { return "acquired"; }
            @Override public String displayName() { return "Acquired"; }
            @Override public BindingScope scope() { return BindingScope.CLASS_POPULATION; }
            @Override public List<ParameterDescriptor> parameters() { return List.of(); }
            @Override public SourceValueSchema outputSchema() {
                return SourceValueSchema.collection(
                        SourceValueKind.ENTITY_REFERENCE, EntityRef.WIKIDATA);
            }
        };
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.classPopulation("Example"),
                new SourceRecipe("test", "acquired", Map.of()));
        SourceExecutionPlan.Step step = new SourceExecutionPlan.Step(
                binding, operation, SourceExecutionPlan.Mode.ACQUISITION);
        PopulationSourceExecution.Resolution pending =
                new PopulationSourceExecution.Resolution(
                        PopulationSourceExecution.Resolution.Kind.LOCAL_ACQUISITION,
                        List.of(), List.of(), step, "");
        java.util.ArrayList<String> messages = new java.util.ArrayList<>();

        PopulationSourceExecution.Resolution acquired = pending.acquire(
                new work.QueryContext(), new domain.runtime.GenerationLog() {
                    @Override public void message(String text) { messages.add(text); }
                    @Override public void subquery(
                            String title, String request, String summary) { }
                });
        RuleNode root = new RuleNode("Example", "example");
        acquired.apply(root);

        assertEquals(List.of("Q1", "Q2"), List.copyOf(root.includedQids()));
        assertTrue(messages.stream().anyMatch(message ->
                message.contains("Unresolved page")));
    }

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

    @Test void qualifiedStatementBindingNeedsNoMainValueQid() {
        RuleNode node = new RuleNode("Star", "star");
        PopulationSourceExecution.apply(node, step(new SourceRecipe(
                "wikidata", "statement-membership",
                Map.of("property", "P528", "conditionProperty", "P972",
                        "conditionValues", "Q499138"))));

        assertEquals("P528", node.propertyPid());
        assertTrue(node.allSourceQids().isEmpty());
        assertEquals(new wikidata.QualifierCondition("P972", List.of("Q499138")),
                node.membershipQualifierCondition());
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

    /**
     * A limit is a cap on an open population. Over an enumerated one it can only
     * subtract from what the modeller listed, and it cannot be repaired by setting it
     * to their number: the query projects ?valueLabel through the en,mul label service,
     * so one entity with both labels occupies two rows. The same arithmetic displaced
     * the last two unlabelled positions from History's 410-QID population.
     */
    @Test void anExplicitPopulationIsNotCutByTheClassConfiguredLimit() {
        RuleNode node = new RuleNode("Movie", "movie");
        node.limit(200);
        node.addExcludedQid("Q99");
        String ids = java.util.stream.IntStream.rangeClosed(1, 300)
                .mapToObj(n -> "Q" + n)
                .collect(java.util.stream.Collectors.joining(","));

        PopulationSourceExecution.apply(node, step(new SourceRecipe(
                "wikidata", "seed-list", Map.of("ids", ids))));

        assertEquals(300, node.includedQids().size());
        assertTrue(node.limit() > 300,
                "a row limit must not cut a population the modeller enumerated");
        assertEquals(List.of("Q99"), List.copyOf(node.excludedQids()),
                "while a restriction authored over those seeds is deliberate and stays");

        RuleNode open = new RuleNode("Movie", "movie");
        open.limit(200);
        PopulationSourceExecution.apply(open, step(new SourceRecipe(
                "wikidata", "statement-membership",
                Map.of("property", "P31", "values", "Q11424"))));

        assertEquals(200, open.limit(),
                "and an open population keeps the cap that is the only thing bounding it");
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
        domain.compile.rule.RuleIncludedField required =
                new domain.compile.rule.RuleIncludedField(
                        "holderCount", "P39", "position held",
                        domain.compile.rule.RuleIncludedField.FieldKind.AUTO, false);
        node.addIncludedField(required);
        node.addMembershipConstraint(required);
        node.addIncludedQid("Q_OLD");
        node.addExcludedQid("Q2");
        node.addValueFilter(new domain.compile.filter.WikidataValueFilter());

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
        consumer.membership(domain.config.EntityBound.vocabulary(
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
                        plan("Position", new SourceRecipe("wikidata", "statement-membership",
                                Map.of("property", "P31", "values", "Q4164871"))));
        RuleNode node = new RuleNode("Position", "position");
        resolved.apply(node);

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

    /**
     * A plan with no step for this class and no plan at all are different answers.
     * Read as one, a domain run asked Wikidata for Person, Name and PositionHolder —
     * three classes that have no population at all — and the run log claimed four root
     * class queries where one was real.
     */
    @Test void aClassThePlanHasNoPopulationForIsNotAskedFor() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.rootClass(person);

        PopulationSourceExecution.Resolution missing = PopulationSourceExecution.resolve(
                project, person, plan("Movie", new SourceRecipe(
                        "wikidata", "statement-membership",
                        Map.of("property", "P31", "values", "Q11424"))));

        assertTrue(!missing.available());
        assertEquals("no population source is configured", missing.reason());
        assertTrue(PopulationSourceExecution.resolve(project, person, null).available(),
                "while an offline caller without a plan runs the rule it already compiled");
    }

    /**
     * History's ReachablePosition extends Position and therefore has an effective
     * inherited Position membership. That membership supplies candidates to the base;
     * it is not a second query for the subclass. The first real run exposed the split
     * decision: the pipeline description skipped it, but execution asked Wikidata for
     * all 1,325 Position entities anyway.
     */
    @Test void aPopulationDerivedSubclassIsClassifiedLocallyNotAskedFor() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(domain.config.EntityBound.relation(
                "P31", List.of("Q4164871"), false));
        project.rootClass(position);
        PopulationSelection population = new PopulationSelection("PositionsForHistory");
        population.className("Position");
        population.instanceQids(List.of("Q1"));
        project.addSelection(population);
        GeneratedClassModel reachable = new GeneratedClassModel("ReachablePosition");
        reachable.baseClassName("Position");
        reachable.subclassCondition(SubclassCondition.outsidePopulation(
                population.name(), population.declarationId()));
        project.addClass(reachable);

        PopulationSourceExecution.Resolution withInheritedStep =
                PopulationSourceExecution.resolve(project, reachable,
                        plan("ReachablePosition", new SourceRecipe(
                                "wikidata", "statement-membership",
                                Map.of("property", "P31", "values", "Q4164871"))));
        PopulationSourceExecution.Resolution offline =
                PopulationSourceExecution.resolve(project, reachable, null);

        assertTrue(!withInheritedStep.available());
        assertTrue(!offline.available(),
                "no execution path may reinterpret local classification as extraction");
        assertTrue(withInheritedStep.reason().contains("classified locally"));
    }

    @Test void anEvidenceDerivedSubclassIsClassifiedLocallyNotAskedFor() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel location = new GeneratedClassModel("Location");
        location.membership(domain.config.EntityBound.relation(
                "P31", List.of("Q2221906"), false));
        project.rootClass(location);
        GeneratedClassModel realm = new GeneratedClassModel("Realm");
        realm.baseClassName("Location");
        project.addClass(realm);
        domain.config.EntityKindRule realmKind =
                new domain.config.EntityKindRule("Realm", List.of("Q1798538"));
        realmKind.propertyPid("P361");
        project.addEntityKindRule(realmKind);

        PopulationSourceExecution.Resolution withInheritedStep =
                PopulationSourceExecution.resolve(project, realm,
                        plan("Realm", new SourceRecipe(
                                "wikidata", "statement-membership",
                                Map.of("property", "P31", "values", "Q2221906"))));
        PopulationSourceExecution.Resolution offline =
                PopulationSourceExecution.resolve(project, realm, null);

        assertTrue(!withInheritedStep.available());
        assertTrue(!offline.available());
        assertTrue(withInheritedStep.reason().contains("classified locally"));
    }

    /** A skip states its cause; the run log showed \"Skip class OfficeHolding — .\" */
    @Test void aStatementClassSaysItIsProducedByReification() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        holding.statementSource(
                new domain.config.StatementClassSource("Backbone", "P39"));
        project.rootClass(holding);

        PopulationSourceExecution.Resolution resolved =
                PopulationSourceExecution.resolve(project, holding, null);

        assertTrue(!resolved.available());
        assertTrue(resolved.reason().contains("reification"),
                "and names reification rather than a missing source: " + resolved.reason());
    }

    private static SourceExecutionPlan plan(String className, SourceRecipe recipe) {
        return SourceExecutionPlan.compile(
                List.of(new SourceBinding(
                        SourceBindingTarget.classPopulation(className), recipe)),
                Datasources.standard());
    }

    private static SourceExecutionPlan.Step step(SourceRecipe recipe) {
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.classPopulation("Movie"), recipe);
        return SourceExecutionPlan.compile(List.of(binding), Datasources.standard())
                .steps().getFirst();
    }
}
