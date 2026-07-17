package quiz.transform;

import quiz.Quizable;
import quiz.QuizableGroup;
import objectview.ViewableGroup.Role;
import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import objectview.facet.FacetTree;
import objectview.facet.FacetTreeBuilder;

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

    // The grouping as a dimension TREE: top-level entries are dimensions off the
    // root; a node's children are sub-dimensions within each of its buckets.
    private final List<FacetTreeBuilder<Quizable>> grouping = new ArrayList<>();
    // The last node added by the linear groupBy(...) API, so a nested facet becomes
    // its child (a drill-down chain); groupTree(...) sets the tree explicitly.
    private FacetTreeBuilder<Quizable> currentTip;

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
    @SafeVarargs
    public final View groupBy(Facet<Quizable>... facets) {
        if (facets != null) {
            for (Facet<Quizable> f : facets) {
                groupBy(f, true);
            }
        }
        return this;
    }

    /** Add one grouping dimension. {@code nested} = drill into the previous
     *  dimension (its child); otherwise start a new dimension off the root. */
    public View groupBy(Facet<Quizable> facet, boolean nested) {
        if (facet != null) {
            FacetTreeBuilder<Quizable> node = new FacetTreeBuilder<>(facet);
            if (nested && currentTip != null) {
                currentTip.children().add(node);
            } else {
                grouping.add(node);
            }
            currentTip = node;
        }
        return this;
    }

    /** Set the grouping as an explicit dimension TREE: top-level entries are
     *  dimensions off the root; sibling children are parallel sub-dimensions within
     *  a bucket, and a lone child is a nested drill-down. */
    public View groupTree(List<FacetTree<Quizable>> dims) {
        if (dims != null) {
            for (FacetTree<Quizable> d : dims) {
                grouping.add(FacetTreeBuilder.from(d));
            }
            currentTip = null;
        }
        return this;
    }

    /** Run the plans over {@code sources}, then group the target members by the
     *  dimension tree — sibling dimensions fan out in parallel, a child drills down
     *  within each bucket of its parent. */
    public QuizableGroup render(Iterable<?> sources) {
        List<? extends Quizable> members = members(sources);
        String label = name + "  (" + members.size() + ")";
        if (grouping.isEmpty()) {
            return FacetGrouper.group(QuizableGroup::new, label, members, List.of());
        }

        QuizableGroup root = new QuizableGroup(label).role(Role.UNIVERSE);
        for (Quizable m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        FacetGrouper.graftTree(root, members, FacetTreeBuilder.buildAll(grouping));
        return root;
    }

    /** The projected/filtered member instances (ungrouped). */
    public List<? extends Quizable> members(Iterable<?> sources) {
        TransformContext ctx = runner.run(sources);
        return ctx.targets(memberClass);
    }
}
