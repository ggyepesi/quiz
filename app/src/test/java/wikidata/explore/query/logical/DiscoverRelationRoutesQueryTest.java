package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoverRelationRoutesQueryTest {

    @Test
    void examplesStateWhichTargetsMakeARouteRelevant() {
        var examples = DiscoverRelationRoutesQuery.parseExamples("""
                Q22878 → Q12748409, Q3991395
                Q123 -> Q456
                """);

        assertEquals(2, examples.size());
        assertEquals("Q22878", examples.getFirst().sourceQid());
        assertEquals(java.util.List.of("Q12748409", "Q3991395"),
                examples.getFirst().expectedTargetQids());
    }

    @Test
    void aLineWithoutAnExplicitSourceTargetBoundaryIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DiscoverRelationRoutesQuery.parseExamples("Q22878 Q12748409"));
        assertTrue(failure.getMessage().contains("Qsource"));
    }

    @Test
    void incomingDiscoveryIsRestrictedToDirectWikidataEntityProperties() {
        String query = DiscoverRelationRoutesQuery.incomingQuery(
                "Q22878", 101);
        assertTrue(query.contains("wikibase:directClaim"));
        assertTrue(query.contains("http://www.wikidata.org/entity/"));
        assertTrue(query.endsWith("LIMIT 101"));
        assertFalse(query.contains("ORDER BY"),
                "the endpoint must be able to stop at the per-entity cap without "
                        + "sorting every incoming edge first");
    }

    @Test
    void routeStatementsMatchGenerationWhileOrdinaryStatementDiscoveryIsUnchanged() {
        String routeQuery = wikidata.statement.EntityStatementSummary.routeQuery(
                java.util.List.of("Q22878"));
        assertTrue(routeQuery.indexOf("OPTIONAL")
                        < routeQuery.indexOf("?prop wikibase:statementProperty"),
                "an unknown/novalue child can still carry the role qualifier that "
                        + "leads to the children group");
        assertTrue(routeQuery.contains("wikibase:DeprecatedRank"),
                "route discovery must not offer a statement generation will drop");

        String ordinary = wikidata.statement.EntityStatementSummary.query(
                java.util.List.of("Q22878"));
        assertTrue(ordinary.indexOf("?prop wikibase:statementProperty")
                        < ordinary.indexOf("OPTIONAL"),
                "ordinary statement discovery still requires the main value; only its "
                        + "qualifier block is optional");
        assertFalse(ordinary.contains("wikibase:DeprecatedRank"),
                "route-specific projection must not silently change the statement "
                        + "inspection tool");
    }

    /** Generation reads a direct property's truthy values, so a normal-rank value next
     *  to a preferred one must not nominate a direct route; a statement step still reads
     *  every non-deprecated statement, so its qualifier still nominates one. */
    @Test
    void aDirectRouteIsDiscoveredOnlyFromTheValuesGenerationReads() {
        String entity = "http://www.wikidata.org/entity/";
        String rank = "http://wikiba.se/ontology#";
        java.util.List<wikidata.WikidataBinding> rows = java.util.List.of(
                row(entity + "Q101", entity + "P40", "s1", rank + "PreferredRank",
                        entity + "Q201", null, null),
                row(entity + "Q101", entity + "P40", "s2", rank + "NormalRank",
                        entity + "Q202", entity + "P3831", entity + "Q301"),
                row(entity + "Q101", entity + "P22", "s3", rank + "NormalRank",
                        entity + "Q401", null, null));
        var summary = wikidata.statement.EntityStatementSummary.fromRows(
                java.util.List.of("Q101"), rows).getFirst();
        java.util.Set<domain.analysis.RelationRouteDiscovery.Segment> segments =
                new java.util.LinkedHashSet<>();

        DiscoverRelationRoutesQuery.addOutgoing(summary, segments);

        assertEquals(java.util.List.of(
                        "P40 -> Q201", "statement:P40/qualifier:P3831 -> Q301", "P22 -> Q401"),
                segments.stream().map(segment -> segment.steps().stream()
                                .map(step -> step.relation().relationId())
                                .collect(java.util.stream.Collectors.joining("/"))
                        + " -> " + segment.targetQid()).toList());
    }

    private static wikidata.WikidataBinding row(String e, String prop, String st,
            String rank, String val, String qualp, String qv) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("e", e);
        values.put("prop", prop);
        values.put("st", st);
        values.put("statementRank", rank);
        values.put("val", val);
        if (qualp != null) values.put("qualp", qualp);
        if (qv != null) values.put("qv", qv);
        return new wikidata.WikidataBinding(values);
    }

    @Test
    void anIncomingTimeoutIsABoundNotAWholeRunFailure() {
        assertTrue(DiscoverRelationRoutesQuery.incomingFailureIsLimit(
                new java.net.http.HttpTimeoutException("timed out")));
        assertTrue(DiscoverRelationRoutesQuery.incomingFailureIsLimit(
                new wikidata.WikidataSparqlClient.SparqlHttpException(
                        500, -1, "Query timeout")));
        assertFalse(DiscoverRelationRoutesQuery.incomingFailureIsLimit(
                new IllegalArgumentException("invalid query")));
    }
}
