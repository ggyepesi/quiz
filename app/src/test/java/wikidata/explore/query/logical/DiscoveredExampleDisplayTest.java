package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of an example is to say what a property holds. A value nobody can read —
 * a bare QID, a raw timestamp — is a discovery that says nothing, so the reading of an
 * example is decided here, once, for every kind of value the profile can return.
 */
class DiscoveredExampleDisplayTest {

    /** Without ?exampleLabel the label service names nothing, and every entity example
     *  can only be shown as its QID. */
    @Test void theProfileAsksForTheExamplesLabel() {
        for (String sparql : List.of(
                SparqlQueries.discoverOutgoingProperties(List.of("Q72717"), 10),
                SparqlQueries.discoverIncomingProperties(List.of("Q72717"), 10))) {
            assertTrue(sparql.contains("?exampleLabel"),
                    "the example's label is never fetched:\n" + sparql);
            assertTrue(sparql.contains("wikibase:label"),
                    "the label service is what binds it:\n" + sparql);
        }
    }

    @Test void anEntityExampleReadsAsItsLabel() {
        assertEquals("Harvard University",
                DiscoverClassPropertiesQuery.display("Harvard University", "Q13371",
                        "http://www.wikidata.org/entity/Q13371"));
    }

    /** The label service echoes a literal back unchanged; an echo is not a label, and
     *  taking it as one is how a timestamp reached the screen as a timestamp. */
    @Test void aTimeLiteralReadsAsADate() {
        assertEquals("1732-02-22",
                DiscoverClassPropertiesQuery.display("1732-02-22T00:00:00Z", "",
                        "1732-02-22T00:00:00Z"));
    }

    /** An external identifier IS its string — there is nothing more readable to show. */
    @Test void anIdentifierReadsAsItself() {
        assertEquals("af9e633b-3b6e-47d7-9716-79d9e9c0b726",
                DiscoverClassPropertiesQuery.display("af9e633b-3b6e-47d7-9716-79d9e9c0b726",
                        "", "af9e633b-3b6e-47d7-9716-79d9e9c0b726"));
    }

    /** Only a wikidata/commons URI is shortened to its last segment. Shortening an
     *  arbitrary URL would drop the part that identifies it. */
    @Test void anExternalUrlIsShownWhole() {
        assertEquals("https://www.oscars.org/oscars/ceremonies/1948",
                DiscoverClassPropertiesQuery.display("", "",
                        "https://www.oscars.org/oscars/ceremonies/1948"));
        assertEquals("Elia Kazan.jpg",
                DiscoverClassPropertiesQuery.display("", "",
                        "http://commons.wikimedia.org/wiki/Special:FilePath/Elia%20Kazan.jpg"));
    }

    /** With no label at all the QID is still better than the entity URI. */
    @Test void anUnlabelledEntityFallsBackToItsQid() {
        assertEquals("Q13371",
                DiscoverClassPropertiesQuery.display(null, "Q13371",
                        "http://www.wikidata.org/entity/Q13371"));
    }
}
