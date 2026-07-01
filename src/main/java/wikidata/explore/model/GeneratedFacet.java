package wikidata.explore.model;

/**
 * A declared GROUPING dimension on a class — a presentation-layer partition of the
 * SAME entities by a field's value (vs a {@code subclass}, which changes the
 * schema). Fed to {@code quiz.facet.FacetGrouper} via the
 * {@code wikidata.explore.view.DomainFacets} bridge, so grouping is part of the
 * domain instead of hand-built per dataset.
 *
 * <p>Bucketing decides how a field's value(s) become bucket keys:
 * VALUE (one bucket per distinct value), FIRST_LETTER (A, B, …), or RANGE
 * (numeric/year floored to {@link #rangeSize}, e.g. 10 → decades).
 */
public class GeneratedFacet {

    public enum Bucketing { VALUE, FIRST_LETTER, RANGE }

    private String name = "";          // display label, e.g. "by category"
    private String fieldName = "";      // the field to group by
    private Bucketing bucketing = Bucketing.VALUE;
    private int rangeSize = 10;         // for RANGE (10 = decade)

    public GeneratedFacet() {}

    public GeneratedFacet(String name, String fieldName, Bucketing bucketing) {
        this.name = name == null ? "" : name;
        this.fieldName = fieldName == null ? "" : fieldName;
        this.bucketing = bucketing == null ? Bucketing.VALUE : bucketing;
    }

    public String name() { return name; }
    public void name(String name) { this.name = name == null ? "" : name; }

    public String fieldName() { return fieldName; }
    public void fieldName(String fieldName) {
        this.fieldName = fieldName == null ? "" : fieldName;
    }

    public Bucketing bucketing() { return bucketing; }
    public void bucketing(Bucketing bucketing) {
        this.bucketing = bucketing == null ? Bucketing.VALUE : bucketing;
    }

    public int rangeSize() { return rangeSize; }
    public void rangeSize(int rangeSize) {
        this.rangeSize = rangeSize <= 0 ? 10 : rangeSize;
    }

    /** Short description for the tree node, e.g. "category · value". */
    public String describe() {
        String b = switch (bucketing) {
            case VALUE -> "value";
            case FIRST_LETTER -> "first letter";
            case RANGE -> "range/" + rangeSize;
        };
        return (fieldName.isBlank() ? "?" : fieldName) + " · " + b;
    }

    @Override public String toString() {
        return (name.isBlank() ? "facet" : name) + "  [" + describe() + "]";
    }
}
