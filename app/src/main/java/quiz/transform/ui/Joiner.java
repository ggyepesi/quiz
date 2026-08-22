package quiz.transform.ui;

import objectview.Viewable;
import quiz.transform.DynamicViewable;
import objectview.field.FieldAccess;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import domain.DomainField;
import domain.DomainModel;

/**
 * Equi-joins two classes on a key value: each LEFT instance is matched to a RIGHT
 * instance where {@code left.leftKey == right.rightKey}, producing a NEW class whose
 * instances reference BOTH sides (fields named after each type). Via nested paths
 * the joined class then exposes both sides' fields ({@code order.total},
 * {@code customer.name}). This is the multi-argument, cross-class operation — its
 * arguments come from two different classes.
 *
 * <p>Keys match on identity for a referenced Viewable, else on string value — so a
 * value join and a reference join both work.
 */
public final class Joiner {

    private Joiner() {}

    public static DerivedClass join(DomainModel domain, String newType,
                                    String leftType, String leftKey,
                                    String rightType, String rightKey) {
        String leftField = leftType.toLowerCase();
        String rightField = rightType.toLowerCase();

        Map<String, Viewable> rightByKey = new HashMap<>();
        for (Viewable r : domain.instances()) {
            if (r != null && domain.isInstanceOf(r, rightType)) {
                String k = keyOf(FieldAccess.getPath(r, rightKey));
                if (k != null) {
                    rightByKey.putIfAbsent(k, r);
                }
            }
        }

        List<Viewable> out = new ArrayList<>();
        for (Viewable l : domain.instances()) {
            if (l == null || !domain.isInstanceOf(l, leftType)) {
                continue;
            }
            String k = keyOf(FieldAccess.getPath(l, leftKey));
            Viewable match = k == null ? null : rightByKey.get(k);

            DynamicViewable o = new DynamicViewable(
                    l.getIdentifier() + (match == null ? "" : "+" + match.getIdentifier()),
                    l.getDisplayName() + (match == null ? "" : "  ×  " + match.getDisplayName()));
            o.type(newType);
            o.put(leftField, l);
            if (match != null) {
                o.put(rightField, match);
            }
            out.add(o);
        }

        List<DomainField> fields = new ArrayList<>();
        fields.add(new DomainField(newType, leftField, true, false));
        fields.add(new DomainField(newType, rightField, true, false));
        List<FieldRef> refs = List.of(
                FieldRef.described(leftField, FieldKind.REFERENCE,
                        FieldKind.REFERENCE, leftType, true, false,
                        leftType, false, false,
                        false, false, "", false),
                FieldRef.described(rightField, FieldKind.REFERENCE,
                        FieldKind.REFERENCE, rightType, true, false,
                        rightType, false, false,
                        false, false, "", false));
        FieldSchema fieldSchema = () -> refs;
        return new DerivedClass(newType, fields, out, fieldSchema);
    }

    /** Match key: a referenced entity by identity, else the string value. */
    private static String keyOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Viewable q) {
            return q.getIdentifier();
        }
        return String.valueOf(v);
    }
}
