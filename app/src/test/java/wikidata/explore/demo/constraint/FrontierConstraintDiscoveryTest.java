package wikidata.explore.demo.constraint;

import org.junit.jupiter.api.Test;
import wikidata.explore.demo.constraint.FrontierConstraintDiscovery.Constraint;
import wikidata.explore.demo.constraint.FrontierConstraintDiscovery.Edge;
import wikidata.explore.demo.constraint.FrontierConstraintDiscovery.Scope;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontierConstraintDiscoveryTest {
    @Test void previewIsExactlyOneSharedPopulationFrontierStep() {
        String query = FrontierConstraintDiscovery.previewQuery(
                List.of("Q6412254"), "P39", "P39", List.of(), 61);

        assertTrue(query.contains("VALUES ?source { wd:Q6412254 }"));
        assertTrue(query.contains("?bridge p:P39 ?sourceStatement;"));
        assertTrue(query.contains("p:P39 ?candidateStatement"));
        assertTrue(query.contains("?candidateStatement ps:P39 ?candidate"));
        assertFalse(query.contains("P39+"), "the demo must not silently compute a closure");
        assertFalse(query.contains("P39*"), "the demo must not silently compute a closure");
    }

    @Test void constraintsAreAppliedToTheirDeclaredGraphRole() {
        List<Constraint> constraints = List.of(
                new Constraint(Scope.CANDIDATE_ANCESTOR, "P279", "Q116", "monarch", 3),
                new Constraint(Scope.CANDIDATE_NODE, "P1001", "Q145", "UK", 2),
                new Constraint(Scope.BRIDGE_NODE, "P27", "Q145", "UK", 2),
                new Constraint(Scope.CANDIDATE_STATEMENT, "P1001", "Q145", "UK", 2));

        String query = FrontierConstraintDiscovery.previewQuery(
                List.of("Q1"), "P39", "P39", constraints, 20);

        assertTrue(query.contains("?candidate wdt:P279* wd:Q116"));
        assertTrue(query.contains("?candidate wdt:P1001 wd:Q145"));
        assertTrue(query.contains("?bridge wdt:P27 wd:Q145"));
        assertTrue(query.contains("?candidateStatement pq:P1001 wd:Q145"));
    }

    @Test void discoveryProfilesNodesAncestorsAndStatementQualifiersFromTheSample() {
        String query = FrontierConstraintDiscovery.constraintQuery(List.of(
                new Edge("Q1", "one", "Q2", "two", "Q3", "three")), "P39", 100);

        assertTrue(query.contains("?candidate wdt:P279+ ?value"));
        assertTrue(query.contains("FILTER(?property != wd:P279)"));
        assertTrue(query.contains("?bridge ?predicate ?value"));
        assertTrue(query.contains("?candidateStatement ps:P39 ?candidate;"));
        assertTrue(query.contains("?qualifierPredicate ?value"));
        assertTrue(query.contains("?property wikibase:qualifier ?qualifierPredicate"));
    }
}
