package quiz.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import objectview.ViewableAdapter;
import objectview.annotations.Reference;
import objectview.field.LinkedTextSequence;
import org.junit.jupiter.api.Test;
import quiz.source.WikidataStatementSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The browser's linked-text fixture is the real HTTP card, not a hand-written shape: a
 * linked-text field value and a reference chip whose caption is a linked text sequence,
 * both served through {@link ViewableHttpServer}. A hand-written fixture had drifted: it
 * declared a scalar reference as a chip, which the server sends as a linked-text field.
 */
class LinkedTextContractTest {

    @Test void theBrowserFixtureIsTheServedCard() throws Exception {
        JsonNode response = httpCard(new Statement());

        JsonNode browserContract = new ObjectMapper().readTree(Path.of(
                "../web/tests/fixtures/linked-text-contract.json").toFile());
        assertEquals(browserContract.path("details").path(Statement.ID), response,
                response.toPrettyString());
    }

    /** One served object carrying both linked-text shapes the web client renders. */
    public static final class Statement extends ViewableAdapter {
        static final String ID = "Q1$child";

        /** The statement's own DISPLAY value, read through the ordinary field API. */
        private final LinkedTextSequence source = (LinkedTextSequence)
                objectview.field.FieldSet.of(new WikidataStatementSource(
                        ID, "Q1", "P40", "Q2", "Child")).read("source");
        /** A collection member is a chip, so its caption carries the chip's links. */
        @Reference
        private final List<WikidataStatementSource> references = List.of(
                new WikidataStatementSource("Q3$instance", "Q3", "P31", "Q5", ""));

        @Override public String getIdentifier() { return ID; }
        @Override public String getDisplayName() { return "Statement"; }
        @Override public String typeName() { return "Statement"; }
    }

    private static JsonNode httpCard(Statement statement) throws Exception {
        ViewableStore store = new ViewableStore();
        store.register(new ViewableSource() {
            @Override public String type() { return "Statement"; }
            @Override public List<objectview.Viewable> load() { return List.of(statement); }
        });
        ViewableHttpServer server = new ViewableHttpServer(store);
        server.start(0);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.port()
                    + "/api/viewable/Statement/"
                    + java.net.URLEncoder.encode(Statement.ID,
                            java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return new ObjectMapper().readTree(response.body());
        } finally {
            server.stop();
        }
    }
}
