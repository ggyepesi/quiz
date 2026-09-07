package quiz.source;

import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entity and statement provenance use one ordinary, inspectable field. */
class WikidataSourceFieldsTest {

    @Test void aSourceEntityPublishesItsQidAsALinkField() {
        var entity = new wikidata.explore.extract.WikidataDynamicObject("Q42", "Douglas Adams");

        FieldSet fields = FieldSet.of(entity);
        FieldRef source = fields.field("wikidataSource");

        assertEquals(objectview.field.FieldRole.PROVENANCE, source.role());
        assertEquals("Wikidata source", source.label());
        assertEquals(FieldKind.COLLECTION, source.kind());
        quiz.source.WikidataSource value = (quiz.source.WikidataSource)
                assertInstanceOf(List.class, fields.read("wikidataSource")).getFirst();
        assertEquals("Q42", value.getDisplayName());
        assertTrue(FieldSet.of(value).field("identity").link());
    }

    @Test void aStatementRecordPublishesItsWholeSourceAsAReferenceField() {
        WikidataStatementSource statement = new WikidataStatementSource(
                "Q28$a", "Q28", "P1411", "Q103916", "Best Actor");
        var record = new wikidata.explore.extract.WikidataDynamicObject(
                "Q28$a", "modeled record");
        record.addWikidataStatementSource(statement);

        FieldSet fields = FieldSet.of(record);
        FieldRef source = fields.field("wikidataSource");

        assertEquals(FieldKind.COLLECTION, source.kind());
        assertTrue(source.annotatedReference());
        assertEquals(statement,
                assertInstanceOf(List.class, fields.read("wikidataSource")).getFirst());
        FieldSet statementFields = FieldSet.of(statement);
        assertTrue(statementFields.field("statementSubject").link());
        assertTrue(statementFields.field("statementProperty").link());
        assertTrue(statementFields.field("statementObject").link());
        assertNull(statementFields.read("statementValue"),
                "a linked entity object has no duplicate literal-value row");
        assertTrue(statementFields.field("guid").link());
        assertEquals("Q28$a|https://www.wikidata.org/w/rest.php/"
                        + "wikibase/v1/statements/Q28%24a",
                statementFields.read("guid"));
        assertTrue(statementFields.field("statementJson").link());
    }

    @Test void aLiteralObjectUsesTheValueRowInsteadOfAnEmptyLinkRow() {
        WikidataStatementSource statement = new WikidataStatementSource(
                "Q28$a", "Q28", "P1476", "A literal title", "");
        FieldSet fields = FieldSet.of(statement);

        assertNull(fields.read("statementObject"));
        assertEquals("A literal title", fields.read("statementValue"));
    }

    @Test void anExpandedStatementSourceCarriesAllFiveDestinationsItNames()
            throws Exception {
        WikidataStatementSource statement = new WikidataStatementSource(
                "Q28$a", "Q28", "P1411", "Q103916", "Best Actor");
        objectview.render.Card[] card = new objectview.render.Card[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> card[0] =
                new objectview.render.Card(statement,
                        objectview.viewconfig.ViewConfig.all(
                                WikidataStatementSource.class), false));

        assertEquals(6, count(card[0], objectview.render.LinkRow.class),
                "containing claim, subject, property, object, GUID and exact JSON");
    }

    @Test void anAggregatedRecordKeepsEverySourceStatementInTheSameField() {
        var record = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "modeled record");
        record.wikidataStatementSources(List.of(
                new WikidataStatementSource("Q1$a", "Q1", "P166", "Q2", "Prize"),
                new WikidataStatementSource("Q3$b", "Q3", "P166", "Q2", "Prize")));

        FieldSet fields = FieldSet.of(record);
        FieldRef source = fields.field("wikidataSource");

        assertEquals(FieldKind.COLLECTION, source.kind());
        assertTrue(source.collection());
        assertEquals(2, assertInstanceOf(
                List.class, fields.read("wikidataSource")).size());
    }

    private static int count(java.awt.Component root, Class<?> type) {
        int found = type.isInstance(root) ? 1 : 0;
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                found += count(child, type);
            }
        }
        return found;
    }
}
