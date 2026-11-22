package quiz.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewableJsonSourceTest {

    @Test void syntheticDisplayFieldIsReadableWhereTheFieldApiOffersIt() {
        var person = new domain.runtime.WikidataDynamicObject(
                "Q71231", "Charles the Bald");

        ViewableView.Field display = ViewableJson.fieldOf(
                person, objectview.field.ViewableContractFieldSet.DISPLAY_KEY);

        assertEquals("Charles the Bald", display.value());
        assertEquals("Charles the Bald", ViewableJson.stringValue(
                person, objectview.field.ViewableContractFieldSet.DISPLAY_KEY));
    }

    @Test void statementTripleIsAnExpandableSourceField() {
        var nomination = new domain.runtime.WikidataDynamicObject(
                "modeled-key", "Nomination");
        nomination.wikidataStatementSources(List.of(
                new domain.provenance.WikidataStatementSource(
                        "Q28$a", "Q28", "P1411", "Q103916", "Best Actor")));

        ViewableView.Field source = statements(ViewableJson.of(nomination,
                statementTicks("@view:display", "statementJson", "guid")));

        assertEquals("wikidataStatements", source.name());
        assertEquals("refs", source.kind());
        assertEquals("Q28 — P1411 → Best Actor (Q103916)",
                source.refs().getFirst().name());
        assertNull(source.refs().getFirst().url(),
                "the complete triple must not pretend to have one destination");
        assertEquals(java.util.Arrays.asList(
                        "https://www.wikidata.org/wiki/Q28",
                        null,
                        "https://www.wikidata.org/wiki/Property:P1411",
                        null,
                        "https://www.wikidata.org/wiki/Q103916"),
                source.refs().getFirst().links().stream()
                        .map(ViewableView.LinkPart::url).toList());
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

    @Test void entitySourceDisplayIsAQidLinkWithoutADuplicateBodyRow() {
        var person = new domain.runtime.WikidataDynamicObject(
                "Q42", "Douglas Adams");

        ViewableView.Field source = source(ViewableJson.of(person,
                sourceTicks("@view:display", "identity")));

        assertEquals("refs", source.kind());
        ViewableView.Ref identity = source.refs().getFirst();
        assertEquals("Q42", identity.name());
        assertEquals("https://www.wikidata.org/wiki/Q42", identity.url());
        assertNull(identity.inline(),
                "DISPLAY is painted once as the linked caption, not repeated in a body");
    }

    @Test void defaultEntitySourceCaptionIsTheSameQidLink() {
        var person = new domain.runtime.WikidataDynamicObject(
                "Q42", "Douglas Adams");

        ViewableView.Ref source = source(ViewableJson.of(person)).refs().getFirst();

        assertEquals("Q42", source.name());
        assertEquals("https://www.wikidata.org/wiki/Q42", source.url());
    }

    @Test void multipleStatementsShareOneExpandableWebSourceField() {
        var award = new domain.runtime.WikidataDynamicObject(
                "modeled-key", "Le Duc Tho — Nobel Peace Prize");
        award.wikidataStatementSources(List.of(
                new domain.provenance.WikidataStatementSource(
                        "Q66107$a", "Q66107", "P166", "Q35637",
                        "Nobel Peace Prize"),
                new domain.provenance.WikidataStatementSource(
                        "Q233969$b", "Q233969", "P166", "Q35637",
                        "Nobel Peace Prize")));

        // The default ticks each statement's DISPLAY: captions, nothing to open.
        ViewableView view = ViewableJson.of(award);
        ViewableView.Field source = statements(view);

        assertEquals("Le Duc Tho — Nobel Peace Prize", view.name());
        assertEquals("wikidataStatements", source.name());
        assertEquals("refs", source.kind());
        assertEquals(List.of(
                        "Q66107 — P166 → Nobel Peace Prize (Q35637)",
                        "Q233969 — P166 → Nobel Peace Prize (Q35637)"),
                source.refs().stream().map(ViewableView.Ref::name).toList());
        assertEquals(0, source.refs().stream()
                .filter(ref -> ref.inline() != null).count());

        // Ticking a field under the statement makes each one expandable.
        ViewableView.Field ticked = statements(ViewableJson.of(award,
                statementTicks("@view:display", "guid")));
        assertEquals(2, ticked.refs().stream()
                .filter(ref -> ref.inline() != null).count());
    }

    private static objectview.viewconfig.ViewConfig sourceTicks(String... fields) {
        return ticks("wikidataSource", fields);
    }

    private static objectview.viewconfig.ViewConfig statementTicks(String... fields) {
        return ticks("wikidataStatements", fields);
    }

    private static objectview.viewconfig.ViewConfig ticks(
            String fieldName, String... fields) {
        objectview.viewconfig.ViewConfig statement = objectview.viewconfig.ViewConfig.leaf();
        for (String field : fields) {
            statement.addField(field, objectview.viewconfig.ViewConfig.leaf());
        }
        objectview.viewconfig.ViewConfig root = objectview.viewconfig.ViewConfig.leaf();
        root.addField(fieldName, statement);
        return root;
    }

    private static ViewableView.Field source(ViewableView view) {
        return view.fields().stream().filter(f -> "wikidataSource".equals(f.name()))
                .findFirst().orElseThrow(() -> new AssertionError(view.fields()));
    }

    private static ViewableView.Field statements(ViewableView view) {
        return view.fields().stream()
                .filter(f -> "wikidataStatements".equals(f.name()))
                .findFirst().orElseThrow(() -> new AssertionError(view.fields()));
    }
}
