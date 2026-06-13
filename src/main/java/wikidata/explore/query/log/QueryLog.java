package wikidata.explore.query.log;

import quiz.QuizableAdapter;
import quiz.QuizableReference;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class QueryLog extends QuizableAdapter {

    private long id;
    private String purpose;
    private String queryType;
    private String status;
    private String parameters;
    private String template;
    private String summary;
    private String error;
    private long timeMs;

    @QuizableReference
    private QueryLogText request;

    private final Collection<QueryLog> subQueries =
            new ArrayList<>();

    public QueryLog() {}

    public QueryLog(long id, Query<?> query) {
        this.id = id;

        if (query != null) {
            this.purpose = query.purpose();
            this.queryType = "QUERY";
            this.template = query.skeleton();
            this.parameters = formatParameters(query.parameters());
        }
    }

    public static QueryLog info(String text) {
        QueryLog log = new QueryLog();
        log.id = System.identityHashCode(log);
        log.purpose = "Log message";
        log.queryType = "INFO";
        log.status = "OK";
        log.summary = firstLine(text);
        log.request = new QueryLogText("Message", text);
        return log;
    }

    @Override
    public String getIdentifier() {
        return getClass().getSimpleName()
                + "@"
                + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public String getDisplayName() {
        String p = blank(purpose) ? "Query" : purpose;
        String k = blank(queryType) ? "" : " · " + queryType;
        String s = blank(status) ? "" : " · " + status;
        return p + k + s;
    }

    public void update(
            QueryStatus status,
            int rowCount,
            long timeMs,
            String error) {

        this.status = status == null ? "" : status.name();
        this.timeMs = timeMs;
        this.error = error == null ? "" : error;

        if (status == QueryStatus.OK) {
            this.summary = rowCount >= 0
                    ? rowCount + " rows"
                    : "Done";
        } else if (status == QueryStatus.RUNNING) {
            this.summary = "Running...";
        } else if (!blank(this.error)) {
            this.summary = this.error;
        }
    }

    public void appendRequestText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (request == null) {
            request = new QueryLogText("Request");
        }

        request.append(text);
    }

    private static String formatParameters(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }

            sb.append(e.getKey())
              .append(" = ")
              .append(e.getValue());
        }

        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String s = text.strip();
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public long id() { return id; }
    public String purpose() { return purpose; }
    public String queryType() { return queryType; }
    public String status() { return status; }
    public String parameters() { return parameters; }
    public String template() { return template; }
    public QueryLogText request() { return request; }
    public String summary() { return summary; }
    public String error() { return error; }
    public long timeMs() { return timeMs; }
    public Collection<QueryLog> subQueries() { return subQueries; }
}