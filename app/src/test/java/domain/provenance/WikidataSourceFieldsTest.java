package domain.provenance;

import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entity identity and statement evidence use separate ordinary, inspectable fields. */
class WikidataSourceFieldsTest {

    @Test void aSourceEntityPublishesItsQidAsALinkField() {
        var entity = new domain.runtime.WikidataDynamicObject("Q42", "Douglas Adams");

        FieldSet fields = FieldSet.of(entity);
        FieldRef source = fields.field("wikidataSource");

        assertEquals(objectview.field.FieldRole.PROVENANCE, source.role());
        assertEquals("Wikidata source", source.label());
        assertEquals(FieldKind.COLLECTION, source.kind());
        domain.provenance.WikidataSource value = (domain.provenance.WikidataSource)
                assertInstanceOf(List.class, fields.read("wikidataSource")).getFirst();
        assertEquals("Q42", value.getDisplayName());
        assertTrue(FieldSet.of(value).field("identity").link());
        assertEquals(FieldRole.DISPLAY,
                FieldSet.of(value).field("identity").role());
    }

    @Test void aStatementRecordPublishesItsEvidenceSeparatelyFromEntitySources() {
        WikidataStatementSource statement = new WikidataStatementSource(
                "Q28$a", "Q28", "P1411", "Q103916", "Best Actor");
        var record = new domain.runtime.WikidataDynamicObject(
                "Q28$a", "modeled record");
        record.addWikidataStatementSource(statement);

        FieldSet fields = FieldSet.of(record);
        FieldRef source = fields.field("wikidataSource");
        FieldRef statements = fields.field("wikidataStatements");

        assertEquals(FieldKind.COLLECTION, source.kind());
        assertEquals(List.of(), fields.read("wikidataSource"));
        assertEquals(FieldKind.COLLECTION, statements.kind());
        assertTrue(statements.annotatedReference());
        assertEquals(List.of(statement), fields.read("wikidataStatements"));
        FieldSet statementFields = FieldSet.of(statement);
        objectview.field.LinkedTextSequence caption = assertInstanceOf(
                objectview.field.LinkedTextSequence.class,
                statementFields.read("source"));
        assertEquals(statement.getDisplayName(), caption.text(),
                "the linked caption is the one statement display-name derivation");
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
                        objectview.viewconfig.ViewConfig.of(
                                WikidataStatementSource.class), false));

        assertEquals(5, count(card[0], objectview.render.LinkRow.class),
                "subject, property, object, GUID and exact JSON");
    }

    @Test void anAggregatedRecordKeepsEverySourceStatementInTheEvidenceField() {
        var record = new domain.runtime.WikidataDynamicObject(
                "modeled-key", "modeled record");
        record.wikidataStatementSources(List.of(
                new WikidataStatementSource("Q1$a", "Q1", "P166", "Q2", "Prize"),
                new WikidataStatementSource("Q3$b", "Q3", "P166", "Q2", "Prize")));

        FieldSet fields = FieldSet.of(record);
        FieldRef source = fields.field("wikidataStatements");

        assertEquals(FieldKind.COLLECTION, source.kind());
        assertTrue(source.collection());
        assertEquals(2, assertInstanceOf(
                List.class, fields.read("wikidataStatements")).size());
    }

    @Test void aQidEntityWithRouteEvidenceNeverLosesItsEntitySource() {
        var laocoon = new domain.runtime.WikidataDynamicObject(
                "Q22878", "Laocoön");
        var evidence = new WikidataStatementSource(
                "Q22878$child", "Q22878", "P40", "Q12748409",
                "Antiphantes");
        laocoon.addWikidataStatementSource(evidence);

        FieldSet fields = FieldSet.of(laocoon);

        assertEquals(List.of(new WikidataSource("Q22878")),
                fields.read("wikidataSource"));
        assertEquals(List.of(evidence), fields.read("wikidataStatements"));
    }

    @Test void theDefaultStatementCaptionLinksSubjectPropertyAndObjectIndependently()
            throws Exception {
        var statement = new WikidataStatementSource(
                "Q22878$child", "Q22878", "P40", "Q12748409",
                "Antiphantes");
        var entity = new domain.runtime.WikidataDynamicObject(
                "Q22878", "Laocoön");
        entity.addWikidataStatementSource(statement);
        objectview.viewconfig.ViewConfig statementView =
                objectview.viewconfig.ViewConfig.leaf();
        statementView.addField(objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig entityView =
                objectview.viewconfig.ViewConfig.leaf();
        entityView.addField("wikidataStatements", statementView);
        Object statements = FieldSet.of(entity).read("wikidataStatements");
        objectview.render.RenderContext context = new objectview.render.RenderContext();
        context.setCollectionExpanded(statements, true);
        objectview.render.Card[] rendered = new objectview.render.Card[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] =
                new objectview.render.Card(entity, entityView, context, false));

        objectview.render.TextRow caption = findLinkedText(rendered[0], 3);
        assertNotNull(caption);
        assertEquals(List.of(
                "https://www.wikidata.org/wiki/Q22878",
                "https://www.wikidata.org/wiki/Property:P40",
                "https://www.wikidata.org/wiki/Q12748409"), caption.linkedUrls());
    }

    @Test void theDefaultSourceCaptionKeepsItsWikidataLink() throws Exception {
        var entity = new domain.runtime.WikidataDynamicObject(
                "Q42", "Douglas Adams");
        Object sources = FieldSet.of(entity).read("wikidataSource");
        objectview.render.RenderContext context = new objectview.render.RenderContext();
        context.setValueLinker(wikidata.ui.WikidataLinks.valueLinker());
        context.setCollectionExpanded(sources, true);
        objectview.render.Card[] rendered = new objectview.render.Card[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] =
                new objectview.render.Card(entity, null, context, false));

        objectview.render.LinkRow link = find(
                rendered[0], objectview.render.LinkRow.class);
        assertNotNull(link);
        assertEquals("https://www.wikidata.org/wiki/Q42", link.url());
    }

    @Test void aWikipediaSourceUsesItsArticleAsALinkedDisplayField() throws Exception {
        WikipediaSource source = new WikipediaSource("Douglas Adams");
        FieldSet fields = FieldSet.of(source);

        assertEquals("article", fields.displayField().name());
        assertEquals(FieldRole.DISPLAY, fields.field("article").role());
        assertTrue(fields.field("article").link());

        ArticleOwner owner = new ArticleOwner(source);
        objectview.viewconfig.ViewConfig article =
                objectview.viewconfig.ViewConfig.of(WikipediaSource.class);
        article.setAllFields(false);
        article.addField("article", objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig ownerView =
                objectview.viewconfig.ViewConfig.of(ArticleOwner.class);
        ownerView.setAllFields(false);
        ownerView.addField("wikipediaSource", article);

        objectview.render.Card[] rendered = new objectview.render.Card[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] =
                new objectview.render.Card(owner, ownerView, false));

        objectview.render.LinkRow link = find(
                rendered[0], objectview.render.LinkRow.class);
        assertNotNull(link);
        assertEquals("https://en.wikipedia.org/wiki/Douglas_Adams", link.url());
    }

    @Test void automaticReferenceDisplayKeepsTheWikipediaArticleLink() throws Exception {
        WikipediaSource source = new WikipediaSource("Douglas Adams");
        ArticleOwner owner = new ArticleOwner(source);
        objectview.viewconfig.ViewConfig display = objectview.viewconfig.ViewConfig.leaf();
        display.addField(objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig ownerView = objectview.viewconfig.ViewConfig.leaf();
        ownerView.addField("wikipediaSource", display);

        objectview.render.Card[] rendered = new objectview.render.Card[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] =
                new objectview.render.Card(owner, ownerView, false));

        objectview.render.LinkRow link = find(
                rendered[0], objectview.render.LinkRow.class);
        assertNotNull(link, "the ordinary DISPLAY projection must retain its link metadata");
        assertEquals("https://en.wikipedia.org/wiki/Douglas_Adams", link.url());
    }

    /** Retaining article structure gives the source an expandable body. The linked
     * display remains a link; disclosure belongs to the separate triangle. */
    @Test void anExpandableWikipediaSourceKeepsItsLinkedCaption() throws Exception {
        var document = wikipedia.WikipediaArticleClient.sourceDocument(
                "Douglas Adams", "17", "Douglas Adams wrote novels.", List.of());
        var article = new domain.datasource.evidence.WikipediaArticle(document,
                "Douglas Adams wrote novels.", List.of(), List.of());
        WikipediaSource source = new WikipediaSource("Douglas Adams", article);
        ArticleOwner owner = new ArticleOwner(source);

        objectview.viewconfig.ViewConfig retained =
                objectview.viewconfig.ViewConfig.of(
                        domain.datasource.evidence.WikipediaArticle.class);
        retained.setAllFields(false);
        retained.addField("revision", objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig sourceView =
                objectview.viewconfig.ViewConfig.of(WikipediaSource.class);
        sourceView.setAllFields(false);
        sourceView.addField("article", objectview.viewconfig.ViewConfig.leaf());
        sourceView.addField("retainedArticle", retained);
        objectview.viewconfig.ViewConfig ownerView =
                objectview.viewconfig.ViewConfig.of(ArticleOwner.class);
        ownerView.setAllFields(false);
        ownerView.addField("wikipediaSource", sourceView);

        objectview.render.Card[] rendered = new objectview.render.Card[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] =
                new objectview.render.Card(owner, ownerView, false));

        objectview.render.ReferenceRow reference = find(
                rendered[0], objectview.render.ReferenceRow.class);
        assertNotNull(reference, "the retained article remains expandable");
        assertEquals("https://en.wikipedia.org/wiki/Douglas_Adams", reference.url(),
                "the configured linked DISPLAY remains the source caption's destination");
    }

    private static final class ArticleOwner extends objectview.ViewableAdapter {
        @objectview.annotations.DisplayField
        private final String name = "Book";
        // Saved datasource schemas deliberately declare this polymorphic reference as
        // Viewable. Rendering must resolve its DISPLAY metadata from the actual target.
        private final objectview.Viewable wikipediaSource;

        private ArticleOwner(WikipediaSource wikipediaSource) {
            this.wikipediaSource = wikipediaSource;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    private static <T> T find(java.awt.Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static objectview.render.LinkRow findLink(
            java.awt.Component root, String url) {
        if (root instanceof objectview.render.LinkRow link
                && url.equals(link.url())) return link;
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                objectview.render.LinkRow found = findLink(child, url);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static objectview.render.TextRow findLinkedText(
            java.awt.Component root, int links) {
        if (root instanceof objectview.render.TextRow row
                && row.linkedUrls().size() == links) return row;
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                objectview.render.TextRow found = findLinkedText(child, links);
                if (found != null) return found;
            }
        }
        return null;
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
