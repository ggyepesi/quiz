package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import quiz.source.WikidataRelationRouteMatch;
import quiz.source.WikidataSource;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikidataRelationRouteCoverageTest {

    @Test void settlesEachExactRouteTerminalAgainstTheFinalField() {
        WikidataDynamicObject owner = character("Q101", "Laocoon");
        owner.put("children", List.of(character("Q201", "Antiphantes")));
        owner.addWikidataRelationRouteMatch(match(
                "direct", "Direct child", "Q201"));
        owner.addWikidataRelationRouteMatch(match(
                "qualified", "Named child group", "Q202"));

        List<WikidataRelationRouteCoverage.Summary> result =
                WikidataRelationRouteCoverage.settle(List.of(owner));

        assertEquals(List.of(
                        "direct Q201 RETAINED",
                        "qualified Q202 NOT_RETAINED"),
                owner.wikidataRelationRouteMatches().stream()
                        .map(value -> value.routeId() + " " + value.terminal().qid()
                                + " " + value.status().name()).toList());
        assertEquals(List.of(
                        "direct 1/1 owners 1/1 matches",
                        "qualified 0/1 owners 0/1 matches"),
                result.stream().map(value -> value.routeId() + " "
                        + value.retainedOwners() + "/" + value.matchedOwners()
                        + " owners " + value.retainedMatches() + "/"
                        + value.matches() + " matches").toList());
    }

    @Test void duplicateCopiesDoNotInflateCoverageCounts() {
        WikidataDynamicObject first = character("Q101", "Laocoon");
        WikidataDynamicObject copy = character("Q101", "Laocoon");
        first.put("children", List.of(character("Q201", "Antiphantes")));
        copy.put("children", List.of(character("Q201", "Antiphantes")));
        first.addWikidataRelationRouteMatch(match("direct", "Direct child", "Q201"));
        copy.addWikidataRelationRouteMatch(match("direct", "Direct child", "Q201"));

        WikidataRelationRouteCoverage.Summary summary =
                WikidataRelationRouteCoverage.settle(List.of(first, copy)).getFirst();

        assertEquals(1, summary.matchedOwners());
        assertEquals(1, summary.retainedOwners());
        assertEquals(1, summary.matches());
        assertEquals(1, summary.retainedMatches());
    }

    private static WikidataRelationRouteMatch match(
            String id, String route, String terminal) {
        return new WikidataRelationRouteMatch(
                "Character", "children", id, route,
                new WikidataSource(terminal),
                WikidataRelationRouteMatch.Status.MATCHED);
    }

    private static WikidataDynamicObject character(String qid, String label) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, label);
        value.type("Character");
        return value;
    }
}
