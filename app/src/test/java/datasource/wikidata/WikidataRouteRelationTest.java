package datasource.wikidata;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One owner spells a Wikidata route step into a relation id and reads it back. */
class WikidataRouteRelationTest {

    @Test void everyKindRoundTripsThroughItsRelationId() {
        for (WikidataRouteRelation.Kind kind : WikidataRouteRelation.Kind.values()) {
            WikidataRouteRelation relation = new WikidataRouteRelation(kind, "p40");
            assertEquals(relation, WikidataRouteRelation.parse(relation.relation()));
        }
        assertEquals("statement:P40", new WikidataRouteRelation(
                WikidataRouteRelation.Kind.STATEMENT, "P40").relation().relationId());
    }

    @Test void anotherProviderOrAnUnknownSpellingIsNotAWikidataStep() {
        assertNull(WikidataRouteRelation.parse(new GraphRelation("dbpedia", "P40")));
        assertNull(WikidataRouteRelation.parse(new GraphRelation("wikidata", "child")));
        assertNull(WikidataRouteRelation.parse(
                new GraphRelation("wikidata", "statement:child")));
    }

    @Test void statementsAndQualifiersAreReadForwardOnly() {
        new WikidataRouteRelation(WikidataRouteRelation.Kind.PROPERTY, "P22")
                .requireDirection(GraphTraversalDirection.INCOMING);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new WikidataRouteRelation(WikidataRouteRelation.Kind.QUALIFIER, "P3831")
                        .requireDirection(GraphTraversalDirection.INCOMING));
        assertEquals("Qualifier P3831 steps support outgoing traversal only",
                refused.getMessage());
    }

    @Test void aRouteIsValidatedAsAConnectedTypedWalk() {
        IllegalArgumentException qualifierFirst = assertThrows(
                IllegalArgumentException.class,
                () -> WikidataRelationRouteSpec.parse("""
                        [{"id":"bad","label":"Qualifier first","steps":[
                          {"relation":"qualifier:P3831","direction":"OUTGOING"}]}]
                        """));
        assertTrue(qualifierFirst.getMessage().contains(
                "step 1: Qualifier P3831 requires a statement node"),
                qualifierFirst.getMessage());

        IllegalArgumentException propertyAfterStatement = assertThrows(
                IllegalArgumentException.class,
                () -> WikidataRelationRouteSpec.parse("""
                        [{"id":"bad","label":"Wrong transition","steps":[
                          {"relation":"statement:P40","direction":"OUTGOING"},
                          {"relation":"P527","direction":"OUTGOING"}]}]
                        """));
        assertTrue(propertyAfterStatement.getMessage().contains(
                "step 2: Property P527 requires an entity node"),
                propertyAfterStatement.getMessage());

        IllegalArgumentException statementTerminal = assertThrows(
                IllegalArgumentException.class,
                () -> WikidataRelationRouteSpec.parse("""
                        [{"id":"bad","label":"Statement terminal","steps":[
                          {"relation":"statement:P40","direction":"OUTGOING"}]}]
                        """));
        assertTrue(statementTerminal.getMessage().contains(
                "must end at an entity, not a statement"),
                statementTerminal.getMessage());
    }
}
