package quiz.transform.ui;

import java.util.List;

/**
 * The typed SIGNATURE of an {@link OperationKind}: its ordered argument slots,
 * each constraining which domain fields can fill it, plus whether it takes a
 * literal value. This is the abstraction that lets the fields pane narrow to only
 * valid arguments per slot — and generalizes cleanly to multi-argument operations
 * whose arguments come from different classes.
 */
public final class OperationSignature {

    /** What a slot accepts, matched against a {@link DomainField}'s shape. */
    public enum Need {
        ANY, SCALAR, REFERENCE, COLLECTION;

        public boolean accepts(DomainField f) {
            if (f == null) {
                return false;
            }
            return switch (this) {
                case ANY -> true;
                case SCALAR -> f.scalar();
                case REFERENCE -> f.reference();
                case COLLECTION -> f.collection();
            };
        }
    }

    /** One argument slot: a label + the field shape it accepts. */
    public record Slot(String label, Need need) {}

    private final OperationKind kind;
    private final List<Slot> slots;
    private final boolean needsValue;
    private final boolean multiField;

    private OperationSignature(OperationKind kind, List<Slot> slots,
                              boolean needsValue, boolean multiField) {
        this.kind = kind;
        this.slots = slots;
        this.needsValue = needsValue;
        this.multiField = multiField;
    }

    public OperationKind kind() { return kind; }
    public List<Slot> slots() { return slots; }
    public boolean needsValue() { return needsValue; }
    /** The operation takes SEVERAL fields (the projected columns), not one. */
    public boolean multiField() { return multiField; }

    public static OperationSignature of(OperationKind kind) {
        return switch (kind) {
            case FILTER -> new OperationSignature(kind,
                    List.of(new Slot("Field", Need.ANY)), true, false);
            case GROUP_BY_VALUE -> new OperationSignature(kind,
                    List.of(new Slot("Group field", Need.SCALAR)), false, false);
            case GROUP_BY_REFERENCE -> new OperationSignature(kind,
                    List.of(new Slot("Reference field", Need.REFERENCE)), false, false);
            case PROJECT_TO_CLASS -> new OperationSignature(kind,
                    List.of(new Slot("Projected fields", Need.ANY)), false, true);
        };
    }

    /** The single field slot's constraint (all current ops take one field). */
    public Need fieldNeed() {
        return slots.isEmpty() ? Need.ANY : slots.get(0).need();
    }
}
