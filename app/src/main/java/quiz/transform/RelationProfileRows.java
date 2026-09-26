package quiz.transform;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
        return of(relation, profile, false);
    }

    /**
     * @param statedSymmetric the catalogue says the relation is symmetric — a property
     *                        naming itself as its own inverse through P1696, which
     *                        sibling and spouse both do. Only then is an unreciprocated
     *                        edge a gap somebody can close; for a succession it is the
     *                        ordinary shape of the relation.
     */
    public static List<Viewable> of(
            String relation, RelationProfile profile, boolean statedSymmetric) {
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
        measure(rows, name, "symmetry breaks", profile.symmetryBreaks(),
                statedSymmetric
                        ? "the catalogue states this property is its own inverse, so "
                                + "each unreciprocated edge is a gap: see Findings"
                        : "a→b is stated but b→a is not — the ordinary shape of a "
                                + "relation nothing states to be symmetric");
        measure(rows, name, "transitivity breaks", profile.transitivityBreaks(),
                "a→b→c is stated but a→c is not; samples are in Findings");
        measure(rows, name, "largest out-degree", profile.maxOutDegree(),
                "above one the relation branches, so it is not a chain");
        measure(rows, name, "largest in-degree", profile.maxInDegree(),
                "above one the relation merges");
        measure(rows, name, "components", profile.components().size(),
                "candidate groups, if grouping by this relation means anything");
        measure(rows, name, "largest component", profile.largestComponent(),
                "one component holding everything means grouping by it says nothing");
        measure(rows, name, "mutually reachable sets", profile.mutuallyReachable().size(),
                statedSymmetric
                        ? "this relation's equivalence classes, which for a symmetric "
                                + "relation are its groups"
                        : "the relation's own equivalence classes: a data error for an "
                                + "order-like relation, where each should be one member");
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

        // A break is a finding only where something states the relation should hold.
        // Over History's replaces ⇄ replacedBy, 81 of 85 edges "break symmetry" and 30
        // break transitivity, and every one of those edits would be wrong: a reciprocal
        // succession is a cycle, a transitive one asserts a succession that never
        // happened. The catalogue states symmetry by naming a property its own inverse,
        // so sibling and spouse get these findings and a succession does not.
        // Transitivity is stated by a P2302 constraint the catalogue does not carry yet
        // (#286), so its samples stay evidence for the measure and nothing more.
        index = 0;
        if (statedSymmetric) {
            for (RelationProfile.SymmetryBreak broken : profile.symmetryBreakSamples()) {
                DynamicViewable row = new DynamicViewable(
                        name + "#symmetry-" + index++, "Missing reverse edge");
                row.type(FINDING);
                row.put("relation", name);
                row.put("finding", "breaks stated symmetry");
                row.put("from", broken.from());
                row.put("to", broken.to());
                row.put("missing", broken.to().getDisplayName() + " → "
                        + broken.from().getDisplayName());
                rows.add(row);
            }
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
     * The loaded instances the offered findings point at, in the order they are offered.
     *
     * <p>Read back off the rows rather than recomputed, so it cannot claim a witness for
     * a finding nobody was given. The two did disagree: a witness list assembled beside
     * the rows kept naming the ends of symmetry samples after those stopped being
     * findings, and every one of them opened a section for work that was never offered.
     */
    public static List<Viewable> witnesses(Collection<? extends Viewable> rows) {
        LinkedHashSet<Viewable> found = new LinkedHashSet<>();
        for (Viewable row : rows == null ? List.<Viewable>of() : rows) {
            if (!FINDING.equals(row.typeName())
                    || !(row instanceof DynamicViewable dynamic)) continue;
            for (Object value : dynamic.dynamicFieldValues().values()) {
                if (value instanceof Viewable witness) found.add(witness);
            }
        }
        return List.copyOf(found);
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
