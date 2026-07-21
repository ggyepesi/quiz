package quiz.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import quiz.QuizableGroup;
import objectview.media.ImageRef;
import quiz.Quizable;
import quiz.QuizableAdapter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal read-only JSON API over a {@link QuizableStore}, on the JDK's
 * built-in HTTP server (no extra dependency). Endpoints:
 * <ul>
 *   <li>{@code GET /api/types} — registered type names</li>
 *   <li>{@code GET /api/quizables?type=T} — shallow list ({id,name,type})</li>
 *   <li>{@code GET /api/quizable/{type}/{id}} — full {@link QuizableView}</li>
 * </ul>
 * Responses carry {@code Access-Control-Allow-Origin: *} so the Svelte dev
 * server can call it cross-port.
 */
public class QuizableHttpServer {

    private final QuizableStore store;
    private final ObjectMapper mapper = QuizableJson.mapper();
    private final BlurredImageService blurService;
    private final java.util.concurrent.ExecutorService exec =
            Executors.newCachedThreadPool();
    private final RequestLogFilter logFilter = new RequestLogFilter();
    private HttpServer server;

    // Domain → its type names, for grouping the client's class list as the number
    // of domains grows. Insertion-ordered; set by the bootstrap from the registry.
    private java.util.List<java.util.Map<String, Object>> domains =
            java.util.List.of();

    public QuizableHttpServer(QuizableStore store) {
        this.store = store;
        this.blurService = new BlurredImageService(store);
    }

    /** Groups served types by domain ({@code [{name, types:[…]}]}) for /api/domains. */
    public QuizableHttpServer domains(
            java.util.LinkedHashMap<String, java.util.List<String>> byDomain) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (byDomain != null) {
            byDomain.forEach((name, types) -> out.add(
                    java.util.Map.of("name", name, "types", types)));
        }
        this.domains = out;
        return this;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        context("/api/types", this::handleTypes);
        context("/api/domains", this::handleDomains);
        context("/api/quizables", this::handleList);
        context("/api/quizable/", this::handleDetail);
        context("/api/image/", this::handleImage);
        context("/api/quiz", this::handleQuiz);
        context("/api/pairing", this::handlePairing);
        context("/api/fields", this::handleFields);
        context("/api/groups", this::handleGroups);
        context("/api/dimensions", this::handleDimensions);
        context("/api/coverage", this::handleCoverage);
        context("/api/chart/", this::handleChart);

        // Model-builder (workbench) endpoints — read-only first slice, gated by
        // a password since they expose the generation model. Auth only on these
        // contexts; the quiz API stays open.
        ModelBuilderApi builder = new ModelBuilderApi();
        com.sun.net.httpserver.Authenticator builderAuth = new ModelBuilderAuth();
        secured("/api/builder/model", builder::handleModel, builderAuth);
        secured("/api/builder/ruletree", builder::handleRuleTree, builderAuth);
        secured("/api/builder/sparql", builder::handleSparqlPreview, builderAuth);

