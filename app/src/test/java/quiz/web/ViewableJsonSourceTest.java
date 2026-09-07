package quiz.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewableJsonSourceTest {

    @Test void statementTripleIsAnExpandableSourceField() {
        var nomination = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "Nomination");
        nomination.wikidataStatementSources(List.of(
                new quiz.source.WikidataStatementSource(
                        "Q28$a", "Q28", "P1411", "Q103916", "Best Actor")));

        ViewableView.Field source = ViewableJson.of(nomination).fields().getLast();

        assertEquals("wikidataSource", source.name());
        assertEquals("refs", source.kind());
        assertEquals("Q28 — P1411 → Best Actor (Q103916)",
                source.refs().getFirst().name());
        ViewableView.Field exact = source.refs().getFirst().inline().fields().stream()
                .filter(field -> "statementJson".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("https://www.wikidata.org/w/rest.php/wikibase/v1/statements/Q28%24a",
                exact.url());
        ViewableView.Field guid = source.refs().getFirst().inline().fields().stream()
                .filter(field -> "guid".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("Q28$a", guid.label());
        assertEquals(exact.url(), guid.url());
    }

    @Test void entitySourceRemainsVisibleAsAQidLink() {
        var person = new wikidata.explore.extract.WikidataDynamicObject(
                "Q42", "Douglas Adams");

        ViewableView.Field source = ViewableJson.of(person).fields().getLast();

        assertEquals("refs", source.kind());
        assertEquals("Q42", source.refs().getFirst().name());
        ViewableView.Field identity = source.refs().getFirst().inline().fields().stream()
                .filter(field -> "identity".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("Q42", identity.label());
        assertEquals("https://www.wikidata.org/wiki/Q42", identity.url());
    }

    @Test void multipleStatementsShareOneExpandableWebSourceField() {
        var award = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "Le Duc Tho — Nobel Peace Prize");
        award.wikidataStatementSources(List.of(
                new quiz.source.WikidataStatementSource(
                        "Q66107$a", "Q66107", "P166", "Q35637",
                        "Nobel Peace Prize"),
                new quiz.source.WikidataStatementSource(
                        "Q233969$b", "Q233969", "P166", "Q35637",
                        "Nobel Peace Prize")));

        ViewableView view = ViewableJson.of(award);
        ViewableView.Field source = view.fields().getLast();

        assertEquals("Le Duc Tho — Nobel Peace Prize", view.name());
        assertEquals("wikidataSource", source.name());
        assertEquals("refs", source.kind());
        assertEquals(List.of(
                        "Q66107 — P166 → Nobel Peace Prize (Q35637)",
                        "Q233969 — P166 → Nobel Peace Prize (Q35637)"),
                source.refs().stream().map(ViewableView.Ref::name).toList());
        assertEquals(2, source.refs().stream()
                .filter(ref -> ref.inline() != null).count());
    }
}
