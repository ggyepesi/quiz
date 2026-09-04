package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A production cycle can alternate the two kinds of edge, and nothing saw it.
 *
 * <p>Owned and aggregated are both "this class has no population of its own, it comes
 * from that one", and each had its own walker: the validator checks owner edges and
 * source edges separately, {@code MembershipPattern} follows owner edges, {@code
 * ModelAggregates} follows source edges. Every one of them stops at the first edge of
 * the other kind, so a chain that alternates them is a cycle none can reach the end of —
 * it passed validation and then hung or refused somewhere downstream, at whichever
 * walker happened to be running.
 */
class MixedProductionCycleTest {

    /** Prize groups Award, Award is owned by Prize. Neither walker sees both edges. */
    private static GeneratedProjectModel alternatingCycle() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel prize = new GeneratedClassModel("Prize");
        prize.classKind(ClassKind.AGGREGATE);
        AggregateClassSource groups = new AggregateClassSource("Award", "awards");
        groups.keys().add(new AggregateClassSource.Key("year", "year"));
        prize.aggregateSource(groups);
        GeneratedFieldModel site = prize.addField(
                "award", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Award");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.ownedClass(true);

        project.addClass(prize);
        project.addClass(award);
        return project;
    }

    @Test void theChainRefusesInsteadOfWalkingForever() {
        GeneratedProjectModel project = alternatingCycle();

        ProductionChain chain = ProductionChain.of(project.findClass("Prize"), project);

        assertFalse(chain.resolved(), "there is no population at the end of a cycle");
        assertTrue(chain.refusal().contains("Production cycle"), chain.refusal());
        assertTrue(chain.refusal().contains("Prize") && chain.refusal().contains("Award"),
                "and it names the way round: " + chain.refusal());
    }

    /** Reached from either end, since either class is a legitimate thing to select. */
    @Test void itRefusesFromTheOtherEndToo() {
        GeneratedProjectModel project = alternatingCycle();

        assertFalse(ProductionChain.of(project.findClass("Award"), project).resolved());
    }

    /** And the model is invalid, which is where a modeller should learn it. */
    @Test void validationReportsTheMixedCycle() {
        GeneratedProjectModelValidator.ValidationResult result =
                GeneratedProjectModelValidator.validate(alternatingCycle());

        assertFalse(result.valid(), "a cycle in production is not a valid model");
        assertTrue(result.errors().stream()
                        .anyMatch(problem ->
                                problem.message().contains("Class dependency cycle")),
                "one walker over both edge kinds, or this stays invisible: "
                        + result.format());
    }

    /** A chain that alternates and does NOT close still resolves. */
    @Test void alternatingIsOnlyAProblemWhenItCloses() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        GeneratedClassModel byLetter = new GeneratedClassModel("NamesByLetter");
        byLetter.classKind(ClassKind.AGGREGATE);
        AggregateClassSource groups = new AggregateClassSource("Name", "names");
        groups.keys().add(new AggregateClassSource.Key("letter", "letter"));
        byLetter.aggregateSource(groups);
        project.addClass(person);
        project.addClass(name);
        project.addClass(byLetter);

        ProductionChain chain =
                ProductionChain.of(project.findClass("NamesByLetter"), project);

        assertTrue(chain.resolved(), chain.refusal());
        assertEquals("Person", chain.population().className(),
                "aggregated from Name, which is owned by Person — two edges, one chain");
        assertEquals(2, chain.links().size());
        assertTrue(chain.has(ClassDependencies.Kind.AGGREGATED)
                && chain.has(ClassDependencies.Kind.OWNED));
    }
}