        // Real pool so concurrent clients (e.g. phone + laptop) are handled
        // independently instead of serially on one thread.
        server.setExecutor(exec);
        server.start();
        System.out.println("Quizable API on http://localhost:" + port + "/api/types");
    }

    // Registers a context with request logging.
    private void context(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler).getFilters().add(logFilter);
    }

    // Registers a context with request logging and an authenticator gate.
    private void secured(
            String path,
            com.sun.net.httpserver.HttpHandler handler,
            com.sun.net.httpserver.Authenticator auth) {
        com.sun.net.httpserver.HttpContext ctx = server.createContext(path, handler);
        ctx.getFilters().add(logFilter);
        ctx.setAuthenticator(auth);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleTypes(HttpExchange ex) throws IOException {
        writeJson(ex, 200, store.types());
    }

    private void handleDomains(HttpExchange ex) throws IOException {
        writeJson(ex, 200, domains);
    }

    private void handleList(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        String group = queryParam(ex, "group");   // optional: a dimension bucket fullName

        try {
            Collection<Quizable> qs = group == null || group.isBlank()
                    ? store.list(type)
                    : store.members(type, group);   // live re-facet: bucket members only
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            List<QuizableView.Ref> items = new ArrayList<>();
            for (Quizable q : qs) {
                items.add(new QuizableView.Ref(
                        q.getIdentifier(), q.getDisplayName(), q.typeName()));
            }
            writeJson(ex, 200, items);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleDetail(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/quizable/".length());
        int slash = path.indexOf('/');
        if (slash < 0) {
            writeJson(ex, 400, Map.of("error", "expected /api/quizable/{type}/{id}"));
            return;
        }

        String type = urlDecode(path.substring(0, slash));
        String id = urlDecode(path.substring(slash + 1));

        try {
            Quizable q = store.get(type, id);
            if (q == null) {
                writeJson(ex, 404, Map.of("error", "not found: " + type + "/" + id));
                return;
            }
            writeJson(ex, 200, QuizableJson.of(q));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/image/{type}/{id}/{field}[/{index}] -> PNG bytes of an
    // ImageRef field (or the index-th ImageRef of a collection field).
    private void handleImage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/image/".length());
        String[] parts = path.split("/", 4);
        if (parts.length < 3) {
            sendStatus(ex, 400);
            return;
        }

        String type = urlDecode(parts[0]);
        String id = urlDecode(parts[1]);
        String field = urlDecode(parts[2]);
        Integer index = parts.length >= 4 ? parseIndex(urlDecode(parts[3])) : null;

        try {
            Quizable q = store.get(type, id);
            Object value = q == null ? null : fieldValue(q, field);
            ImageRef ref = imageRefAt(value, index);

            if (ref == null) {
                sendStatus(ex, 404);
                return;
            }

            byte[] png = ref.pngBytes();
            if (png == null) {
                sendStatus(ex, 404);
                return;
            }

            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", "image/png");
            h.add("Access-Control-Allow-Origin", "*");
            h.add("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(png);
            }
        } catch (Exception e) {
            sendStatus(ex, 500);
        }
    }

    // GET /api/chart/{type}/{id}/{field} -> the image with its answer text
    // (e.g. the constellation name) mosaicked out. Cached after first build.
    private void handleChart(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/chart/".length());
        String[] parts = path.split("/", 3);
        if (parts.length < 3) {
            sendStatus(ex, 400);
            return;
        }
        try {
            byte[] img = blurService.blurred(
                    urlDecode(parts[0]), urlDecode(parts[1]), urlDecode(parts[2]));
            if (img == null) {
                sendStatus(ex, 404);
                return;
            }
            Headers h = ex.getResponseHeaders();
            // PNG magic 0x89; otherwise assume the (unblurred) original is JPEG.
            h.add("Content-Type", img.length > 0 && (img[0] & 0xff) == 0x89 ? "image/png" : "image/jpeg");
            h.add("Access-Control-Allow-Origin", "*");
            h.add("Cache-Control", "public, max-age=86400");
            ex.sendResponseHeaders(200, img.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(img);
            }
        } catch (Exception e) {
            sendStatus(ex, 500);
        }
    }

    // Build the blurred image in the background, so it's cached by the time the
    // player reaches that question/pair.
    private void prewarm(QuizableView.Field f) {
        if ("image".equals(f.kind())) {
            warm(f.url());
        } else if ("images".equals(f.kind()) && f.values() != null) {
            // A blurred member strip — warm each chart endpoint.
            for (String u : f.values()) {
                warm(u);
            }
        }
    }

    private void warm(String url) {
        if (url != null && url.startsWith("/api/chart/")) {
            exec.submit(() -> blurService.prewarm(url));
        }
    }

    private static Integer parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ImageRef imageRefAt(Object value, Integer index) {
        if (index == null) {
            return value instanceof ImageRef r ? r : null;
        }
        if (value instanceof Collection<?> c) {
            int i = 0;
            for (Object item : c) {
                if (i++ == index) {
                    return item instanceof ImageRef r ? r : null;
                }
            }
        }
        return null;
    }

    private static Object fieldValue(Quizable q, String fieldName) {
        Field f = QuizableAdapter.getField(q.getClass(), fieldName);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(q);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendStatus(HttpExchange ex, int code) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    // GET /api/fields?type=T -> [{name, kind}] across a sample of the dataset,
    // for the quiz config UI to offer prompt/answer choices.
    private void handleFields(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        // Optional dotted path: fields available UNDER a reference, e.g.
        // path=namedAfter -> the fields of a constellation's named-after target.
        String path = queryParam(ex, "path");

        try {
            Collection<Quizable> qs = store.list(type);
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            boolean nested = path != null && !path.isBlank();

            LinkedHashMap<String, String> kinds = new LinkedHashMap<>();
            kinds.put("name", "text"); // display name — skipped in views, but quizzable
            int seen = 0;
            for (Quizable q : qs) {
                Quizable target = nested ? QuizableJson.resolvePath(q, path) : q;
                if (target != null) {
                    for (QuizableView.Field field : QuizableJson.of(target).fields()) {
                        kinds.putIfAbsent(field.name(), field.kind());
                    }
                }
                if (++seen >= 40) {
                    break;
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            kinds.forEach((name, kind) -> out.add(Map.of(
                    "name", name,
                    "kind", kind,
                    // ref/refs fields can be expanded to configure their own fields
                    "expandable", "ref".equals(kind) || "refs".equals(kind))));
            writeJson(ex, 200, out);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/groups?type=T -> the group tree (or null if the type has none).
    private void handleGroups(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        try {
            QuizableGroup root = store.rootGroup(type);
            writeJson(ex, 200, root == null ? null : GroupNode.of(root));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/dimensions?type=T -> the DECLARED groupable dimensions [{label,path,kind}]
    // the client offers for live re-faceting (grouping executed on demand).
    private void handleDimensions(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        try {
            writeJson(ex, 200, store.dimensions(type));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/coverage?type=T -> per-field coverage [{label,path,present,total}] over
    // the served pool (the first consistency check: present vs. missing per field).
    private void handleCoverage(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        try {
            writeJson(ex, 200, store.coverage(type));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/quiz?type=&group=&prompt=&ask=&n= -> a multiple-choice quiz.
    private void handleQuiz(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        String group = queryParam(ex, "group");
        String prompt = queryParam(ex, "prompt");
        String ask = queryParam(ex, "ask");
        int n = intParam(ex, "n", 10);

        if (type == null || prompt == null || ask == null) {
            writeJson(ex, 400, Map.of("error", "need type, prompt, ask"));
            return;
        }

        try {
            Quiz quiz = QuizGenerator.generate(store, type, group, csv(prompt), csv(ask), n);
            for (Quiz.Question q : quiz.questions()) {
                for (QuizableView.Field f : q.prompts()) {
                    prewarm(f);
                }
            }
            writeJson(ex, 200, quiz);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/pairing?type=&group=&prompt=&ask=&n= -> a matching game.
    private void handlePairing(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        String group = queryParam(ex, "group");
        String prompt = queryParam(ex, "prompt");
        String ask = queryParam(ex, "ask");
        int n = intParam(ex, "n", 12);

        if (type == null || prompt == null || ask == null) {
            writeJson(ex, 400, Map.of("error", "need type, prompt, ask"));
            return;
        }

        try {
            Pairing pairing = PairingGenerator.generate(store, type, group, csv(prompt), csv(ask), n);
            for (Pairing.Pair p : pairing.pairs()) {
                for (QuizableView.Field f : p.prompts()) {
                    prewarm(f);
                }
            }
            writeJson(ex, 200, pairing);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static List<String> csv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int intParam(HttpExchange ex, String key, int def) {
        String v = queryParam(ex, key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void writeJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "application/json; charset=utf-8");
        h.add("Access-Control-Allow-Origin", "*");
        // Dynamic data (esp. each fresh quiz) must not be cached.
        h.add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String queryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return urlDecode(part.substring(eq + 1));
            }
        }
        return null;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
