package quiz.transform;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.facet.FacetGrouper;

import java.util.ArrayList;
import java.util.List;

/**
 * A saveable VIEW over a domain's instances: one or more {@link ClassTransformPlan}s
 * (project / filter / invert) plus a nested grouping. {@link #render} runs the
 * plans over the domain instances and groups the chosen target class — e.g.
 * "Oscar winners per category per year".
 *
 * <pre>{@code
 * new View("Oscar winners", WikidataDynamicObject.class)
 *     .plan(new ClassTransformPlan<>(WDO.class, WDO.class)
 *             .whereFieldEquals("won", true)
 *             .copy("name", "name")            // display name
 *             .copy("category", "category")
 *             .copy("year", "year"))
 *     .groupBy(Facet.reference("category"), Facet.field("year"))
 *     .render(snapshotInstances);
 * }</pre>
 *
 * <p>This is the "produce new structures from a domain's instances" layer standing
 * on its own — no model builder, computed at presentation, not baked into the data.
 */
public final class View {

    private final String name;
    private final Class<? extends Quizable> memberClass;
    private final TransformRunner runner = new TransformRunner();
    private final List<Facet> grouping = new ArrayList<>();

    /** @param memberClass the target class whose instances are the grouped members. */
    public View(String name, Class<? extends Quizable> memberClass) {
        this.name = name == null ? "" : name;
        this.memberClass = memberClass;
    }

    public View plan(ClassTransformPlan<?, ?> plan) {
        if (plan != null) {
            runner.add(plan);
        }
        return this;
    }

    /** Ordered facets: each partitions the buckets of the previous (drill-down). */
    public View groupBy(Facet... facets) {
        if (facets != null) {
            for (Facet f : facets) {
                if (f != null) {
                    grouping.add(f);
                }
            }
        }
        return this;
    }

    /** Run the plans over {@code sources}, then group the target members. */
    public QuizableGroup render(Iterable<?> sources) {
        List<? extends Quizable> members = members(sources);
        String label = name + "  (" + members.size() + ")";
        return grouping.isEmpty()
                ? FacetGrouper.group(label, members, List.of())
                : FacetGrouper.groupNested(label, members, grouping);
    }

    /** The projected/filtered member instances (ungrouped). */
    public List<? extends Quizable> members(Iterable<?> sources) {
        TransformContext ctx = runner.run(sources);
        return ctx.targets(memberClass);
    }
}
