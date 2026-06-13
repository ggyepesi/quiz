package wikidata.explore.query.log;

import quiz.QuizableAdapter;

public class QueryLogText extends QuizableAdapter {

    private String title;
    private String text = "";

    public QueryLogText() {}

    public QueryLogText(String title) {
        this.title = title;
    }

    public QueryLogText(String title, String text) {
        this.title = title;
        this.text = text == null ? "" : text;
    }

    @Override
    public String getIdentifier() {
        return getClass().getSimpleName()
                + "@"
                + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    public String getDisplayName() {
        return title == null || title.isBlank() ? "Text" : title;
    }

    public void append(String s) {
        if (s != null && !s.isEmpty()) {
            text += s;
        }
    }

    public String title() {
        return title;
    }

    public String text() {
        return text == null ? "" : text;
    }
}