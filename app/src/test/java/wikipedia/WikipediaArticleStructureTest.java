package wikipedia;

import datasource.evidence.WikipediaArticle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #464: blocks and sections were placed by searching the article text for each element's
 * own text, which the parser had built differently. On the live Maat article 94 list items,
 * 3 quotations, 2 paragraphs and the infobox were silently dropped. Positions now come from
 * the walk that writes the text, so a block is exactly its slice of that text.
 */
class WikipediaArticleStructureTest {

    private static final String HTML = """
            <p>Maat governed order. Worship of Maat spread widely.</p>
            <table class="infobox"><tr><th>Symbol</th><td>Ostrich feather</td></tr>
              <tr><th>Consort</th><td>Thoth</td></tr></table>
            <ul><li><p>Her feather weighed the heart.</p><p>A second paragraph.</p></li></ul>
            <blockquote class="templatequote"><p>I have done Maat.</p></blockquote>
            <h2>Worship</h2>
            <p>Her cult was widespread.</p>
            """;

    private static List<WikipediaArticle.Block> blocks(
            WikipediaArticleClient.ParsedArticleText parsed) {
        List<WikipediaArticle.Block> result = new ArrayList<>();
        collect(parsed.structuredSections(), result);
        return result;
    }

    private static void collect(List<WikipediaArticle.Section> sections,
            List<WikipediaArticle.Block> into) {
        for (WikipediaArticle.Section section : sections) {
            into.addAll(section.blocks());
            collect(section.sections(), into);
        }
    }

    @Test void nestedBlocksAndTablesBecomeBlocksInsteadOfVanishing() {
        var parsed = WikipediaArticleClient.parseArticleText(HTML);
        List<WikipediaArticle.Block> blocks = blocks(parsed);

        assertEquals(List.of(WikipediaArticle.Block.Kind.PARAGRAPH,
                        WikipediaArticle.Block.Kind.TABLE,
                        WikipediaArticle.Block.Kind.LIST_ITEM,
                        WikipediaArticle.Block.Kind.QUOTATION,
                        WikipediaArticle.Block.Kind.PARAGRAPH),
                blocks.stream().map(WikipediaArticle.Block::kind).toList());
        assertEquals("Symbol Ostrich feather\nConsort Thoth", blocks.get(1).value(),
                "a row is a line and its cells are separated");
        assertEquals("Her feather weighed the heart.\nA second paragraph.",
                blocks.get(2).value());
        java.util.Set<String> outcomes = new java.util.HashSet<>(List.of(
                "Section heading", "Inside a retained block", "Empty", "Not retained"));
        for (WikipediaArticle.Block.Kind kind : WikipediaArticle.Block.Kind.values()) {
            outcomes.add(kind.name());
        }
        assertTrue(parsed.elements().stream().allMatch(kind -> outcomes.contains(kind.retainedAs())),
                "an element is a block, a heading, part of a block, empty or not read — "
                        + "never read and then lost: " + parsed.elements());
    }

    @Test void everyBlockIsExactlyItsSliceOfTheArticleText() {
        var parsed = WikipediaArticleClient.parseArticleText(HTML);

        for (WikipediaArticle.Block block : blocks(parsed)) {
            assertEquals(block.value(), parsed.text().substring(block.start(), block.end()),
                    block.getDisplayName());
        }
    }

    /** "Worship" appears in the first paragraph; the Worship section still starts at its
     *  heading, where searching the text for the heading's words found that paragraph. */
    @Test void aSectionStartsAtItsHeadingNotAtAnEarlierMentionOfItsWords() {
        var parsed = WikipediaArticleClient.parseArticleText(HTML);
        int heading = parsed.text().indexOf("\nWorship\n") + 1;

        WikipediaArticle.Section worship = parsed.structuredSections().stream()
                .filter(section -> section.heading().equals("Worship")).findFirst().orElseThrow();
        assertEquals(heading, worship.start());
        assertEquals(heading, parsed.sections().stream()
                .filter(section -> section.title().equals("Worship"))
                .findFirst().orElseThrow().start());
    }

    private static final String KINDS = """
            <dl><dd>Indented commentary on Maat.</dd></dl>
            <figure><figcaption>Maat wearing an ostrich feather.</figcaption></figure>
            <table class="infobox vcard"><tr><th>Symbol</th><td>Ostrich feather</td></tr></table>
            <h2>References</h2>
            <ol class="references"><li>Wilkinson 2003, p. 150.</li></ol>
            """;

    /** Definition entries and captions were content the structure never read; each block
     *  also records its source element, so an infobox or a footnote is told apart by what
     *  the page states rather than by a meaning assigned in code. */
    @Test void definitionsAndCaptionsAreBlocksAndEveryBlockNamesItsSourceElement() {
        List<WikipediaArticle.Block> blocks =
                blocks(WikipediaArticleClient.parseArticleText(KINDS));

        assertEquals(List.of("DEFINITION dl > dd", "CAPTION figcaption",
                        "TABLE table.infobox.vcard", "LIST_ITEM ol.references > li"),
                blocks.stream().map(block -> block.kind() + " " + block.element()).toList());
        assertEquals("Indented commentary on Maat.", blocks.get(0).value());
    }

    /** The source element is part of the retained article; a snapshot written before it
     *  was recorded still loads, with an empty element. */
    @Test void theSourceElementSurvivesASnapshotAndOlderSnapshotsStillLoad(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var parsed = WikipediaArticleClient.parseArticleText(KINDS);
        var document = WikipediaArticleClient.sourceDocument("Maat", "1", parsed.text(),
                List.of(), parsed.links());
        var maat = new wikidata.explore.extract.WikidataDynamicObject("Q170471", "Maat");
        maat.type("EgyptianDeity");
        maat.wikipediaArticleEvidence(new WikipediaArticle(document, parsed.text(), List.of(),
                parsed.structuredSections()), true);
        var store = new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        var file = dir.resolve("kinds-snapshot.json").toFile();
        store.save(List.of(maat), file);

        var loaded = store.load(file).getFirst().wikipediaArticle();
        List<WikipediaArticle.Block> blocks = new ArrayList<>();
        collect(loaded.sections(), blocks);
        assertEquals("table.infobox.vcard", blocks.get(2).element());

        String older = java.nio.file.Files.readString(file.toPath())
                .replaceAll("\\s*\"element\"\\s*:\\s*\"[^\"]*\",?", "");
        java.nio.file.Files.writeString(file.toPath(), older);
        List<WikipediaArticle.Block> olderBlocks = new ArrayList<>();
        collect(store.load(file).getFirst().wikipediaArticle().sections(), olderBlocks);
        assertEquals("", olderBlocks.get(2).element());
        assertEquals(WikipediaArticle.Block.Kind.TABLE, olderBlocks.get(2).kind());
    }
}
