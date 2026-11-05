package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityAliasSnapshotTest {

    @Test void aliasesSurviveTheFlattenedSnapshotBoundary(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "One");
        person.type("Person");
        person.aliases(List.of("First alias", "Second alias"));

        var file = dir.resolve("snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(person), file);

        assertEquals(List.of("First alias", "Second alias"),
                store.load(file).getFirst().aliases());
    }

    @Test void categoryEvidenceSurvivesWithoutBecomingADomainField(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject movie = new WikidataDynamicObject("Q157058", "Blood Diamond");
        movie.type("Movie");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Blood Diamond", "Blood Diamond",
                "https://en.wikipedia.org/wiki/Blood_Diamond", "987654",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-08-21T12:00:00Z");
        movie.categoryMemberships(List.of(
                new datasource.evidence.CategoryMembership(
                        "Films set in Sierra Leone", document),
                new datasource.evidence.CategoryMembership(
                        "Films set in 1999", document)));

        var file = dir.resolve("snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(movie), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        assertEquals("Films set in Sierra Leone",
                loaded.categoryMemberships().getFirst().category());
        org.junit.jupiter.api.Assertions.assertTrue(loaded.categoryMemberships().getFirst()
                .document().versionId().startsWith("revision:987654;"));
        org.junit.jupiter.api.Assertions.assertEquals(null, loaded.get("categoryMemberships"),
                "source facts stay outside the rendered field graph");
        String json = Files.readString(file.toPath());
        assertEquals(1, occurrences(json, "\"wikipediaCategoryDocument\""),
                "one page document is shared by all of its category memberships");
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"categoryMemberships\":"),
                "the snapshot must not fall back to the document-per-category shape");
    }

    @Test void infoboxEvidenceSurvivesWithoutBecomingADomainField(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject movie = new WikidataDynamicObject("Q157058", "Blood Diamond");
        movie.type("Movie");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Blood Diamond", "Blood Diamond",
                "https://en.wikipedia.org/wiki/Blood_Diamond", "987654",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-08-21T12:00:00Z");
        movie.infoboxParameters(new datasource.evidence.InfoboxParameters(
                "Infobox film", java.util.Map.of("country", "Sierra Leone"),
                java.util.Map.of("Group", "group 8"), document));

        var file = dir.resolve("infobox-snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(movie), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        assertEquals("Sierra Leone", loaded.infoboxParameters().value("country"));
        assertEquals("group 8", loaded.infoboxParameters().renderedFields().get("Group"));
        org.junit.jupiter.api.Assertions.assertTrue(loaded.renderedInfoboxAnswered());
        assertEquals("987654", loaded.infoboxParameters().document().revision());
        assertEquals(null, loaded.get("infoboxParameters"),
                "source facts stay outside the rendered field graph");
    }

    @Test void answeredWithoutInfoboxSurvivesTheSnapshotBoundary(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject movie = new WikidataDynamicObject("Q1", "Film");
        movie.type("Movie");
        movie.infoboxParameters(null);

        var file = dir.resolve("no-infobox-snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(movie), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        org.junit.jupiter.api.Assertions.assertTrue(loaded.infoboxAnswered());
        assertEquals(null, loaded.infoboxParameters());
    }

    @Test void articleIntroductionEvidenceSurvivesWithoutBecomingADomainField(
            @TempDir Path dir) throws Exception {
        WikidataDynamicObject episode =
                new WikidataDynamicObject("Q1784308", "Judgement of Paris");
        episode.type("Episode");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Judgement of Paris", "Judgement of Paris",
                "https://en.wikipedia.org/wiki/Judgement_of_Paris", "987654",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-11-03T12:00:00Z");
        var link = new datasource.evidence.ArticleIntroduction.Link(
                "Paris", "Paris (mythology)", 0, 5);
        episode.articleIntroductionEvidence(
                new datasource.evidence.ArticleIntroduction(
                        "Paris judges three goddesses.", document,
                        java.util.List.of(link)), true);

        var file = dir.resolve("article-snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(episode), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        assertEquals("Paris judges three goddesses.",
                loaded.articleIntroduction().text());
        assertEquals("987654",
                loaded.articleIntroduction().document().revision());
        assertEquals(java.util.List.of(link), loaded.articleIntroduction().links());
        org.junit.jupiter.api.Assertions.assertTrue(loaded.articleIntroductionAnswered());
        assertEquals(null, loaded.get("articleIntroduction"),
                "source facts stay outside the rendered field graph");
    }

    @Test void structuredWikipediaArticleSurvivesAsOneSourceArtifact(
            @TempDir Path dir) throws Exception {
        WikidataDynamicObject deity = new WikidataDynamicObject("Q170471", "Maat");
        deity.type("EgyptianDeity");
        deity.enwikiTitle("Maat");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Maat", "Maat",
                "https://en.wikipedia.org/wiki/Maat", "123",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-11-05T12:00:00Z");
        var paragraph = new datasource.evidence.WikipediaArticle.Block(
                "Paragraph 1", datasource.evidence.WikipediaArticle.Block.Kind.PARAGRAPH,
                "Maat represented truth and order.", java.util.List.of(), 0, 33);
        var section = new datasource.evidence.WikipediaArticle.Section(
                "Introduction", 0, java.util.List.of(paragraph), java.util.List.of(),
                0, 33);
        deity.wikipediaArticleEvidence(new datasource.evidence.WikipediaArticle(
                document, "Maat represented truth and order.",
                java.util.List.of("Egyptian goddesses"), java.util.List.of(section)), true);

        var file = dir.resolve("structured-article-snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(java.util.List.of(deity), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();

        assertEquals("Maat", loaded.wikipediaArticle().getDisplayName());
        assertEquals("Introduction",
                loaded.wikipediaArticle().sections().getFirst().heading());
        org.junit.jupiter.api.Assertions.assertTrue(loaded.wikipediaArticleAnswered());
        String json = Files.readString(file.toPath());
        assertEquals(1, occurrences(json, "\"wikipediaArticle\""));
        assertEquals(null, loaded.articleIntroduction(),
                "a fetched structured article is not a second source of the introduction");
        org.junit.jupiter.api.Assertions.assertFalse(loaded.articleIntroductionAnswered());
    }

    /** #448: one revision had two introductions — the batched lead and one derived from a
     * fetched structured article — whose text and offsets differed, so mentions and claims
     * depended on which route ran. The batched lead is the one introduction: retaining a
     * structured article afterwards neither replaces it nor drops it on save. */
    @Test void aFetchedArticleKeepsTheOneRetainedIntroduction(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject deity = new WikidataDynamicObject("Q170471", "Maat");
        deity.type("EgyptianDeity");
        var document = new datasource.evidence.SourceDocument(
                "Wikipedia (English)", "Maat", "Maat",
                "https://en.wikipedia.org/wiki/Maat", "123",
                new datasource.evidence.ContentDigest("sha256", "abc"),
                "2026-11-05T12:00:00Z");
        var lead = new datasource.evidence.ArticleIntroduction(
                "Maat was the goddess of truth.", document);
        deity.articleIntroductionEvidence(lead, true);
        var paragraph = new datasource.evidence.WikipediaArticle.Block(
                "Paragraph 1", datasource.evidence.WikipediaArticle.Block.Kind.PARAGRAPH,
                "Maat represented truth and order.", java.util.List.of(), 0, 33);
        deity.wikipediaArticleEvidence(new datasource.evidence.WikipediaArticle(
                document, "Maat represented truth and order.", java.util.List.of(),
                java.util.List.of(new datasource.evidence.WikipediaArticle.Section(
                        "Introduction", 0, java.util.List.of(paragraph),
                        java.util.List.of(), 0, 33))), true);

        assertSame(lead, deity.articleIntroduction());
        var file = dir.resolve("both-artifacts-snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(java.util.List.of(deity), file);
        WikidataDynamicObject loaded = store.load(file).getFirst();
        assertEquals("Maat was the goddess of truth.", loaded.articleIntroduction().text());
        assertEquals("Introduction",
                loaded.wikipediaArticle().sections().getFirst().heading());
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
