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
    private final int have;
    private final String examples;

    DiscoveredValueView(String value, int have, String examples) {
        this.value = value == null ? "" : value;
        this.have = have;
        this.examples = examples == null ? "" : examples;
    }

    String value() { return value; }
    String examples() { return examples; }

    @Override public String getIdentifier() { return value; }
    @Override public String getDisplayName() { return value; }
    @Override public FieldSet fields() { return FieldSet.of(this); }
}
