package wikidata.explore.tree;

public class RememberedProperty {
    public String pid = "";
    public String label = "";
    public String description = "";
    public String wikibaseType = "";
    public String recommendedFieldType = "";
    public String firstSeenSource = "";
    public long firstSeenMillis;
    public long lastSeenMillis;
    public int timesUsed;

    public RememberedProperty() {}

    public RememberedProperty(PropertyValidationResult r, String source) {
        this.pid = r.pid();
        this.label = r.label();
        this.description = r.description();
        this.wikibaseType = r.wikibaseType();
        this.recommendedFieldType = r.recommendedFieldType();
        this.firstSeenSource = source == null ? "" : source;
        this.firstSeenMillis = System.currentTimeMillis();
        this.lastSeenMillis = this.firstSeenMillis;
        this.timesUsed = 1;
    }

    @Override public String toString() {
        return label == null || label.isBlank() ? pid : label + " (" + pid + ")";
    }
}
