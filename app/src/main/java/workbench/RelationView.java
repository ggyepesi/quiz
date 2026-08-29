package workbench;

import objectview.Viewable;
import objectview.annotations.DisplayField;
import objectview.field.FieldSet;

/**
 * One relation of an explored entity, modelled as a {@link Viewable} so the Explore panel can
 * render the relation battery through the shared {@code SearchableView} — getting search,
 * per-field sort and filter (including by {@link #kind}) for free, exactly like every other
 * card view, instead of a bespoke {@code JTable}. Reflection over the declared fields drives
 * the card; {@code label} is hidden by the caller because it is shown as the card title.
 *
 * <p>{@link #kind} is the value type surfaced to the user — {@code entity}, {@code image},
 * {@code number}, {@code date} or {@code text} — so a literal property (population, area) is
 * distinguishable at a glance from an entity relation, and the list can be filtered by it.
 */
public final class RelationView implements Viewable {

    private final String kind;
    private final int count;
    private final String example;
    private final String exampleQid;
    private final String direction;
    private final String pid;
    @DisplayField private final String label;

    public RelationView(
            String direction, String pid, String label,
            int count, String example, String kind, String exampleQid) {
        this.direction = direction == null ? "" : direction;
        this.pid = pid == null ? "" : pid;
        this.label = label == null || label.isBlank() ? this.pid : label;
        this.count = count;
        this.example = example == null ? "" : example;
        this.exampleQid = exampleQid == null ? "" : exampleQid;
        this.kind = kind == null ? "" : kind;
    }

    public String pid() { return pid; }

    /** The relation's human label (the value returned to a property picker). */
    public String relationLabel() { return label; }

    /** Incoming relations point AT the explored entity; outgoing start from it. */
    public boolean incoming() { return direction.startsWith("←"); }
    public String exampleQid() { return exampleQid; }

    @Override public String getIdentifier() { return pid + "|" + direction; }

    @Override public String getDisplayName() { return label; }

    @Override public FieldSet fields() { return FieldSet.of(this); }
}
