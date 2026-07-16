package quiz.transform.ui;

import quiz.Quizable;
import quiz.transform.DynamicQuizable;
import objectview.field.FieldAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Equi-joins two classes on a key value: each LEFT instance is matched to a RIGHT
 * instance where {@code left.leftKey == right.rightKey}, producing a NEW class whose
 * instances reference BOTH sides (fields named after each type). Via nested paths
 * the joined class then exposes both sides' fields ({@code order.total},
 * {@code customer.name}). This is the multi-argument, cross-class operation — its
 * arguments come from two different classes.
 *
 * <p>Keys match on identity for a referenced Quizable, else on string value — so a
 * value join and a reference join both work.
 */
public final class Joiner {

    private Joiner() {}

    public static DerivedClass join(DomainModel domain, String newType,
                                    String leftType, String leftKey,
                                    String rightType, String rightKey) {
        String leftField = leftType.toLowerCase();
        String rightField = rightType.toLowerCase();

        Map<String, Quizable> rightByKey = new HashMap<>();
        for (Quizable r : domain.instances()) {
            if (r != null && rightType.equals(r.typeName())) {
                String k = keyOf(FieldAccess.getPath(r, rightKey));
                if (k != null) {
                    rightByKey.putIfAbsent(k, r);
                }
            }
        }

        List<Quizable> out = new ArrayList<>();
        for (Quizable l : domain.instances()) {
            if (l == null || !leftType.equals(l.typeName())) {
                continue;
            }
            String k = keyOf(FieldAccess.getPath(l, leftKey));
            Quizable match = k == null ? null : rightByKey.get(k);

            DynamicQuizable o = new DynamicQuizable(
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
        return new DerivedClass(newType, fields, out);
    }

    /** Match key: a referenced entity by identity, else the string value. */
    private static String keyOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Quizable q) {
            return q.getIdentifier();
        }
        return String.valueOf(v);
    }
}
