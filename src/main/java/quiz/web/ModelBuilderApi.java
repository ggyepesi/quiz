package quiz.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeConfig;
import wikidata.explore.rule.RuleTreeSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Read-only HTTP surface for the Wikidata model workbench — the first slice of
 * webifying the desktop ModelBuilder. Serves the current project model, its
 * compiled rule tree, and the SPARQL it would run.
 *
 * <p>These endpoints only read/derive from the in-memory model (no mutation, no
 * network, no code-gen), so they are safe to expose — behind the authenticator
 * wired in {@link QuizableHttpServer}. Editing and generation come in later
 * slices.
 */
public class ModelBuilderApi {

    private final GeneratedProjectModel model;
    private final GeneratedProjectModelStore modelStore = new GeneratedProjectModelStore();
    private final RuleTreeSerializer ruleTreeStore = new RuleTreeSerializer();

    public ModelBuilderApi() {
        this(GeneratedProjectModel.constellationDemo());
    }

    public ModelBuilderApi(GeneratedProjectModel model) {
        this.model = model == null ? GeneratedProjectModel.constellationDemo() : model;
    }

    /** GET /api/builder/model -> the editable project config as JSON. */
    void handleModel(HttpExchange ex) throws IOException {
        try {
            sendString(ex, 200, "application/json; charset=utf-8", modelStore.toJson(model));
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    /** GET /api/builder/ruletree -> the compiled rule-tree plan as JSON. */
    void handleRuleTree(HttpExchange ex) throws IOException {
        try {
            RuleNode root = RuleTreeCompiler.compileProject(model);
            String json = ruleTreeStore.mapper()
                    .writeValueAsString(RuleTreeConfig.of(root));
            sendString(ex, 200, "application/json; charset=utf-8", json);
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    /** GET /api/builder/sparql?depth=N -> the SPARQL the generator would run. */
    void handleSparqlPreview(HttpExchange ex) throws IOException {
        try {
            int depth = intParam(ex, "depth", 0);
            RuleNode root = RuleTreeCompiler.compileProject(model);
            // previewQueries only builds query strings (no client calls).
            List<String> queries = new RuleTreeExtractor(null).previewQueries(root, depth);
            sendString(ex, 200, "text/plain; charset=utf-8",
                    String.join("\n\n", queries));
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    private static int intParam(HttpExchange ex, String key, int dflt) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return dflt;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                try {
                    return Integer.parseInt(part.substring(eq + 1));
                } catch (NumberFormatException ignored) {
                    return dflt;
                }
            }
        }
        return dflt;
    }

    private static void sendString(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", contentType);
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange ex, Exception e) throws IOException {
        sendString(ex, 500, "text/plain; charset=utf-8",
                "error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
    }
}
