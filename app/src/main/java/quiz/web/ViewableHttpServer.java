package quiz.web;

import wikidata.explore.extract.WikidataDynamicObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import quiz.group.ViewableGroup;
import objectview.media.ImageRef;
import objectview.Viewable;
import quiz.ordering.EqualValuePolicy;
import quiz.ordering.OrderKey;
import quiz.ordering.OrderValueType;
import quiz.ordering.OrderingQuizConfig;
import quiz.ordering.OrderingQuizGenerator;
import quiz.ordering.SortDirection;
import quiz.web.ordering.OrderingQuizView;

import java.io.IOException;
import java.io.OutputStream;
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
 * Minimal read-only JSON API over a {@link ViewableStore}, on the JDK's
 * built-in HTTP server (no extra dependency). Endpoints:
 * <ul>
 *   <li>{@code GET /api/types} — registered type names</li>
 *   <li>{@code GET /api/viewables?type=T} — shallow list ({id,name,type})</li>
 *   <li>{@code GET /api/viewable/{type}/{id}} — full {@link ViewableView}</li>
 * </ul>
 * Responses carry {@code Access-Control-Allow-Origin: *} so the Svelte dev
 * server can call it cross-port.
 */
public class ViewableHttpServer {

    private final ViewableStore store;
    private final ObjectMapper mapper = ViewableJson.mapper();
    private final BlurredImageService blurService;
    private final java.util.concurrent.ExecutorService exec =
            Executors.newCachedThreadPool();
    private final RequestLogFilter logFilter = new RequestLogFilter();
    private HttpServer server;

    // Domain → its type names, for grouping the client's class list as the number
    // of domains grows. Insertion-ordered; set by the bootstrap from the registry.
    private java.util.List<java.util.Map<String, Object>> domains =
            java.util.List.of();

    public ViewableHttpServer(ViewableStore store) {
        this.store = store;
        this.blurService = new BlurredImageService(store);
    }

