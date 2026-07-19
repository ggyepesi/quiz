package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.model.Selection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice 2: a Selection's content is inspectable — a VOCABULARY resolves to its
 * members as labelled objects, in declared order, ready to render.
 */
class SelectionContentResolverTest {

    @Test void vocabularyResolvesToLabelledMembersInOrder() {
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q102427", "Academy Award for Best Picture")
                .entity("Q106301", "Academy Award for Best Supporting Actress");

        Selection cats = new Selection("OscarCategories", Selection.Kind.VOCABULARY);
        cats.valueQids(List.of("Q102427", "Q106301"));

        List<WikidataDynamicObject> content =
                new SelectionContentResolver().resolve(cats, api, null);

        assertEquals(2, content.size());
        assertEquals("Q102427", content.get(0).qid());
        assertEquals("Academy Award for Best Picture", content.get(0).getDisplayName());
        assertEquals("Academy Award for Best Supporting Actress",
                content.get(1).getDisplayName());
    }

    @Test void aMissingLabelFallsBackToTheQid() {
        FakeWikidataApiClient api = new FakeWikidataApiClient();   // knows nothing
        Selection s = new Selection("V", Selection.Kind.VOCABULARY);
        s.valueQids(List.of("Q999"));

        List<WikidataDynamicObject> content =
                new SelectionContentResolver().resolve(s, api, null);

        assertEquals(1, content.size());
        assertEquals("Q999", content.get(0).getDisplayName());
    }

    @Test void aTypeOnlyVocabularyResolvesToNothingYet() {
        Selection s = new Selection("ByType", Selection.Kind.VOCABULARY);
        s.valueTypeQid("Q19020");   // no explicit QIDs
        assertTrue(new SelectionContentResolver()
                .resolve(s, new FakeWikidataApiClient(), null).isEmpty());
    }
}
