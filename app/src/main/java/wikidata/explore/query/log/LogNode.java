package wikidata.explore.query.log;

import objectview.annotations.Hidden;
import objectview.annotations.Inline;
import objectview.annotations.Link;
import quiz.QuizableAdapter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One node in the log tree: the workflow root, a query step, or a
 * standalone message.
 *
 * <p>Nodes are dumb data. They are created and mutated only by a {@link
 * WorkflowRecorder} on the worker thread, and read on the EDT while the
 * tree is rendered via {@link quiz.QuizableAdapter} reflection. The
 * mutating methods are therefore package-private (only the recorder calls
 * them) and {@link #children} is copy-on-write so the renderer iterates a
 * stable snapshot without locking.
 */
public class LogNode extends QuizableAdapter {

    @Hidden
    private LogKind kind;

    // title and queryType are carried in the display label (getDisplayName),
    // not rendered as their own rows; status likewise shows in the label.
    @Hidden
    private String title;

    @Hidden
    private String queryType;

    @Hidden
    private LogStatus status = LogStatus.PENDING;

    private String description;
    // A LIST (one "key = value" per element) rather than a joined blob, so the
    // card renders the request parameters as a collapsible collection instead of a
    // single wall of text (e.g. the "domain = oscarnominations" entry).
    private List<String> parameters;
    private String skeleton;
    private String request;

    // A runnable link derived from request + queryType: SPARQL opens the
    // Wikidata Query Service editor, API opens the HTTP request. Carried as
    // "caption|url" so the chip shows a short caption, not the long URL.
    @Link
    private String link;

    private String summary;
    private String error;

    // Rendered as a formatted string ("570 ms"); the raw millis stay
    // available programmatically but aren't shown (0 reads as nothing).
    private String time;

    @Hidden
    private long timeMs;

    @Inline
    private final Collection<LogNode> steps =
            new CopyOnWriteArrayList<>();

    @Hidden
    private long startedAtMs;

    public LogNode() {}

    public LogNode(LogKind kind, String title) {
        this.kind = kind;
        this.title = emptyToNull(title);
    }

    // --- mutation, recorder-only ---

    void start() {
        status = LogStatus.RUNNING;
        summary = LogStatus.RUNNING.defaultSummary();
        error = null;
        startedAtMs = System.currentTimeMillis();
    }

    void complete(LogStatus status, String summary, String error) {
        this.status = status == null ? LogStatus.OK : status;
        this.error = emptyToNull(error);

        if (!blank(summary)) {
            this.summary = summary;
        } else if (this.error != null) {
            this.summary = this.error;
        } else {
            this.summary = this.status.defaultSummary();
        }

        if (startedAtMs > 0) {
            timeMs = System.currentTimeMillis() - startedAtMs;
            time = timeMs > 0 ? timeMs + " ms" : null;
        }
    }

    void addStep(LogNode step) {
        if (step != null) {
            steps.add(step);
        }
    }

    void appendRequest(String text) {
        request = appendText(request, text);
        updateLink();
    }

    private void updateLink() {
        if (blank(request) || blank(queryType)) {
            link = null;
            return;
        }

        String qt = queryType.toLowerCase();
        String req = request.strip();

        // An action-API request (wbgetentities, …) is an HTTP URL, not SPARQL —
        // even when the surrounding group inherited a "sparql" type. Detect it by
        // content so its link opens the API URL, not a WDQS page (which fails).
        boolean apiRequest = req.contains("api.php")
                || req.contains("wbgetentities")
                || req.startsWith("http");

        if (qt.contains("api") || apiRequest) {
            // Correct a mislabeled type: subqueries are logged as "SPARQL" by
            // default, but an action-API request is not SPARQL — relabel it so the
            // display reads "API", not "SPARQL".
            if (apiRequest && !qt.contains("api")) {
                queryType = "API";
            }
            String url = firstHttpLine(request);
            // The api.php query carries raw '|' (ids=Q1|Q2, props=labels|claims) and
            // spaces, which java.net.URI rejects ("Illegal character") so the browser
            // never opens. Percent-encode those so the link works.
            link = url == null ? null : "Open request|" + sanitizeUrl(url);
        } else if (qt.contains("sparql")) {
            link = "Open in query service|https://query.wikidata.org/#"
                    + encodeFragment(req);
        } else {
            link = null;
        }
    }

    private static String encodeFragment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // Percent-encode the characters an api.php URL commonly carries unescaped that
    // java.net.URI rejects, so BrowserLauncher can open it. Leaves already-valid
    // characters (incl. already-'%'-escaped sequences) untouched.
    private static String sanitizeUrl(String url) {
        return url.replace(" ", "%20")
                .replace("|", "%7C")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("\\", "%5C")
                .replace("^", "%5E");
    }

    private static String firstHttpLine(String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("http://") || t.startsWith("https://")) {
                return t;
            }
        }
        return null;
    }

    LogNode queryType(String queryType) {
        this.queryType = emptyToNull(queryType);
        return this;
    }

    LogNode description(String description) {
        this.description = emptyToNull(description);
        return this;
    }

    LogNode skeleton(String skeleton) {
        this.skeleton = emptyToNull(skeleton);
        return this;
    }

    LogNode parameters(List<String> parameters) {
        this.parameters = parameters == null || parameters.isEmpty() ? null : parameters;
        return this;
    }

    // --- Quizable ---

    @Override
    public String getIdentifier() {
        return getClass().getSimpleName()
                + "@"
                + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public String getDisplayName() {
        String t = blank(title)
                ? (kind == null ? "Log" : kind.label())
                : title;

        // queryType only when the title doesn't already convey it.
        if (!blank(queryType) && !t.toLowerCase().contains(queryType.toLowerCase())) {
            t = t + " · " + queryType;
        }

        String s = status == null || status == LogStatus.PENDING
                ? ""
                : " · " + status;
        return t + s;
    }

    // --- shared formatting helpers ---

    static List<String> formatParameters(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }

        List<String> rows = new ArrayList<>(params.size());
        for (Map.Entry<String, String> e : params.entrySet()) {
            rows.add(e.getKey() + " = " + e.getValue());
        }
        return rows;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String emptyToNull(String s) {
        return blank(s) ? null : s;
    }

    static String appendText(String oldText, String newText) {
        if (blank(newText)) {
            return oldText;
        }

        if (blank(oldText)) {
            return newText;
        }

        if (oldText.endsWith("\n") || newText.startsWith("\n")) {
            return oldText + newText;
        }

        return oldText + "\n" + newText;
    }

    // --- accessors ---

    public LogKind kind() { return kind; }
    public String title() { return title; }
    public String queryType() { return queryType; }
    public String description() { return description; }
    public List<String> parameters() { return parameters; }
    public String skeleton() { return skeleton; }
    public String request() { return request; }
    public String link() { return link; }
    public LogStatus status() { return status; }
    public String summary() { return summary; }
    public String error() { return error; }
    public long timeMs() { return timeMs; }
    public String time() { return time; }
    public Collection<LogNode> steps() { return steps; }
}
