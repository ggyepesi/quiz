package domain.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.web.ViewableHttpServer;
import quiz.web.ViewableSource;
import quiz.web.ViewableStore;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.ModelSourceExecutionPlan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configured Egyptian-deity symbol is one complete datasource slice: the saved
 * model chooses the Wikipedia infobox row, acquisition retains its answer/evidence,
 * the flattened snapshot preserves both, Remap performs no acquisition, and the same
 * generic generated instance reaches the HTTP card contract.
 */
class EgyptianSymbolAcceptanceTest {

    @Test void symbolAndExaminedEmptySurviveSnapshotOfflineRemapAndHttp(
            @TempDir Path directory) throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                Path.of("../data/wikidata/egyptiangods/egyptiangods.model.json").toFile());
        var plan = ModelSourceExecutionPlan.compile(model, domain.datasource.Datasources.standard());
        WikidataDynamicObject amun = deity("Q1268", "Amun", "Amun");
        WikidataDynamicObject anubis = deity("Q47534", "Anubis", "Anubis");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(amun, anubis));
        RecordingLog log = new RecordingLog();

        WikipediaInfoboxAcquisition.Result acquired = WikipediaInfoboxAcquisition.apply(
                model, pool, log, new work.CancellationToken(), new SitelinkClient(), uri -> {
                    String query = java.net.URLDecoder.decode(uri.getRawQuery(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (query.contains("page=Amun")) {
                        return renderedResponse("Amun", 101, """
                                <table class="infobox"><tbody><tr>
                                  <th class="infobox-label">Symbol</th>
                                  <td class="infobox-data">was sceptre</td>
                                </tr></tbody></table>
                                """);
                    }
                    return renderedResponse("Anubis", 102, """
                            <table class="infobox"><tbody><tr>
                              <th class="infobox-label">Parents</th>
                              <td class="infobox-data">Ra and Hesat</td>
                            </tr></tbody></table>
                            """);
                }, plan);

        assertEquals("was sceptre", amun.get("symbol"));
        assertNull(anubis.get("symbol"));
        assertEquals(1, acquired.count(WikipediaInfoboxAcquisition.OutcomeState.VALUE));
        assertEquals(1, acquired.count(
                WikipediaInfoboxAcquisition.OutcomeState.ANSWERED_EMPTY));
        assertEquals(0, acquired.count(WikipediaInfoboxAcquisition.OutcomeState.FAILED));
        assertTrue(log.infoboxGroupSeen);
        assertEquals(2, log.childRequests,
                "the article requests belong to the one infobox acquisition log group");

        Path snapshot = directory.resolve("egyptian-symbol.instances.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(pool, snapshot.toFile());
        List<WikidataDynamicObject> loaded = store.load(snapshot.toFile());
        WikidataDynamicObject loadedAmun = byQid(loaded, "Q1268");
        WikidataDynamicObject loadedAnubis = byQid(loaded, "Q47534");
        assertEquals("was sceptre", loadedAmun.get("symbol"));
        assertEquals("101", loadedAmun.infoboxParameters().document().revision());
        assertTrue(loadedAnubis.renderedInfoboxAnswered());
        assertNull(loadedAnubis.get("symbol"));

        GenerationRun previous = new GenerationRun(
                model, 1, null, loaded, null, List.of());
        GenerationRun remapped = new GenerationPipeline().remap(
                previous, model, GenerationLog.NOOP, RunSteps.SILENT);
        assertFalse(remapped.instances().isEmpty());
        objectview.Viewable servedAmun = remapped.instances().stream()
                .filter(value -> "Q1268".equals(value.getIdentifier()))
                .findFirst().orElseThrow();
        objectview.field.FieldSet fields = objectview.field.FieldSet.of(servedAmun);
        assertEquals("was sceptre", fields.read("symbol"));
        domain.provenance.WikipediaSource wikipedia = (domain.provenance.WikipediaSource)
                fields.read("wikipediaSource");
        assertNotNull(wikipedia);
        assertEquals("https://en.wikipedia.org/wiki/Amun", wikipedia.url());

        JsonNode response = httpCard(remapped.instances(), "Q1268");
        assertEquals("Amun", response.path("name").asText());
        assertEquals("was sceptre", field(response, "symbol").path("value").asText());
        JsonNode source = field(response, "wikipediaSource");
        assertEquals("https://en.wikipedia.org/wiki/Amun",
                source.path("url").asText(), response.toPrettyString());
        JsonNode emptyResponse = httpCard(remapped.instances(), "Q47534");
        assertEquals("", field(emptyResponse, "symbol").path("value").asText());

        JsonNode browserContract = new ObjectMapper().readTree(Path.of(
                "../web/tests/fixtures/egyptian-symbol-contract.json").toFile());
        assertEquals(browserContract.path("details").path("Q1268"),
                contractFields(response));
        assertEquals(browserContract.path("details").path("Q47534"),
                contractFields(emptyResponse));
    }

    private static JsonNode httpCard(List<objectview.Viewable> instances, String id)
            throws Exception {
        ViewableStore store = new ViewableStore();
        store.register(new ViewableSource() {
            @Override public String type() { return "EgyptianDeity"; }
            @Override public List<objectview.Viewable> load() { return instances; }
        });
        ViewableHttpServer server = new ViewableHttpServer(store);
        server.start(0);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.port()
                    + "/api/viewable/EgyptianDeity/" + id);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return new ObjectMapper().readTree(response.body());
        } finally {
            server.stop();
        }
    }

    private static JsonNode field(JsonNode view, String name) {
        for (JsonNode field : view.path("fields")) {
            if (name.equals(field.path("name").asText())) return field;
        }
        throw new AssertionError("No field " + name + " in " + view.toPrettyString());
    }

    /** The exact server response slice consumed by the browser test. Extra configured
     * fields may exist on the real card; this contract follows the two fields under test. */
    private static JsonNode contractFields(JsonNode response) {
        ObjectMapper mapper = new ObjectMapper();
        var contract = mapper.createObjectNode();
        contract.put("id", response.path("id").asText());
        contract.put("name", response.path("name").asText());
        contract.put("type", response.path("type").asText());
        var fields = contract.putArray("fields");
        fields.add(field(response, "wikipediaSource"));
        fields.add(field(response, "symbol"));
        return contract;
    }

    private static WikidataDynamicObject deity(
            String qid, String label, String article) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, label);
        value.type("EgyptianDeity");
        value.enwikiTitle(article);
        return value;
    }

    private static WikidataDynamicObject byQid(
            List<WikidataDynamicObject> values, String qid) {
        return values.stream().filter(value -> qid.equals(value.qid()))
                .findFirst().orElseThrow();
    }

    private static String renderedResponse(String title, int revision, String html) {
        ObjectMapper json = new ObjectMapper();
        return "{\"parse\":{\"title\":" + json.valueToTree(title)
                + ",\"revid\":" + revision
                + ",\"wikitext\":\"{{Infobox deity}}\",\"text\":"
                + json.valueToTree(html) + "}}";
    }

    private static final class SitelinkClient extends wikidata.api.WikidataApiClient {
        private SitelinkClient() { super(DEFAULT_USER_AGENT); }

        @Override protected JsonNode getEntitiesBatch(
                List<String> qids, boolean withClaims) throws Exception {
            StringBuilder entities = new StringBuilder();
            for (String qid : qids) {
                if (!entities.isEmpty()) entities.append(',');
                String title = "Q1268".equals(qid) ? "Amun" : "Anubis";
                entities.append(new ObjectMapper().valueToTree(qid)).append(":{\"id\":")
                        .append(new ObjectMapper().valueToTree(qid))
                        .append(",\"sitelinks\":{\"enwiki\":{\"title\":")
                        .append(new ObjectMapper().valueToTree(title)).append("}}}");
            }
            return new ObjectMapper().readTree("{\"entities\":{" + entities + "}}");
        }
    }

    private static final class RecordingLog implements GenerationLog {
        private boolean infoboxGroupSeen;
        private int childRequests;

        @Override public void message(String text) { }
        @Override public void subquery(String title, String request, String summary) { }

        @Override public Group group(String title) {
            infoboxGroupSeen |= title != null
                    && title.startsWith("Acquire native Wikipedia infoboxes");
            return new Group() {
                @Override public void close() { }
                @Override public void message(String text) { }
                @Override public void subquery(
                        String title, String request, String summary) { }
                @Override public Running subqueryStarted(String title, String request) {
                    childRequests++;
                    return new Running() {
                        @Override public void done(String summary) { }
                        @Override public void failed(String error) { }
                    };
                }
            };
        }
    }
}
