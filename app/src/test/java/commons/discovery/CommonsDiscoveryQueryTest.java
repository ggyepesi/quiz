package commons.discovery;

import org.junit.jupiter.api.Test;
import quiz.source.WikidataSource;
import wikidata.WikidataBinding;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonsDiscoveryQueryTest {

    @Test void queryContainsOneCappedExactDepictsBranchPerSelectedQid() {
        CommonsDiscoveryQuery query = query(List.of(
                entity("Q42", "Douglas Adams"), entity("Q146", "cat")),
                ignored -> List.of());

        String sparql = query.sparqlForTest(List.of("Q42", "Q146"));

        assertTrue(sparql.contains("?file wdt:P180 wd:Q42"), sparql);
        assertTrue(sparql.contains("?file wdt:P180 wd:Q146"), sparql);
        assertEquals(2, occurrences(sparql, "LIMIT 7"), sparql);
        assertTrue(sparql.contains("UNION"), sparql);
        assertFalse(sparql.contains("^wdt:P180"),
                "the first workflow follows no incoming or transitive relation");
    }

    @Test void oneFileDepictingTwoSelectedEntitiesStaysOneConnectedMediaObject()
            throws Exception {
        WikidataDynamicObject douglas = entity("Q42", "Douglas Adams");
        WikidataDynamicObject cat = entity("Q146", "cat");
        String file = "https://commons.wikimedia.org/entity/M100";
        String image = "http://commons.wikimedia.org/wiki/Special:FilePath/Example.jpg";
        List<WikidataBinding> rows = List.of(
                row("Q42", file, image, "Example portrait", "Q5",
                        "2008-10-15T00:00:00Z", "Q296955", "Q123", "Q456"),
                row("Q146", file, image, "Example portrait", "Q5",
                        "2008-10-15T00:00:00Z", "Q296955", "Q123", "Q456"));
        CommonsDiscoveryQuery query = new CommonsDiscoveryQuery(
                List.of(douglas, cat), 7, ignored -> rows,
                (context, qids) -> Map.of(
                        "Q5", "human", "Q296955", "oil paint",
                        "Q123", "Example collection", "Q456", "Example museum"));

        CommonsDiscoveryQuery.Result result = query.execute(new QueryContext());

        assertEquals(1, result.media().size());
        MediaRepresentation media = result.media().getFirst();
        assertEquals("M100", media.mediaId());
        assertEquals(List.of(douglas, cat), media.depictedEntities());
        assertEquals(List.of("2008-10-15"), media.creationDates());
        assertEquals(List.of("human"),
                media.creators().stream().map(WikidataSource::getDisplayName).toList());
        assertEquals("Example.jpg|https://commons.wikimedia.org/wiki/File:Example.jpg",
                media.commonsSource());
        assertTrue(result.withoutMedia().isEmpty());
    }

    /** A QID identifies the source request, not the modeled instance. Two logical
     *  instances may deliberately correspond to the same source entity and both must
     *  remain connected to the returned media object. */
    @Test void oneQidRequestKeepsEverySelectedLogicalInstance() throws Exception {
        WikidataDynamicObject person = entity("Q42", "Douglas Adams", "Person");
        WikidataDynamicObject author = entity("Q42", "Douglas Adams", "Author");
        List<String> requests = new ArrayList<>();
        CommonsDiscoveryQuery query = new CommonsDiscoveryQuery(
                List.of(person, author), 7, sparql -> {
                    requests.add(sparql);
                    return List.of(row("Q42", "https://commons.wikimedia.org/entity/M1",
                            "https://commons.wikimedia.org/wiki/Special:FilePath/Face.jpg",
                            "Face", "", "", "", "", ""));
                }, (context, qids) -> Map.of());

        CommonsDiscoveryQuery.Result result = query.execute(new QueryContext());

        assertEquals(1, requests.size());
        assertEquals(1, occurrences(requests.getFirst(), "?file wdt:P180 wd:Q42"),
                requests.getFirst());
        assertEquals("2", query.parameters().get("selectedInstances"));
        assertEquals("1", query.parameters().get("selectedQids"));
        assertEquals("7", query.parameters().get("maximumFilesBeforeDeduplication"));
        assertEquals(List.of(person, author), result.media().getFirst().depictedEntities());
        assertTrue(result.withoutMedia().isEmpty());
    }

    /** Audio, video and documents carry depicts statements too; shown as a depiction
     *  they would be a broken image, so they are counted and named instead. */
    @Test void aNonImageFileIsNotShownAsADepictionButIsCounted() throws Exception {
        WikidataDynamicObject selected = entity("Q42", "Douglas Adams");
        CommonsDiscoveryQuery query = new CommonsDiscoveryQuery(
                List.of(selected), 7,
                ignored -> List.of(
                        row("Q42", "https://commons.wikimedia.org/entity/M1",
                                "https://commons.wikimedia.org/wiki/Special:FilePath/Talk.ogg",
                                "Talk", "", "", "", "", ""),
                        row("Q42", "https://commons.wikimedia.org/entity/M2",
                                "https://commons.wikimedia.org/wiki/Special:FilePath/Face.jpg",
                                "Face", "", "", "", "", "")),
                (context, qids) -> Map.of());

        CommonsDiscoveryQuery.Result result = query.execute(new QueryContext());

        assertEquals(List.of("M2"),
                result.media().stream().map(MediaRepresentation::mediaId).toList());
        assertEquals(1, result.nonImageFiles());
        assertTrue(query.summary(result).contains("1 non-image file(s)"),
                query.summary(result));
    }

    @Test void unselectedDepictsRowsCannotInventAResultSubject() throws Exception {
        WikidataDynamicObject selected = entity("Q42", "Douglas Adams");
        CommonsDiscoveryQuery query = new CommonsDiscoveryQuery(
                List.of(selected), 7,
                ignored -> List.of(row("Q999", "https://commons.wikimedia.org/entity/M1",
                        "https://commons.wikimedia.org/wiki/Special:FilePath/X.jpg",
                        "X", "", "", "", "", "")),
                (context, qids) -> Map.of());

        CommonsDiscoveryQuery.Result result = query.execute(new QueryContext());

        assertTrue(result.media().isEmpty());
        assertEquals(List.of(selected), result.withoutMedia());
    }

    @Test void selectionMustBeBoundedAndEverySubjectMustHaveAnExactQid() {
        List<WikidataDynamicObject> tooMany = new ArrayList<>();
        for (int i = 1; i <= CommonsDiscoveryQuery.MAX_SUBJECTS + 1; i++) {
            tooMany.add(entity("Q" + i, "Entity " + i));
        }
        assertThrows(IllegalArgumentException.class,
                () -> query(tooMany, ignored -> List.of()));

        WikidataDynamicObject missing = new WikidataDynamicObject("not-a-qid", "Missing");
        missing.type("Person");
        assertThrows(IllegalArgumentException.class,
                () -> query(List.of(missing), ignored -> List.of()));
    }

    private static CommonsDiscoveryQuery query(
            List<WikidataDynamicObject> subjects,
            CommonsDiscoveryQuery.SparqlSource source) {
        return new CommonsDiscoveryQuery(subjects, 7, source,
                (context, qids) -> Map.of());
    }

    private static WikidataDynamicObject entity(String qid, String name) {
        return entity(qid, name, "Person");
    }

    private static WikidataDynamicObject entity(String qid, String name, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, name);
        value.type(type);
        return value;
    }

    private static WikidataBinding row(
            String depicted,
            String file,
            String image,
            String caption,
            String creator,
            String date,
            String medium,
            String collection,
            String location) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("depicted", "http://www.wikidata.org/entity/" + depicted);
        values.put("file", file);
        values.put("image", image);
        put(values, "caption", caption);
        putEntity(values, "creator", creator);
        put(values, "creationDate", date);
        putEntity(values, "medium", medium);
        putEntity(values, "collection", collection);
        putEntity(values, "location", location);
        return new WikidataBinding(values);
    }

    private static void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) values.put(key, value);
    }

    private static void putEntity(Map<String, String> values, String key, String qid) {
        if (qid != null && !qid.isBlank()) {
            values.put(key, "http://www.wikidata.org/entity/" + qid);
        }
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(part, at)) >= 0; at += part.length()) count++;
        return count;
    }
}
