package commons.discovery;

import org.junit.jupiter.api.Test;
import quiz.source.WikidataSource;
import wikidata.WikidataBinding;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        String scene = query.sceneSparqlForTest(List.of("M1", "M2"));
        assertTrue(scene.contains("VALUES ?file { <https://commons.wikimedia.org/entity/M1>"
                + " <https://commons.wikimedia.org/entity/M2> }"), scene);
        assertTrue(scene.contains("?file p:P180 ?depictionStatement"), scene);
        assertTrue(scene.contains("?depictionStatement ps:P180 ?sceneEntity"), scene);
        assertTrue(scene.contains("wikibase:rank ?depictionRank"), scene);
        assertTrue(scene.contains("?qualifierProperty"), scene);
        assertFalse(scene.contains("wdt:P170"),
                "file metadata would multiply every depiction/qualifier row: " + scene);

        String metadata = query.metadataSparqlForTest(List.of("M1", "M2"));
        assertTrue(metadata.contains("wdt:P6243 ?digitalRepresentation"), metadata);
        assertTrue(metadata.contains("wdt:P921 ?mainSubject"), metadata);
        assertFalse(metadata.contains("p:P180"), metadata);
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
        assertEquals(List.of(douglas, cat), media.selectedEntities());
        assertEquals(List.of("Q42", "Q146"), media.depictions().stream()
                .map(value -> value.depictedEntity().qid()).toList());
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

        assertEquals(3, requests.size(), "files, then their metadata and their scenes");
        assertEquals(1, occurrences(requests.getFirst(), "?file wdt:P180 wd:Q42"),
                requests.getFirst());
        assertTrue(requests.get(2).contains("?file p:P180 ?depictionStatement"));
        assertEquals("2", query.parameters().get("selectedInstances"));
        assertEquals("1", query.parameters().get("selectedQids"));
        assertEquals("7", query.parameters().get("maximumFilesBeforeDeduplication"));
        assertEquals(List.of(person, author), result.media().getFirst().selectedEntities());
        assertTrue(result.withoutMedia().isEmpty());
    }

    @Test void completeSceneKeepsCoDepictionsProminenceAndGenericQualifiers()
            throws Exception {
        WikidataDynamicObject selected = entity("Q42", "Douglas Adams");
        String file = "https://commons.wikimedia.org/entity/M1";
        String image = "https://commons.wikimedia.org/wiki/Special:FilePath/Scene.jpg";
        Set<String> requestedLabels = new LinkedHashSet<>();
        CommonsDiscoveryQuery query = new CommonsDiscoveryQuery(
                List.of(selected), 7, sparql -> {
                    if (sparql.contains("SELECT ?matched")) {
                        return List.of(row("Q42", file, image, "Scene", "", "",
                                "", "", ""));
                    }
                    return List.of(
                            sceneRow(file, "S1", "Q42", "PreferredRank",
                                    "", "", "Q5", "2008-10-15T00:00:00Z",
                                    "Q296955", "Q123", "Q456", "Q789", "Q900"),
                            sceneRow(file, "S2", "Q146", "NormalRank",
                                    "P462", "Q23445", "Q5",
                                    "2008-10-15T00:00:00Z", "Q296955", "Q123",
                                    "Q456", "Q789", "Q900"),
                            sceneRow(file, "S2", "Q146", "NormalRank",
                                    "P1114", "2", "Q5",
                                    "2008-10-15T00:00:00Z", "Q296955", "Q123",
                                    "Q456", "Q789", "Q900"));
                }, (context, ids) -> {
                    requestedLabels.addAll(ids);
                    return Map.ofEntries(
                        Map.entry("Q42", "Douglas Adams"), Map.entry("Q146", "cat"),
                        Map.entry("P462", "color"), Map.entry("Q23445", "black"),
                        Map.entry("P1114", "quantity"),
                        Map.entry("Q5", "human"), Map.entry("Q296955", "oil paint"),
                        Map.entry("Q123", "Example collection"),
                        Map.entry("Q456", "Example museum"),
                        Map.entry("Q789", "Example artwork"),
                        Map.entry("Q900", "Example subject"));
                });

        MediaRepresentation media = query.execute(new QueryContext()).media().getFirst();

        assertEquals(2, media.depictions().size());
        DepictionEvidence selectedDepiction = media.depictions().getFirst();
        assertEquals("Q42", selectedDepiction.depictedEntity().qid());
        assertTrue(selectedDepiction.prominent());
        DepictionEvidence cat = media.depictions().get(1);
        assertEquals("Q146", cat.depictedEntity().qid());
        assertFalse(cat.prominent());
        assertEquals(2, cat.qualifiers().size());
        DepictionQualifier color = cat.qualifiers().stream()
                .filter(value -> value.getDisplayName().equals("color: black"))
                .findFirst().orElseThrow();
        assertEquals("Q23445", color.entityValue().qid());
        DepictionQualifier quantity = cat.qualifiers().stream()
                .filter(value -> value.getDisplayName().equals("quantity: 2"))
                .findFirst().orElseThrow();
        assertEquals("2", quantity.literalValue());
        // A literal has no URL; rendered as a link it would point at the word itself.
        assertFalse(objectview.ViewableAdapter.isLinkField(
                DepictionQualifier.class.getDeclaredField("literalValue")),
                "a literal qualifier value is plain text");
        assertTrue(objectview.ViewableAdapter.isLinkField(
                DepictionQualifier.class.getDeclaredField("property")));
        assertEquals(List.of("Q789"), media.digitalRepresentations().stream()
                .map(WikidataSource::qid).toList());
        assertEquals(List.of("Q900"), media.mainSubjects().stream()
                .map(WikidataSource::qid).toList());
        assertTrue(requestedLabels.containsAll(
                List.of("Q146", "P462", "Q23445", "P1114")), requestedLabels::toString);
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
        values.put("matched", "http://www.wikidata.org/entity/" + depicted);
        values.put("file", file);
        values.put("image", image);
        values.put("depictionStatement", file + "/statement/" + depicted);
        values.put("sceneEntity", "http://www.wikidata.org/entity/" + depicted);
        values.put("depictionRank", "http://wikiba.se/ontology#NormalRank");
        put(values, "caption", caption);
        putEntity(values, "creator", creator);
        put(values, "creationDate", date);
        putEntity(values, "medium", medium);
        putEntity(values, "collection", collection);
        putEntity(values, "location", location);
        return new WikidataBinding(values);
    }

    private static WikidataBinding sceneRow(
            String file,
            String statement,
            String depicted,
            String rank,
            String qualifierPid,
            String qualifierValueQid,
            String creator,
            String date,
            String medium,
            String collection,
            String location,
            String digitalRepresentation,
            String mainSubject) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("file", file);
        values.put("caption", "Scene");
        values.put("depictionStatement", file + "/statement/" + statement);
        putEntity(values, "sceneEntity", depicted);
        values.put("depictionRank", "http://wikiba.se/ontology#" + rank);
        if (!qualifierPid.isBlank()) {
            values.put("qualifierProperty",
                    "http://www.wikidata.org/entity/" + qualifierPid);
            if (wikidata.WikidataIds.isQid(qualifierValueQid)) {
                putEntity(values, "qualifierValue", qualifierValueQid);
            } else {
                put(values, "qualifierValue", qualifierValueQid);
            }
        }
        putEntity(values, "creator", creator);
        put(values, "creationDate", date);
        putEntity(values, "medium", medium);
        putEntity(values, "collection", collection);
        putEntity(values, "location", location);
        putEntity(values, "digitalRepresentation", digitalRepresentation);
        putEntity(values, "mainSubject", mainSubject);
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
