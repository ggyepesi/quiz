package quiz.transform;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.List;

/**
 * A measured relation as rows to look at: the numbers a decision needs, the candidate
 * groups, and the two worklists.
 *
 * <p>Three kinds of row, because they are read for three different reasons. A MEASURE
 * answers whether a derived construct is worth building at all — component sizes decide
 * whether grouping means anything, and a maximum out-degree above one says the relation
 * is not the chain someone assumed. A COMPONENT is a candidate equivalence class, shown
 * with its ends so it is visible whether a representative rule like "the last office" is
 * even well defined for it; the report never picks one. A FINDING is a single thing
 * somebody can act on — a statement Wikidata holds in one direction only, or a member
 * whose chain continues outside the loaded population.
 *
 * <p>Rows are ordinary Viewables so they render through the shared search and card path
 * with their instances reachable, rather than as a table that would have to reinvent
 * search, links and selection.
 */
public final class RelationProfileRows {
    public static final String MEASURE = "RelationMeasure";
    public static final String COMPONENT = "RelationComponent";
    public static final String FINDING = "RelationFinding";

    private RelationProfileRows() { }

    public static List<Viewable> of(String relation, RelationProfile profile) {
        if (profile == null) return List.of();
        String name = relation == null || relation.isBlank()
                ? profile.forwardField() : relation;
        List<Viewable> rows = new ArrayList<>();

        measure(rows, name, "members", profile.members(),
                "the instances the relation was measured over");
        measure(rows, name, "edges", profile.edges(),
                "distinct directed statements between two loaded members");
        if (!profile.inverseField().isBlank()) {
            measure(rows, name, "stated by both sides", profile.statedBothWays(),
                    "both fields assert the same edge");
            measure(rows, name, "stated by one side only", profile.statedOneWay().size(),
                    "reading a single field would lose these");
        }
        measure(rows, name, "reflexive", profile.reflexive(),
                "a member related to itself");
        measure(rows, name, "mutual pairs", profile.mutualPairs(),
                "both directions between the same two members: symmetry");
        measure(rows, name, "transitivity gaps", profile.transitivityGaps(),
                "a→b→c with a→c unstated; a chain is expected to have many");
        measure(rows, name, "largest out-degree", profile.maxOutDegree(),
                "above one the relation branches, so it is not a chain");
        measure(rows, name, "largest in-degree", profile.maxInDegree(),
                "above one the relation merges");
        measure(rows, name, "components", profile.components().size(),
                "candidate groups, if grouping by this relation means anything");
        measure(rows, name, "largest component", profile.largestComponent(),
                "one component holding everything means grouping by it says nothing");
        measure(rows, name, "mutually reachable sets", profile.mutuallyReachable().size(),
                "the relation's own equivalence classes: its groups when it is "
                        + "symmetric, a data error when it is an order");
        measure(rows, name, "edges leaving the population", profile.danglingEdges(),
                "targets that were never loaded, so their components are fragments");

        int index = 0;
        for (RelationProfile.Component component : profile.components()) {
            if (component.size() < 2) continue;      // a singleton is not a group
            DynamicViewable row = new DynamicViewable(
                    name + "#component-" + index++, "Component of " + component.size());
            row.type(COMPONENT);
            row.put("relation", name);
            row.put("size", component.size());
            row.put("members", component.members());
            row.put("origins", component.origins());
            row.put("terminals", component.terminals());
            row.put("namingRule", namingRule(component));
            row.put("complete", !component.touchesBoundary());
            rows.add(row);
        }

        index = 0;
        for (RelationProfile.OneSided oneSided : profile.statedOneWay()) {
            String stated = oneSided.forwardOnly()
                    ? profile.forwardField() : profile.inverseField();
            String missing = oneSided.forwardOnly()
                    ? profile.inverseField() : profile.forwardField();
            DynamicViewable row = new DynamicViewable(
                    name + "#one-sided-" + index++, "Stated only by " + stated);
            row.type(FINDING);
            row.put("relation", name);
            row.put("finding", "one direction stated");
            row.put("from", oneSided.from());
            row.put("to", oneSided.to());
            row.put("missing", missing);
            rows.add(row);
        }

        index = 0;
        for (Viewable member : profile.leavingPopulation()) {
            DynamicViewable row = new DynamicViewable(
                    name + "#leaves-" + index++, "Continues outside the population");
            row.type(FINDING);
            row.put("relation", name);
            row.put("finding", "leaves the loaded population");
            row.put("member", member);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /**
     * Which end could name this component — stated as what is available, not as a choice.
     * Exactly one origin or one terminal makes a rule usable; several make it arbitrary.
     */
    private static String namingRule(RelationProfile.Component component) {
        boolean origin = component.origins().size() == 1;
        boolean terminal = component.terminals().size() == 1;
        if (origin && terminal) return "either end is unique";
        if (origin) return "only the origin is unique";
        if (terminal) return "only the terminal is unique";
        return "neither end is unique";
    }

    private static void measure(
            List<Viewable> rows, String relation, String measure, int value, String reading) {
        DynamicViewable row = new DynamicViewable(
                relation + "#" + measure, measure);
        row.type(MEASURE);
        row.put("relation", relation);
        row.put("value", value);
        row.put("reading", reading);
        rows.add(row);
    }
}
