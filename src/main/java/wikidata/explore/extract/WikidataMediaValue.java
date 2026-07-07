package wikidata.explore.extract;

/**
 * Metadata-only media value.
 * Does not download or decode images.
 *
 * <p>Implements the generic {@link quiz.ui.MediaValue} so the renderer can turn it
 * into an image without depending on wikidata — the conversion happens at render
 * time, so this value stays serializable in the data pool.
 */
public class WikidataMediaValue implements quiz.ui.MediaValue {

    private String label;
    private String url;
    private boolean svg;

    @Override public String mediaUrl() { return url; }
    @Override public String mediaLabel() { return label; }
    @Override public boolean mediaSvg() { return svg; }

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
