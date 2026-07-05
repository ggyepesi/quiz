package quiz.transform.ui;

import quiz.Quizable;
import quiz.facet.Facet;
import quiz.transform.ClassTransformPlan;
import quiz.transform.View;
import wikidata.explore.extract.WikidataDynamicObject;

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
    public static View compile(String name, String memberType, List<OperationSpec> ops) {
        ClassTransformPlan plan =
                ClassTransformPlan.keeping(WikidataDynamicObject.class);

        if (memberType != null && !memberType.isBlank()) {
            String type = memberType;
            plan.where((Predicate) o ->
                    o instanceof Quizable q && type.equals(q.typeName()));
        }
        for (OperationSpec op : ops) {
            if (op != null && op.kind == OperationKind.FILTER && op.field != null) {
                plan.whereFieldEquals(op.field.field(), op.value);
            }
        }

        View view = new View(name, WikidataDynamicObject.class).plan(plan);

        for (OperationSpec op : ops) {
            if (op == null || op.field == null) {
                continue;
            }
            switch (op.kind) {
                case GROUP_BY_VALUE -> view.groupBy(Facet.field(op.field.field()));
                case GROUP_BY_REFERENCE -> view.groupBy(Facet.reference(op.field.field()));
                default -> { /* FILTER already applied to the plan */ }
            }
        }
        return view;
    }
}
