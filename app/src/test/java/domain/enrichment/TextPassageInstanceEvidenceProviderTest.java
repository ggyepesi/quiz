package domain.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.runtime.WikidataDynamicObject;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stories from an authored Wikisource page: one proposed instance per numbered chapter, its
 * text and source work, and the loaded characters it names uniquely. A name two loaded
 * characters share (Vili) is a review warning, never a reference; no passage is bulk-safe.
 */
class TextPassageInstanceEvidenceProviderTest {

    private static final String GYLFAGINNING =
            "The Prose Edda (1916 translation by Arthur Gilchrist Brodeur)/Gylfaginning";
    private static final String HTML = """
            <p>Gylfi was a king in Sweden.</p>
            <p><b>VI.</b> Burr had three sons: the first was Odin, the second Vili, the third Vé.</p>
            <p><b>XV.</b> Thither came Allfather and craved one drink of the well; he laid his eye in pledge.</p>
            <p><b>L.</b> Now Loki was taken truceless.</p>
            """;

    private static WikidataDynamicObject character(String qid, String label, String... aliases) {
        var character = new WikidataDynamicObject(qid, label);
        character.type("NorseCharacter");
        character.aliases(List.of(aliases));
        return character;
    }

    private final WikidataDynamicObject odin = character("Q43610", "Odin", "Allfather");
    private final WikidataDynamicObject loki = character("Q133147", "Loki");
    private final WikidataDynamicObject brother = character("Q1153419", "Vili");
    private final WikidataDynamicObject son = character("Q3554720", "Váli", "Vili");

    private static WikipediaDatasourceProvider.TextPassageInstanceSpec spec() {
        return WikipediaDatasourceProvider.textPassageInstances(new SourceBinding(
                SourceBindingTarget.fieldValue("NorseLiteraryWork", "stories",
                        SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.TEXT_PASSAGE_INSTANCES, Map.of(
                                WikipediaDatasourceProvider.TEXT_SITE, "WIKISOURCE",
                                WikipediaDatasourceProvider.TEXT_PAGES, "Q205882 = " + GYLFAGINNING,
                                WikipediaDatasourceProvider.TEXT_DIVISION, "NUMBERED_CHAPTERS",
                                WikipediaDatasourceProvider.PASSAGE_TEXT_FIELD, "text",
                                WikipediaDatasourceProvider.PASSAGE_SOURCE_FIELD, "work",
                                WikipediaDatasourceProvider.PASSAGE_TITLE_FIELD, "title",
                                WikipediaDatasourceProvider.PASSAGE_REFERENCES_FIELD, "characters"))));
    }

    private static EnrichmentRequest request() {
        FieldRef schema = FieldRef.described("stories", FieldKind.COLLECTION, FieldKind.REFERENCE,
                "List<NorseStory>", true, true, "NorseStory", false, false, false, false, "", false);
        return new EnrichmentRequest(new EnrichmentProposal.Subject("NorseLiteraryWork", "Q205882",
                "Q205882", "Prose Edda"), "stories", true, List.of(), schema, List.of(), List.of(),
                null, null, null);
    }

    @Test void eachChapterOfTheAuthoredPageIsOneProposedStoryWithTheCharactersItNames() throws Exception {
        List<URI> requests = new ArrayList<>();
        String response = new ObjectMapper().writeValueAsString(Map.of("parse", Map.of(
                "title", GYLFAGINNING, "revid", 7, "text", HTML, "categories", List.of())));
        var provider = new TextPassageInstanceEvidenceProvider(spec(), List.of(odin, loki, brother, son),
                "NorseCharacter", domain(), new wikipedia.WikipediaArticleClient(uri -> {
                    requests.add(uri);
                    return response;
                }));

        var proposal = provider.discover(request()).execute(new QueryContext());

        assertEquals("en.wikisource.org", requests.getFirst().getHost());
        var stories = proposal.instances();
        assertEquals(List.of("Gylfaginning VI — Burr had three sons: the first was Odin, the second Vili, the third Vé.",
                        "Gylfaginning XV — Thither came Allfather and craved one drink of the well; he laid his eye in pledge.",
                        "Gylfaginning L — Now Loki was taken truceless."),
                stories.stream().map(EnrichmentProposal.InstanceCandidate::displayName).toList());
        assertTrue(stories.stream().noneMatch(EnrichmentProposal.InstanceCandidate::bulkSafe),
                "each story is its own review decision");

        assertEquals(List.of("Q43610"), named(stories.get(1)), "Allfather is Odin");
        assertEquals(List.of("Q133147"), named(stories.get(2)));
        assertEquals(List.of("Q43610"), named(stories.getFirst()), "the shared name Vili is not guessed");
        assertTrue(stories.getFirst().evidence().getFirst().warnings().stream()
                .anyMatch(warning -> warning.startsWith("\"Vili\" names 2 loaded instances")));
        assertEquals(3, proposal.fields().size(), "each story is offered for the work's stories field");
        assertFalse(stories.getFirst().source().recordUrl().isBlank());
    }

    @Test void aWorkWithoutAuthoredPagesReadsNothing() throws Exception {
        var provider = new TextPassageInstanceEvidenceProvider(spec(), List.of(), "NorseCharacter", domain(),
                new wikipedia.WikipediaArticleClient(uri -> {
                    throw new AssertionError("no page is authored for this work");
                }));
        var other = new EnrichmentRequest(new EnrichmentProposal.Subject("NorseLiteraryWork", "Q205874",
                "Q205874", "Poetic Edda"), "stories", true, List.of(), request().targetSchema(), List.of(),
                List.of(), null, null, null);

        assertTrue(provider.discover(other).execute(new QueryContext()).instances().isEmpty());
    }

    private static List<String> named(EnrichmentProposal.InstanceCandidate story) {
        return story.fields().stream().filter(field -> field.field().equals("characters"))
                .flatMap(field -> field.references().stream())
                .map(EnrichmentProposal.TypedReference::id).toList();
    }

    private static domain.DomainModel domain() {
        return new domain.DomainModel() {
            @Override public List<String> types() { return List.of("NorseStory", "NorseCharacter", "NorseLiteraryWork"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) { return null; }
            @Override public java.util.Collection<? extends objectview.Viewable> instances() { return List.of(); }
            @Override public Class<? extends objectview.Viewable> universe() { return WikidataDynamicObject.class; }
        };
    }
}
