package quiz.transform.ui;

import quiz.Quizable;
import quiz.facet.Facet;
import quiz.transform.ClassTransformPlan;
import quiz.transform.View;

import java.util.List;
import java.util.function.Predicate;

/**
 * Compiles a transform pipeline — a member class plus an ordered list of
 * {@link OperationSpec}s — into a runnable {@link View} over the snapshot pool.
 * FILTERs become {@link ClassTransformPlan} predicates; GROUP operations become
 * ordered {@link Facet}s (a reference facet is the invert — a member lands under
 * each referenced entity). The grouped result IS the derived subdomain.
 */
public final class ViewCompiler {

    private ViewCompiler() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static View compile(String name, String memberType, List<OperationSpec> ops,
                               Class<? extends Quizable> universe) {
        ClassTransformPlan plan = ClassTransformPlan.keeping((Class) universe);

        if (memberType != null && !memberType.isBlank()) {
            String type = memberType;
            plan.where((Predicate) o ->
                    o instanceof Quizable q && type.equals(q.typeName()));
        }
        for (OperationSpec op : ops) {
            if (op == null || op.kind != OperationKind.FILTER) {
                continue;
            }
            // A FILTER is a predicate: AND all its conditions into the plan.
            if (op.conditions != null && !op.conditions.isEmpty()) {
                for (FilterCondition c : op.conditions) {
                    if (c.field() != null) {
                        plan.whereFieldEquals(c.field().field(), c.value());
                    }
                }
            } else if (op.field != null) {
                plan.whereFieldEquals(op.field.field(), op.value);
            }
        }

        View view = new View(name, universe).plan(plan);

        for (OperationSpec op : ops) {
            if (op == null || op.field == null) {
                continue;
            }
            switch (op.kind) {
                // One "Group by": a reference field keys by the entity (invert),
                // a scalar by its value — chosen from the field's shape.
                case GROUP_BY -> view.groupBy(op.field.reference()
                        ? Facet.reference(op.field.field())
                        : Facet.field(op.field.field()));
                default -> { /* FILTER already applied to the plan */ }
            }
        }
        return view;
    }
}
