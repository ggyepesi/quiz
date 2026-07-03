package quiz.transform;

import quiz.Quizable;
import quiz.facet.Facet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The DECLARATIVE, saveable form of a {@link View}: which type's instances to
 * keep, which field filters to apply, and the ordered grouping. Plain fields so
 * it serializes to JSON and can be edited on a config UI; {@link #build} turns it
 * into a runtime {@link View} over a universe class (e.g. the snapshot's
 * {@code WikidataDynamicObject}).
 */
public class ViewSpec {

    public String name = "";
    /** Keep only instances of this typeName (blank = any). */
    public String memberType = "";
    public List<Filter> filters = new ArrayList<>();
    public List<GroupBy> grouping = new ArrayList<>();

    /** A field == value filter (e.g. won == true). */
    public static class Filter {
        public String field = "";
        public Object value;
        public Filter() {}
        public Filter(String field, Object value) { this.field = field; this.value = value; }
    }

    /** One grouping level. mode: VALUE (scalar) or REFERENCE (entity bucket). */
    public static class GroupBy {
        public String field = "";
        public String mode = "VALUE";
        public GroupBy() {}
        public GroupBy(String field, String mode) { this.field = field; this.mode = mode; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public View build(Class<? extends Quizable> universe) {
        ClassTransformPlan plan = ClassTransformPlan.keeping((Class) universe);
        if (memberType != null && !memberType.isBlank()) {
            String type = memberType;
            plan.where((Predicate) o -> o instanceof Quizable q && type.equals(q.typeName()));
        }
        for (Filter f : filters) {
            if (f != null && f.field != null && !f.field.isBlank()) {
                plan.whereFieldEquals(f.field, f.value);
            }
        }
        View view = new View(name, universe).plan(plan);
        for (GroupBy g : grouping) {
            Facet facet = facetFor(g);
            if (facet != null) {
                view.groupBy(facet);
            }
        }
        return view;
    }

    private static Facet facetFor(GroupBy g) {
        if (g == null || g.field == null || g.field.isBlank()) {
            return null;
        }
        return "REFERENCE".equalsIgnoreCase(g.mode)
                ? Facet.reference(g.field)
                : Facet.field(g.field);
    }
}
