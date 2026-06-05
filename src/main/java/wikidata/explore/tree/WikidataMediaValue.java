package wikidata.explore.tree;

/**
 * Metadata-only media value.
 * Does not download or decode images.
 */
public class WikidataMediaValue {

    private String label;
    private String url;
    private boolean svg;

    public WikidataMediaValue() {
        this("", "", false);
    }

    public WikidataMediaValue(String label, String url, boolean svg) {
        this.label = label == null ? "" : label;
        this.url = url == null ? "" : url;
        this.svg = svg;
    }

    public String label() {
        return label;
    }

    public void label(String label) {
        this.label = label == null ? "" : label;
    }

    public String url() {
        return url;
    }

    public void url(String url) {
        this.url = url == null ? "" : url;
    }

    public boolean svg() {
        return svg;
    }

    public void svg(boolean svg) {
        this.svg = svg;
    }

    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }

    public String displayText() {
        if (label != null && !label.isBlank()) return label;
        return url == null || url.isBlank() ? "<media>" : url;
    }

    @Override
    public String toString() {
        return displayText();
    }
}
