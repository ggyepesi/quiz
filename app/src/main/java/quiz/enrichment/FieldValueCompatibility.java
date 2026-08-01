package quiz.enrichment;

import objectview.Viewable;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.media.ImagePane;
import objectview.media.MediaValue;

import java.util.Collection;

/** Schema-first compatibility check for a proposed external field value. */
public final class FieldValueCompatibility {
    private FieldValueCompatibility() {}

    /** Null when compatible; otherwise a user-facing explanation. */
    public static String problem(FieldRef target, Object proposed) {
        if (target == null || proposed == null) return null;
        if (!target.collection() && proposed instanceof Collection<?>) {
            return sourceShape(proposed) + " cannot populate scalar " + target.typeLabel();
        }
        if (target.collection() && proposed instanceof Collection<?> values) {
            for (Object value : values) {
                String problem = scalarProblem(target, value);
                if (problem != null) return problem;
            }
            return null;
        }
        return scalarProblem(target, proposed);
    }

    private static String scalarProblem(FieldRef target, Object value) {
        if (value == null) return null;
        FieldKind expected = target.collection() ? target.valueKind() : target.kind();
        boolean compatible = switch (expected) {
            case MEDIA -> value instanceof MediaValue || value instanceof ImagePane;
            case REFERENCE -> value instanceof Viewable;
            case BOOLEAN -> value instanceof Boolean;
            case ORDERED -> value instanceof Number || value instanceof CharSequence
                    || value instanceof Comparable<?>;
            case TEXT -> value instanceof CharSequence || value instanceof Character
                    || value instanceof Number || value instanceof Boolean;
            case COLLECTION -> value instanceof Collection<?>;
            case UNKNOWN -> true;
        };
        if (compatible && expected == FieldKind.REFERENCE
                && target.targetType() != null && !target.targetType().isBlank()
                && value instanceof Viewable viewable
                && !target.targetType().equals(viewable.typeName())) {
            return "Reference " + viewable.typeName() + " is not a " + target.targetType();
        }
        if (compatible) return null;
        String expectedLabel = target.collection()
                ? "element of " + target.typeLabel() : target.typeLabel();
        return sourceShape(value) + " is incompatible with " + expectedLabel
                + " (expected " + expected + ")";
    }

    private static String sourceShape(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
