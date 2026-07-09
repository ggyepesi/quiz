package quiz.transform.ui;

import quiz.Quizable;
import quiz.facet.Facet;
import quiz.facet.FacetTree;
import quiz.transform.ClassTransformPlan;
import quiz.transform.View;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterPredicates;

import java.util.ArrayList;
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
        // FILTER: AND each condition into the plan, operator-aware (equals,
        // contains, <, between, is-empty, …), evaluated by FilterPredicates.
        for (OperationSpec op : ops) {
            if (op == null || op.kind != OperationKind.FILTER) {
                continue;
            }
            if (op.conditions != null && !op.conditions.isEmpty()) {
                for (FilterCondition c : op.conditions) {
                    if (c.field() != null) {
                        plan.where((Predicate) o -> FilterPredicates.matches(o, c));
                    }
                }
            } else if (op.field != null) {
                plan.whereFieldEquals(op.field.field(), op.value);
            }
        }

        View view = new View(name, universe).plan(plan);

        // GROUP_BY: rebuild the dimension TREE from the pre-order (facet, depth)
        // sequence — depth 0 is a dimension off the root, depth d nests under the
        // last group at depth d-1. A reference field keys by the entity (invert),
        // a scalar by its value.
        List<FacetTree> dims = new ArrayList<>();
        List<FacetTree> path = new ArrayList<>();   // path.get(d) = open ancestor at depth d
        for (OperationSpec op : ops) {
            if (op == null || op.kind != OperationKind.GROUP_BY || op.field == null) {
                continue;
            }
            FacetTree node = new FacetTree(op.field.reference()
                    ? Facet.reference(op.field.field())
                    : Facet.field(op.field.field()));
            int depth = Math.max(0, Math.min(op.depth, path.size()));
            if (depth == 0) {
                dims.add(node);
            } else {
                path.get(depth - 1).children().add(node);
            }
            while (path.size() > depth) {
                path.remove(path.size() - 1);
            }
            path.add(node);
        }
        view.groupTree(dims);

        return view;
    }
}
