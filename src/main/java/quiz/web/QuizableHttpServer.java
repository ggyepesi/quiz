package quiz.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import quiz.ImageRef;
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
    private HttpServer server;

    public QuizableHttpServer(QuizableStore store) {
        this.store = store;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/types", this::handleTypes);
        server.createContext("/api/quizables", this::handleList);
        server.createContext("/api/quizable/", this::handleDetail);
        server.createContext("/api/image/", this::handleImage);
        server.createContext("/api/quiz", this::handleQuiz);
        server.createContext("/api/fields", this::handleFields);
        server.createContext("/api/groups", this::handleGroups);
        // Real pool so concurrent clients (e.g. phone + laptop) are handled
        // independently instead of serially on one thread.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Quizable API on http://localhost:" + port + "/api/types");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleTypes(HttpExchange ex) throws IOException {
        writeJson(ex, 200, store.types());
    }

    private void handleList(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");

        try {
            Collection<Quizable> qs = store.list(type);
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            List<QuizableView.Ref> items = new ArrayList<>();
            for (Quizable q : qs) {
                items.add(new QuizableView.Ref(
                        q.getIdentifier(), q.getDisplayName(), q.getClass().getSimpleName()));
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

        try {
            Collection<Quizable> qs = store.list(type);
            if (qs == null) {
                writeJson(ex, 404, Map.of("error", "unknown type: " + type));
                return;
            }

            LinkedHashMap<String, String> kinds = new LinkedHashMap<>();
            kinds.put("name", "text"); // display name — skipped in views, but quizzable
            int seen = 0;
            for (Quizable q : qs) {
                for (QuizableView.Field field : QuizableJson.of(q).fields()) {
                    kinds.putIfAbsent(field.name(), field.kind());
                }
                if (++seen >= 40) {
                    break;
                }
            }

            List<Map<String, String>> out = new ArrayList<>();
            kinds.forEach((name, kind) -> out.add(Map.of("name", name, "kind", kind)));
            writeJson(ex, 200, out);
        } catch (Exception e) {
            writeJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // GET /api/groups?type=T -> the group tree (or null if the type has none).
    private void handleGroups(HttpExchange ex) throws IOException {
        String type = queryParam(ex, "type");
        try {
            quiz.QuizableGroup root = store.rootGroup(type);
            writeJson(ex, 200, root == null ? null : GroupNode.of(root));
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
            writeJson(ex, 200,
                    QuizGenerator.generate(store, type, group, csv(prompt), csv(ask), n));
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
