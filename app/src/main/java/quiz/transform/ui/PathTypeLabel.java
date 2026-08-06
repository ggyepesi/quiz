package quiz.transform.ui;

import objectview.field.FieldKind;
import objectview.field.FieldPath;
import objectview.viewconfig.FieldTypeSource;

import java.util.Map;
import java.util.function.Function;

/**
 * Builds the Type-column label for the flat field picker / coverage table
 * ({@code ViewConfigEditor.setPathRows}). The editor lives in the lower lib and can't
 * see a {@link DomainField}, so the label is computed here, per dotted path, from the
 * best source available:
 *
 * <ol>
 *   <li>a compiled model's {@link FieldTypeSource} (walked down the path) — the exact
 *       class/type label for Wikidata domains;</li>
 *   <li>else the {@link DomainField}'s own shape — a reflection domain (Nobel, State):
 *       the value kind, {@code Image} for a media field, or a ref/collection hint.</li>
 * </ol>
 */
public final class PathTypeLabel {

    private PathTypeLabel() { }

    public static Function<FieldPath, String> of(Map<FieldPath, DomainField> byPath,
                                              FieldTypeSource types) {
        return path -> {
            String modelled = walk(types, path);
            if (modelled != null && !modelled.isBlank()) {
                return modelled;
            }
            DomainField f = byPath.get(path);
            if (f == null) {
                return null;
            }
            String kind = kindLabel(f.kind());
            if (kind != null) {
                return kind;
            }
            if (f.reference()) {
                return f.collection() ? "ref[]" : "ref";
            }
            if (f.collection()) {
                return "[]";
            }
            return null;
        };
    }

    /** Walk the nested type sources down {@code seg} to the field's type label, or null
     *  when no compiled type info resolves (a reflection domain). */
    private static String walk(FieldTypeSource types, FieldPath path) {
        FieldTypeSource level = types;
        FieldTypeSource.FieldTypeInfo info = null;
        for (String s : path.segments()) {
            if (level == null) {
                return null;
            }
            info = level.field(s);
            if (info == null) {
                return null;
            }
            level = info.nested();
        }
        return info == null ? null : info.typeLabel();
    }

    private static String kindLabel(FieldKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case BOOLEAN -> "Boolean";
            case ORDERED -> "Number";
            case TEXT -> "String";
            case MEDIA -> "Image";
            default -> null;
        };
    }
}
