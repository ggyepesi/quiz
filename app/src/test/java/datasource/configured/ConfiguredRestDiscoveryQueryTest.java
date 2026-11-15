package datasource.configured;

import datasource.EntityRef;
import datasource.api.SourceValueKind;
import datasource.api.discovery.RelatedRecordDiscoveryOperation;
import datasource.api.discovery.RelatedRecordDiscoveryRequest;
import datasource.api.discovery.RelatedRecordSeed;
import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configured source must stay bounded and prove its relation from source evidence:
 * a detail record merely containing the seed's label must not become related.
 */
class ConfiguredRestDiscoveryQueryTest {

    @Test void boundedSearchRetainsOnlyRecordsWhoseEvidenceNamesTheExactSeed()
            throws Exception {
        List<String> requested = new ArrayList<>();
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            requested.add(uri.toString());
            if (uri.getPath().endsWith("/search")) {
                return "{\"total\":3,\"objectIDs\":[1,2,3]}";
            }
            if (uri.getPath().endsWith("/1")) return detail(1, "Anubis statue",
                    "Q47534", "Anubis");
            if (uri.getPath().endsWith("/2")) return detail(2,
                    "Anubis written in the title only", "Q42", "Douglas Adams");
            throw new AssertionError("The configured cap must exclude " + uri);
        });
        RelatedRecordDiscoveryOperation operation = operation(client, 2);

        var result = operation.discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis")))).execute(new QueryContext());

        assertEquals(1, result.records().size());
        assertEquals("met-object:1", result.records().getFirst().qualifiedId());
        assertEquals(List.of("anubis"), result.records().getFirst().matchedSeedKeys());
        assertEquals(List.of("Anubis"),
                result.records().getFirst().fields().get("subjectTags"));
        assertEquals(2, result.searchedCandidates(),
                "the result IDs are capped before detail requests are made");
        assertTrue(requested.stream().anyMatch(url ->
                url.contains("q=Anubis") && url.contains("limit=2")), requested.toString());
        assertFalse(requested.stream().anyMatch(url -> url.endsWith("/3")),
                requested.toString());
    }

    @Test void aRecordFoundByTwoSeedsIsFetchedOnceAndKeepsBothExactMatches()
            throws Exception {
        Map<String, Integer> calls = new LinkedHashMap<>();
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            calls.merge(uri.getPath(), 1, Integer::sum);
            if (uri.getPath().endsWith("/search")) {
                return "{\"total\":1,\"objectIDs\":[9]}";
            }
            return """
                    {"objectID":9,"title":"Two deities","objectURL":"https://met/9",
                     "primaryImageSmall":"https://images/9.jpg",
                     "tags":[
                       {"term":"Anubis","Wikidata_URL":"https://www.wikidata.org/wiki/Q47534"},
                       {"term":"Ra","Wikidata_URL":"https://www.wikidata.org/wiki/Q2831"}]}
                    """;
        });
        RelatedRecordDiscoveryOperation operation = operation(client, 4);

        var result = operation.discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis"),
                seed("ra", "Q2831", "Ra")))).execute(new QueryContext());

        assertEquals(1, result.records().size());
        assertEquals(List.of("anubis", "ra"),
                result.records().getFirst().matchedSeedKeys());
        assertEquals(1, calls.get("/detail/9"),
                "duplicate search IDs become one detail request");
    }

    @Test void oneUnavailableDetailMakesTheResultPartialWithoutLosingOtherRecords()
            throws Exception {
        int[] failedCalls = { 0 };
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            if (uri.getPath().endsWith("/search")) {
                return "{\"total\":2,\"objectIDs\":[1,2]}";
            }
            if (uri.getPath().endsWith("/1")) return detail(1, "Anubis statue",
                    "Q47534", "Anubis");
            failedCalls[0]++;
            throw new IOException("detail unavailable");
        });
        RelatedRecordDiscoveryOperation operation = operation(client, 2);

        var result = operation.discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis")))).execute(new QueryContext());

        assertEquals(1, result.records().size());
        assertTrue(result.partial());
        assertEquals(1, result.failedWork().size());
        assertEquals(2, failedCalls[0], "the configured batch retry budget is visible");
    }

    /** A status the server will not reconsider used to be FATAL for the whole run when a
     *  search received it, although the same status on a detail record cost only that
     *  record. One refused label search now costs only its own seed. */
    @Test void aRefusedSearchCostsOnlyItsSeed() throws Exception {
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            if (uri.getPath().endsWith("/search")) {
                if (uri.getQuery().contains("q=Ra")) {
                    throw new objectview.utils.UrlOpener.HttpStatusException(
                            400, uri.toURL());
                }
                return "{\"total\":1,\"objectIDs\":[1]}";
            }
            return detail(1, "Anubis statue", "Q47534", "Anubis");
        });
        RelatedRecordDiscoveryOperation operation = operation(client, 2);

        var result = operation.discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis"),
                seed("ra", "Q2831", "Ra")))).execute(new QueryContext());

        assertEquals(1, result.records().size());
        assertTrue(result.partial());
        assertEquals(List.of("Could not read Met test search for Ra (Q2831): HTTP 400"),
                result.failedWork());
    }

    /** A search only nominates. Detail evidence naming another selected seed relates the
     *  record to that seed too, although that seed's own search did not return it (#492). */
    @Test void detailEvidenceDecidesEverySelectedRelation() throws Exception {
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            if (uri.getPath().endsWith("/search")) {
                return uri.getQuery().contains("q=Anubis")
                        ? "{\"total\":1,\"objectIDs\":[9]}"
                        : "{\"total\":0,\"objectIDs\":[]}";
            }
            return """
                    {"objectID":9,"title":"Two deities",
                     "tags":[
                       {"term":"Anubis","Wikidata_URL":"https://www.wikidata.org/wiki/Q47534"},
                       {"term":"Ra","Wikidata_URL":"https://www.wikidata.org/wiki/Q2831"}]}
                    """;
        });

        var result = operation(client, 4).discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis"),
                seed("ra", "Q2831", "Ra")))).execute(new QueryContext());

        assertEquals(List.of("anubis", "ra"),
                result.records().getFirst().matchedSeedKeys());
    }

    /** A refused detail request was a successful batch value, so its step said
     *  "completed; 0 unavailable" while the result was partial (#492). */
    @Test void aRefusedRequestMakesItsStepPartial() throws Exception {
        ConfiguredRestClient client = new ConfiguredRestClient(uri -> {
            if (uri.getPath().endsWith("/search")) {
                return "{\"total\":2,\"objectIDs\":[1,2]}";
            }
            if (uri.getPath().endsWith("/1")) return detail(1, "Anubis statue",
                    "Q47534", "Anubis");
            throw new objectview.utils.UrlOpener.HttpStatusException(404, uri.toURL());
        });
        work.LogNode root = new work.LogNode(work.LogKind.WORKFLOW, "Discover");
        work.WorkflowRecorder recorder = new work.WorkflowRecorder(root);
        recorder.start();

        var result = operation(client, 2).discover(new RelatedRecordDiscoveryRequest(List.of(
                seed("anubis", "Q47534", "Anubis"))))
                .execute(new QueryContext().withRecorder(recorder));

        assertTrue(result.partial());
        List<work.LogNode> steps = new ArrayList<>(root.steps());
        assertEquals(work.LogStatus.OK, steps.get(0).status(), steps.get(0).summary());
        work.LogNode details = steps.get(1);
        assertEquals(work.LogStatus.PARTIAL, details.status());
        assertEquals("1 completed; 1 refused; 0 unavailable", details.summary());
        work.LogNode refused = details.steps().stream()
                .filter(node -> node.getDisplayName().contains("record 2"))
                .findFirst().orElseThrow();
        assertEquals(work.LogStatus.FAILED, refused.status(),
                "the refused request itself must not remain OK inside the partial step");
        assertTrue(refused.summary().contains("HTTP 404"), refused.summary());
    }

    private static RelatedRecordSeed seed(String key, String qid, String label) {
        return new RelatedRecordSeed(key, EntityRef.wikidata(qid), label);
    }

    private static RelatedRecordDiscoveryOperation operation(
            ConfiguredRestClient client, int limit) {
        ConfiguredDatasourceDefinition definition = new ConfiguredDatasourceDefinition(
                "met-test", "Met test", List.of(new ConfiguredDatasourceDefinition.RestOperation(
                "artifacts", "Artifacts", "wikidata", 10, limit, 2,
                new ConfiguredDatasourceDefinition.Search("https://example.test/search",
                        Map.of("q", "${seed.label}", "limit", "${limit}"),
                        "objectIDs[]", "total"),
                new ConfiguredDatasourceDefinition.Detail(
                        "https://example.test/detail/${result.id}"),
                new ConfiguredDatasourceDefinition.Match("tags[].Wikidata_URL",
                        ConfiguredDatasourceDefinition.ValueFormat.WIKIDATA_URL),
                new ConfiguredDatasourceDefinition.Record("MuseumArtifact", "met-object",
                        "objectID", "title", List.of(
                        new ConfiguredDatasourceDefinition.Field("image", "Image",
                                "primaryImageSmall", SourceValueKind.MEDIA, false),
                        new ConfiguredDatasourceDefinition.Field("metSource", "Met source",
                                "objectURL", SourceValueKind.URL, false),
                        new ConfiguredDatasourceDefinition.Field("subjectTags", "Subject tags",
                                "tags[].term", SourceValueKind.TEXT, true))))));
        return (RelatedRecordDiscoveryOperation)
                new ConfiguredRestDatasourceProvider(definition, client)
                        .operations().getFirst();
    }

    private static String detail(int id, String title, String qid, String tag) {
        return "{\"objectID\":" + id + ",\"title\":\"" + title
                + "\",\"objectURL\":\"https://met/" + id
                + "\",\"primaryImageSmall\":\"https://images/" + id
                + ".jpg\",\"tags\":[{\"term\":\"" + tag
                + "\",\"Wikidata_URL\":\"https://www.wikidata.org/wiki/"
                + qid + "\"}]}";
    }
}
