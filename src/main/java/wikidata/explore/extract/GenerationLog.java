package wikidata.explore.extract;

import java.util.function.Consumer;

/**
 * Progress sink for a generation run. {@link #message} is free-text progress;
 * {@link #subquery} records ONE sub-query as a structured, collapsible entry
 * (e.g. each per-parent child query) instead of appending it to an
 * ever-growing log blob.
 */
public interface GenerationLog {

    void message(String text);

    void subquery(String title, String request, String summary);

    GenerationLog NOOP = new GenerationLog() {
        @Override public void message(String text) {}
        @Override public void subquery(String t, String r, String s) {}
    };

    /** Adapts a plain text sink (subqueries are flattened into messages). */
    static GenerationLog of(Consumer<String> sink) {
        if (sink == null) {
            return NOOP;
        }
        return new GenerationLog() {
            @Override public void message(String text) { sink.accept(text); }
            @Override public void subquery(String title, String request, String summary) {
                sink.accept("\n" + title + "  " + (summary == null ? "" : summary)
                        + "\n" + (request == null ? "" : request) + "\n");
            }
        };
    }
}
