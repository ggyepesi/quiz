package quiz.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewableJsonSourceTest {

    @Test void syntheticDisplayFieldIsReadableWhereTheFieldApiOffersIt() {
        var person = new wikidata.explore.extract.WikidataDynamicObject(
                "Q71231", "Charles the Bald");

        ViewableView.Field display = ViewableJson.fieldOf(
                person, objectview.field.ViewableContractFieldSet.DISPLAY_KEY);

        assertEquals("Charles the Bald", display.value());
        assertEquals("Charles the Bald", ViewableJson.stringValue(
                person, objectview.field.ViewableContractFieldSet.DISPLAY_KEY));
    }

    @Test void statementTripleIsAnExpandableSourceField() {
        var nomination = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "Nomination");
        nomination.wikidataStatementSources(List.of(
                new quiz.source.WikidataStatementSource(
                        "Q28$a", "Q28", "P1411", "Q103916", "Best Actor")));

        ViewableView.Field source = source(ViewableJson.of(nomination,
                sourceTicks("@view:display", "statementJson", "guid")));

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

        ViewableView.Field source = source(ViewableJson.of(person,
                sourceTicks("@view:display", "identity")));

        assertEquals("refs", source.kind());
        assertEquals("Q42", source.refs().getFirst().name());
        ViewableView.Field identity = source.refs().getFirst().inline().fields().stream()
                .filter(field -> "identity".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("Q42", identity.label());
        assertEquals("https://www.wikidata.org/wiki/Q42", identity.url());
    }

    @Test void defaultEntitySourceCaptionIsTheSameQidLink() {
        var person = new wikidata.explore.extract.WikidataDynamicObject(
                "Q42", "Douglas Adams");

        ViewableView.Ref source = source(ViewableJson.of(person)).refs().getFirst();

        assertEquals("Q42", source.name());
        assertEquals("https://www.wikidata.org/wiki/Q42", source.url());
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

        // The default ticks each statement's DISPLAY: captions, nothing to open.
        ViewableView view = ViewableJson.of(award);
        ViewableView.Field source = source(view);

        assertEquals("Le Duc Tho — Nobel Peace Prize", view.name());
        assertEquals("wikidataSource", source.name());
        assertEquals("refs", source.kind());
        assertEquals(List.of(
                        "Q66107 — P166 → Nobel Peace Prize (Q35637)",
                        "Q233969 — P166 → Nobel Peace Prize (Q35637)"),
                source.refs().stream().map(ViewableView.Ref::name).toList());
        assertEquals(0, source.refs().stream()
                .filter(ref -> ref.inline() != null).count());

        // Ticking a field under the statement makes each one expandable.
        ViewableView.Field ticked = source(ViewableJson.of(award,
                sourceTicks("@view:display", "guid")));
        assertEquals(2, ticked.refs().stream()
                .filter(ref -> ref.inline() != null).count());
    }

    private static objectview.viewconfig.ViewConfig sourceTicks(String... fields) {
        objectview.viewconfig.ViewConfig statement = objectview.viewconfig.ViewConfig.leaf();
        for (String field : fields) {
            statement.addField(field, objectview.viewconfig.ViewConfig.leaf());
        }
        objectview.viewconfig.ViewConfig root = objectview.viewconfig.ViewConfig.leaf();
        root.addField("wikidataSource", statement);
        return root;
    }

    private static ViewableView.Field source(ViewableView view) {
        return view.fields().stream().filter(f -> "wikidataSource".equals(f.name()))
                .findFirst().orElseThrow(() -> new AssertionError(view.fields()));
    }
}