    /** Groups served types by domain ({@code [{name, types:[…]}]}) for /api/domains. */
    public ViewableHttpServer domains(
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
        context("/api/viewables", this::handleList);
        context("/api/viewable/", this::handleDetail);
        context("/api/image/", this::handleImage);
        context("/api/quiz", this::handleQuiz);
        context("/api/ordering", this::handleOrdering);
        context("/api/pairing", this::handlePairing);
        context("/api/fields", this::handleFields);
        context("/api/groups", this::handleGroups);
        context("/api/dimensions", this::handleDimensions);
        context("/api/coverage", this::handleCoverage);
        context("/api/missing", this::handleMissing);
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
        System.out.println("Viewable API on http://localhost:" + port + "/api/types");
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

    /**
     * Which served collection a request means. A bare type name is enough while only one
     * domain serves it; once several do — which is what importing a shared model makes
     * ordinary — the request has to say, and {@code resolve} refuses rather than picking.
     */
    private ViewableStore.Address addressed(HttpExchange ex, String type) {
        String domain = queryParam(ex, "domain");
        if (domain != null && !domain.isBlank()) {
            return new ViewableStore.Address(domain, type);
        }
        // The address the client was handed, sent back whole. A type name never contains
        // a slash, so the last one separates a domain that may.
        int slash = type == null ? -1 : type.lastIndexOf('/');
        if (slash > 0) {
            return new ViewableStore.Address(
                    type.substring(0, slash), type.substring(slash + 1));
        }
        return store.resolve(type);
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
            ViewableStore.Address listed = addressed(ex, type);
            Collection<Viewable> qs = group == null || group.isBlank()
                    ? store.list(addressed(ex, type))
                    : store.members(addressed(ex, type), group);   // live re-facet: bucket members only
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            List<ViewableView.Ref> items = new ArrayList<>();
            for (Viewable q : qs) {
                items.add(new ViewableView.Ref(
                        q.getIdentifier(), q.getDisplayName(), q.typeName(),
                        ViewableJson.thumbUrl(q), null));
            }
            writeJson(ex, 200, items, listed);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleDetail(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/viewable/".length());
        int slash = path.indexOf('/');
        if (slash < 0) {
            writeJson(ex, 400, Map.of("error", "expected /api/viewable/{type}/{id}"));
            return;
        }

        String type = urlDecode(path.substring(0, slash));
        String id = urlDecode(path.substring(slash + 1));

        try {
            Viewable q = store.get(addressed(ex, type), id);
            if (q == null) {
                writeJson(ex, 404, Map.of("error", "not found: " + type + "/" + id));
                return;
            }
            writeJson(ex, 200, ViewableJson.of(q), addressed(ex, type));
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
            Viewable q = store.get(addressed(ex, type), id);
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
    private void prewarm(ViewableView.Field f) {
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
        Object v = value;
        if (index != null) {
            v = null;
            if (value instanceof Collection<?> c) {
                int i = 0;
                for (Object item : c) {
                    if (i++ == index) {
                        v = item;
                        break;
                    }
                }
            }
        }
        return asImageRef(v);
    }

    /** A directly-renderable ImageRef, or a MediaValue rendered on demand — a saved
     *  manual domain's images are metadata-only MediaValues, not live ImagePanes. */
    private static ImageRef asImageRef(Object v) {
        if (v instanceof ImageRef r) {
            return r;
        }
        if (v instanceof objectview.media.MediaValue m
                && m.mediaUrl() != null && !m.mediaUrl().isBlank()) {
            try {
                return new objectview.media.ImagePane(
                        m.mediaLabel(), m.mediaUrl(), null, false, m.mediaSvg(), false);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object fieldValue(Viewable q, String fieldName) {
        // A map-held snapshot object and a hand-written bean are one question (#87):
        // FieldSet picks the backing, so this reads the same either way — and a served
        // field that is DECLARED by the schema but not stored (a reference's display
        // name) resolves the way rendering resolves it, instead of coming back null.
        return objectview.field.FieldAccess.getPath(q, fieldName);
    }

    private void sendStatus(HttpExchange ex, int code) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    // GET /api/fields?type=T -> [{name, label, kind}] across a sample of the dataset,
    // for the quiz config UI to offer prompt/answer choices.
    private void handleFields(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        // Optional dotted path: fields available UNDER a reference, e.g.
        // path=namedAfter -> the fields of a constellation's named-after target.
        String path = queryParam(ex, "path");

        try {
            Collection<Viewable> qs = store.list(addressed(ex, type));
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            boolean nested = path != null && !path.isBlank();

            LinkedHashMap<String, String> kinds = new LinkedHashMap<>();
            LinkedHashMap<String, String> labels = new LinkedHashMap<>();
            for (objectview.field.FieldRef field
                    : objectview.field.ViewableContractFieldSet.fieldRefs()) {
                kinds.put(field.name(), "text");
                labels.put(field.name(), field.label());
            }
            int seen = 0;
            for (Viewable q : qs) {
                Viewable target = nested ? ViewableJson.resolvePath(q, path) : q;
                if (target != null) {
                    for (ViewableView.Field field : ViewableJson.of(target).fields()) {
                        kinds.putIfAbsent(field.name(), field.kind());
                        labels.putIfAbsent(field.name(), field.name());
                    }
                }
                if (++seen >= 40) {
                    break;
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            kinds.forEach((name, kind) -> out.add(Map.of(
                    "name", name,
                    "label", labels.getOrDefault(name, name),
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
            objectview.group.ViewableGroup<?> root = store.rootGroup(type);
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
            writeJson(ex, 200, store.dimensions(addressed(ex, type)));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/coverage?type=T -> per-field coverage [{label,path,present,total}] over
    // the served pool (the first consistency check: present vs. missing per field).
    private void handleCoverage(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        try {
            writeJson(ex, 200, store.coverage(addressed(ex, type)));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/missing?type=T&path=P&limit=N -> members lacking a value at P (the
    // drill-down behind a coverage gap): [{id,name,type}], id is a Q-id to link to WD.
    private void handleMissing(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        String path = queryParam(ex, "path");
        int limit = intParam(ex, "limit", 200);
        try {
            writeJson(ex, 200, store.missing(addressed(ex, type), path, limit),
                    addressed(ex, type));
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
                for (ViewableView.Field f : q.prompts()) {
                    prewarm(f);
                }
            }
            writeJson(ex, 200, quiz);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/ordering?type=&group=&prompt=&answer=&order=&valueType=&n=
    // -> a stateless ordered-card deck. Kept separate from /api/quiz so the
    // existing ABCD JSON contract and client behaviour remain untouched.
    private void handleOrdering(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        String group = queryParam(ex, "group");
        List<String> prompt = csv(queryParam(ex, "prompt"));
        List<String> answer = csv(queryParam(ex, "answer"));
        String order = queryParam(ex, "order");
        int n = intParam(ex, "n", 10);

        if (type == null || order == null || order.isBlank()) {
            writeJson(ex, 400, Map.of("error", "need type and order"));
            return;
        }
        try {
            OrderValueType valueType = enumParam(
                    queryParam(ex, "valueType"), OrderValueType.class, OrderValueType.DATE);
            SortDirection direction = enumParam(
                    queryParam(ex, "direction"), SortDirection.class, SortDirection.ASCENDING);
            EqualValuePolicy equality = enumParam(
                    queryParam(ex, "equalValues"), EqualValuePolicy.class,
                    EqualValuePolicy.EQUIVALENT);

            Collection<Viewable> members = store.members(addressed(ex, type), group);
            if (members == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }
            if (members.isEmpty()) {
                String scope = group == null || group.isBlank()
                        ? type : type + " group '" + group + "'";
                writeJson(ex, 400, Map.of("error", "no members found for " + scope));
                return;
            }

            Class<? extends objectview.Viewable> cls = members.stream()
                    .findFirst().map(Viewable::getClass).orElse(Viewable.class);
            objectview.viewconfig.ViewConfig promptView = selectedView(cls, prompt);
            objectview.viewconfig.ViewConfig answerView = selectedView(cls, answer);
            OrderingQuizConfig config = new OrderingQuizConfig(
                    promptView, answerView, new OrderKey(order, valueType),
                    direction, equality);

            OrderingQuizGenerator.GenerationResult generation =
                    OrderingQuizGenerator.generate(members, config);
            List<OrderingQuizGenerator.Item> items =
                    new ArrayList<>(generation.items());
            int total = members.size();
            int matched = generation.matched();
            int missing = generation.missing();
            int invalid = generation.invalid();
            // Missing values and entity-local data errors are skipped. If the
            // configuration produces no usable values at all, still fail the
            // request and include one rejected entity as a diagnostic.
            if (matched == 0) {
                String example = generation.invalidItems().isEmpty()
                        ? ""
                        : "; example invalid item "
                        + invalidItemDescription(generation.invalidItems().getFirst());
                writeJson(ex, 400, Map.of("error",
                        "no usable values for order field '" + order + "' among "
                        + total + " " + type + " (missing=" + missing
                        + ", invalid=" + invalid + ")" + example
                        + " — check the field name and valueType"));
                return;
            }
            java.util.Collections.shuffle(items);
            if (items.size() > Math.max(1, n)) {
                items = new ArrayList<>(items.subList(0, Math.max(1, n)));
            }
            writeJson(ex, 200, new OrderingQuizView(
                    "ORDERING", valueType == OrderValueType.DATE ? "TIMELINE" : "SCALE",
                    type, order, valueType, direction, equality,
                    total, matched, missing, invalid, List.copyOf(items)));
        } catch (IllegalArgumentException e) {
            writeJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static String invalidItemDescription(
            OrderingQuizGenerator.InvalidItem item) {
        String identity = item.id() != null && !item.id().isBlank()
                ? "'" + item.id() + "'"
                : item.name() != null && !item.name().isBlank()
                ? "'" + item.name() + "'" : "<unknown>";
        return identity + ": " + item.message();
    }

    private static objectview.viewconfig.ViewConfig selectedView(
            Class<? extends objectview.Viewable> cls, List<String> fields) {
        objectview.viewconfig.ViewConfig config =
                objectview.viewconfig.ViewConfig.of(cls);
        config.setAllFields(false);
        for (String field : fields) {
            config.addField(field, objectview.viewconfig.ViewConfig.leaf());
        }
        return config;
    }

    private static <E extends Enum<E>> E enumParam(
            String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type.getSimpleName() + ": " + raw);
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
                for (ViewableView.Field f : p.prompts()) {
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

    /**
     * Writes a response whose type names address a served collection rather than name a
     * class: every {@code type} becomes {@code domain/Type}.
     *
     * <p>Done here, at the wire, and not in {@link ViewableJson}: within a domain a bare
     * type name is exactly right, and threading a domain through view building would put
     * a serving concern into the rendering of every field. What the client needs is an
     * address it can send back, which is a property of the wire.
     */
    private void writeJson(HttpExchange ex, int code, Object body,
            ViewableStore.Address address) throws IOException {
        if (address == null || address.domain().isBlank()) {
            writeJson(ex, code, body);
            return;
        }
        com.fasterxml.jackson.databind.JsonNode tree = mapper.valueToTree(body);
        qualifyTypes(tree, address.domain());
        writeJson(ex, code, tree);
    }

    private static void qualifyTypes(
            com.fasterxml.jackson.databind.JsonNode node, String domain) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            com.fasterxml.jackson.databind.JsonNode type = object.get("type");
            if (type != null && type.isTextual() && !type.asText().isBlank()
                    && !type.asText().contains("/")) {
                object.put("type", domain + "/" + type.asText());
            }
            object.fields().forEachRemaining(e -> qualifyTypes(e.getValue(), domain));
        } else if (node.isArray()) {
            node.forEach(child -> qualifyTypes(child, domain));
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
