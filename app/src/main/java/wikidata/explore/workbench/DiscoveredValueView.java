package wikidata.explore.workbench;

import objectview.Viewable;
import objectview.annotations.DisplayField;
import objectview.field.FieldSet;

/** One discovered source capability — an infobox property, a category title, later a QID —
 * with how much of the sample carries it and what it looked like there. Every discovery
 * answers those same three things, so they are one class rather than one per provider:
 * the card's title is the value itself, and the provider's wording lives in the
 * {@link SourceDiscoveryPicker.Spec}, which is where the difference actually is. */
final class DiscoveredValueView implements Viewable {
    @DisplayField private final String value;
    /** What the reader picks, as a renderable row. It is the whole value for a category
     * — a twin of the header, shown only when the body would otherwise be empty — and
     * the parameter alone for an infobox, where the header carries the whole key.
     * {@link SourceDiscoveryPicker#hiddenFields} decides which of those it is. */
    private final String discoveredValue;
    /** The structure the value was read from (an infobox template), blank when the
     * value has no structure above it. */
    private final String sourceStructure;
    private final int have;
    private final String examples;

    DiscoveredValueView(String value, int have, String examples) {
        this(value, value, "", have, examples);
    }

    DiscoveredValueView(String value, String discoveredValue, String sourceStructure,
            int have, String examples) {
        this.value = value == null ? "" : value;
        this.discoveredValue = discoveredValue == null ? this.value : discoveredValue;
        this.sourceStructure = sourceStructure == null ? "" : sourceStructure;
        this.have = have;
        this.examples = examples == null ? "" : examples;
    }

    String value() { return value; }
    String discoveredValue() { return discoveredValue; }
    String sourceStructure() { return sourceStructure; }
    int have() { return have; }
    String examples() { return examples; }

    @Override public String getIdentifier() { return value; }
    @Override public String getDisplayName() { return value; }
    @Override public FieldSet fields() { return FieldSet.of(this); }
}
