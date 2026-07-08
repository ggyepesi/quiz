package quiz.transform;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.QuizableGroup.Role;
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

    // One grouping dimension: the facet, and whether it NESTS under the previous
    // dimension (drill-down) or starts a new INDEPENDENT dimension off the root.
    private record Dim(Facet facet, boolean nested) {}

    private final String name;
    private final Class<? extends Quizable> memberClass;
    private final TransformRunner runner = new TransformRunner();
    private final List<Dim> grouping = new ArrayList<>();

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

    /** Ordered facets, each drilling into the previous (nested by default). */
    public View groupBy(Facet... facets) {
        if (facets != null) {
            for (Facet f : facets) {
                groupBy(f, true);
            }
        }
        return this;
    }

    /** Add one grouping dimension. {@code nested} = drill into the previous
     *  dimension; otherwise start a new INDEPENDENT dimension off the root. */
    public View groupBy(Facet facet, boolean nested) {
        if (facet != null) {
            grouping.add(new Dim(facet, nested));
        }
        return this;
    }

    /** Run the plans over {@code sources}, then group the target members. A run of
     *  nested dimensions forms one drill-down chain; an independent one starts a
     *  new parallel chain off the root. */
    public QuizableGroup render(Iterable<?> sources) {
        List<? extends Quizable> members = members(sources);
        String label = name + "  (" + members.size() + ")";
        if (grouping.isEmpty()) {
            return FacetGrouper.group(label, members, List.of());
        }

        QuizableGroup root = new QuizableGroup(label).role(Role.UNIVERSE);
        for (Quizable m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        List<Facet> chain = new ArrayList<>();
        for (int i = 0; i < grouping.size(); i++) {
            Dim d = grouping.get(i);
            boolean startsChain = i == 0 || !d.nested();
            if (startsChain && !chain.isEmpty()) {
                FacetGrouper.graftNested(root, members, chain);
                chain = new ArrayList<>();
            }
            chain.add(d.facet());
        }
        if (!chain.isEmpty()) {
            FacetGrouper.graftNested(root, members, chain);
        }
        return root;
    }

    /** The projected/filtered member instances (ungrouped). */
    public List<? extends Quizable> members(Iterable<?> sources) {
        TransformContext ctx = runner.run(sources);
        return ctx.targets(memberClass);
    }
}
