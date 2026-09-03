package canonical;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One engine, applying a plan and deciding nothing.
 *
 * <p>Four paths did this before — statement dedup with its work-anchored preference,
 * aggregate grouping, owned composition, and the class-kind branch in Canonicalizer —
 * each with a fixed idea of what combining means. That is why "union the laureates while
 * requiring the category to agree" could not be expressed: no path could be told.
 */
class KeyedReductionTest {

    /** A candidate is a bag of values plus what production supplies. */
    private record Row(String className, Map<String, Object> values,
                       String occurrence) implements Candidate {
        @Override public Object value(String fieldPath) { return values.get(fieldPath); }
        @Override public String structuralIdentity(KeyComponent.Kind kind) {
            return kind == KeyComponent.Kind.SOURCE_OCCURRENCE ? occurrence : "";
        }
    }

    private static Row row(String occurrence, Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new Row("Award", values, occurrence);
    }

    private static final StableForm STABLE = value ->
            value == null ? "" : String.valueOf(value);

    private static CanonicalizationPlan plan(List<KeyComponent> key,
                                             Map<String, Reduction> reductions) {
        return new CanonicalizationPlan("Award", key,
                MissingKeyPolicy.defaultPolicy(), reductions);
    }

    /**
     * Nobel's shape: three laureates carry three statements to one award, and the award
     * is what the key says it is. The laureates union; nothing else has to be said.
     */
    @Test void severalCandidatesBecomeOneInstanceAndTheirCollectionsCombine() {
        var plan = plan(List.of(KeyComponent.field("category"), KeyComponent.field("year")),
                Map.of("laureates", Reduction.UNION_DISTINCT));

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics", "year", "1903", "laureates", List.of("Curie")),
                row("s2", "category", "Physics", "year", "1903", "laureates", List.of("Becquerel")),
                row("s3", "category", "Chemistry", "year", "1911", "laureates", List.of("Curie"))),
                STABLE);

        assertEquals(2, result.instances().size(), "two awards, not three statements");
        var physics = result.instances().get(0);
        assertEquals(List.of("Becquerel", "Curie"), physics.values().get("laureates"));
        assertEquals(2, physics.candidateCount());
        assertEquals(1, result.reducedPartitions());
        assertEquals(List.of(), result.conflicts());
    }

    /**
     * Ordered by the values' own stable form, not by arrival. A snapshot is reproducible
     * from its model, and R18 records that WDQS can answer a partial result as a silent
     * 200 — so encounter order is not a property of the model at all.
     */
    @Test void aUnionIsOrderedByTheValuesAndNotByWhenTheyArrived() {
        var plan = plan(List.of(KeyComponent.field("category")),
                Map.of("laureates", Reduction.UNION_DISTINCT));

        var forwards = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics", "laureates", List.of("Zeeman", "Curie")),
                row("s2", "category", "Physics", "laureates", List.of("Becquerel"))), STABLE);
        var backwards = KeyedReduction.reduce(plan, List.of(
                row("s2", "category", "Physics", "laureates", List.of("Becquerel")),
                row("s1", "category", "Physics", "laureates", List.of("Curie", "Zeeman"))), STABLE);

        assertEquals(List.of("Becquerel", "Curie", "Zeeman"),
                forwards.instances().get(0).values().get("laureates"));
        assertEquals(forwards.instances().get(0).values().get("laureates"),
                backwards.instances().get(0).values().get("laureates"),
                "the same candidates in a different order produce the same instance");
    }

    /** Disagreement is reported and the run continues — it is a fact about the data. */
    @Test void aDisagreementIsReportedRatherThanRankedOrFatal() {
        var plan = plan(List.of(KeyComponent.field("category")),
                Map.of("motivation", Reduction.REQUIRE_AGREEMENT));

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics", "motivation", "for radiation"),
                row("s2", "category", "Physics", "motivation", "for radioactivity")), STABLE);

        assertEquals(1, result.instances().size(), "the instance is still produced");
        assertEquals(1, result.conflicts().size());
        var conflict = result.conflicts().get(0);
        assertEquals("motivation", conflict.fieldPath());
        assertEquals("for radiation", conflict.kept());
        assertEquals("for radioactivity", conflict.rejected());
    }

    /**
     * Keying on the source occurrence is what "one instance per thing the datasource
     * produced" means. It used to be spelled as an empty key, which the same engine
     * would have read as one partition for the whole class.
     */
    @Test void keyingOnTheOccurrenceLeavesEveryCandidateStandingAlone() {
        var plan = plan(List.of(KeyComponent.sourceOccurrence()), Map.of());

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics"),
                row("s2", "category", "Physics")), STABLE);

        assertEquals(2, result.instances().size());
        assertEquals(0, result.reducedPartitions());
        assertTrue(plan.onePerOccurrence());
    }

    /**
     * A candidate missing a key component is KEPT and counted, not dropped.
     *
     * <p>Rejecting was the first default here, and running it over the shipped snapshots
     * would have discarded 99 real records. A counted drop is still a drop, and the rule
     * that lets a reducer default at all says a default may only be non-destructive.
     */
    @Test void aCandidateMissingAKeyComponentIsKeptAndCounted() {
        var plan = plan(List.of(KeyComponent.field("category")), Map.of());

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics"),
                row("s2", "year", "1903")), STABLE);

        assertEquals(2, result.instances().size(), "both records still exist");
        assertEquals(1, result.unkeyed().size());
        assertEquals("category", result.unkeyed().get(0).missing().fieldPath());
        assertEquals(MissingKeyPolicy.INCOMPLETE_GROUP,
                result.unkeyed().get(0).applied());
    }

    /** Rejecting stays available — it is a decision a class makes, not a default. */
    @Test void rejectingIsAvailableForAClassThatMeansIt() {
        var plan = new CanonicalizationPlan("Award",
                List.of(KeyComponent.field("category")),
                MissingKeyPolicy.REJECT_CANDIDATE, Map.of());

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics"),
                row("s2", "year", "1903")), STABLE);

        assertEquals(1, result.instances().size());
        assertEquals(2, result.candidateCount(), "and the count still says what came in");
    }

    @Test void aClassWithNoKeyIsRefusedRatherThanCollapsedIntoOneInstance() {
        var unidentified = CanonicalizationPlan.unidentified("Award");

        assertThrows(IllegalArgumentException.class,
                () -> KeyedReduction.reduce(unidentified, List.of(row("s1")), STABLE),
                "group by nothing is one group — never what an unchosen key meant");
    }

    /** Nothing prefers a survivor. Which candidate is kept follows from the reducer. */
    @Test void noCandidateIsPreferredByTheEngineItself() {
        var plan = plan(List.of(KeyComponent.field("category")),
                Map.of("motivation", Reduction.REQUIRE_AGREEMENT));

        var result = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics", "motivation", "first"),
                row("s2", "category", "Physics", "motivation", "second")), STABLE);

        assertEquals("first", result.instances().get(0).values().get("motivation"),
                "the first is kept because agreement keeps one, not because it won");
    }

    /**
     * The grain's effect, said out loud. Neither collapsing nor a conflict is visible in
     * the finished instances, so a number that moves between runs is what tells a
     * modeller their configuration changed meaning.
     */
    @Test void whatTheGrainDidIsReportedAsCountsRatherThanErrors() {
        var plan = plan(List.of(KeyComponent.field("category")),
                Map.of("motivation", Reduction.REQUIRE_AGREEMENT));

        String report = KeyedReduction.reduce(plan, List.of(
                row("s1", "category", "Physics", "motivation", "for radiation"),
                row("s2", "category", "Physics", "motivation", "for radioactivity"),
                row("s3", "year", "1903")), STABLE).report();

        assertTrue(report.contains("3 candidate(s) became 2 instance(s)"), report);
        assertTrue(report.contains("1 combined more than one"), report);
        assertTrue(report.contains("could not be keyed"), report);
        assertTrue(report.contains("for radiation vs for radioactivity"), report);
    }
}
