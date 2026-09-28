package quiz.transform;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A measured relation as rows to look at: the numbers a decision needs, the candidate
 * evidence-bearing measures and the actionable worklist.
 *
 * <p>Three kinds of row, because they are read for three different reasons. A MEASURE
 * answers whether a derived construct is worth building at all — component sizes decide
 * whether grouping means anything, and a maximum out-degree above one says the relation
 * is not the chain someone assumed. Candidate equivalence classes use the shared group
 * panel directly rather than being copied into a second row representation. A FINDING is a single thing
 * somebody can act on — a statement Wikidata holds in one direction only, or a member
 * whose chain continues outside the loaded population.
 *
 * <p>Rows are ordinary Viewables so they render through the shared search and card path
 * with their instances reachable, rather than as a table that would have to reinvent
 * search, links and selection.
 */
public final class RelationProfileRows {
    public static final String MEASURE = "RelationMeasure";
    public static final String FINDING = "RelationFinding";

    private RelationProfileRows() { }

    /** Rows shown by the report and the number of findings they represent. The latter
     *  can exceed the shown rows because expensive graph-break findings are sampled. */
    public record Report(List<Viewable> rows, int totalFindings,
                         List<String> findingCoverage) {
        public Report {
            rows = List.copyOf(rows == null ? List.of() : rows);
            totalFindings = Math.max(0, totalFindings);
            findingCoverage = List.copyOf(
                    findingCoverage == null ? List.of() : findingCoverage);
        }

        public int shownFindings() {
            return (int) rows.stream().filter(
                    row -> row != null && FINDING.equals(row.typeName())).count();
        }

        public String findingsTabTitle() {
            String count = shownFindings() < totalFindings
                    ? shownFindings() + " of " + totalFindings + " shown"
                    : Integer.toString(totalFindings);
            return "Findings (" + count + ")";
        }

        public String findingCoverageText() {
            return findingCoverage.isEmpty()
                    ? "No actionable findings."
                    : String.join("  ·  ", findingCoverage);
        }
    }

    public static List<Viewable> of(String relation, RelationProfile profile) {
        return report(relation, profile, false).rows();
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
        return report(relation, profile, statedSymmetric).rows();
    }

    public static Report report(
            String relation, RelationProfile profile, boolean statedSymmetric) {
        if (profile == null) return new Report(List.of(), 0, List.of());
        String name = relation == null || relation.isBlank()
                ? profile.forwardField() : relation;
        List<Viewable> rows = new ArrayList<>();

        measure(rows, name, "members", profile.members(),
                "the instances the relation was measured over", profile.population());
        measure(rows, name, "edges", profile.edges(),
                "distinct directed statements between two loaded members",
                profile.edgeMembers());
        if (!profile.inverseField().isBlank()) {
            measure(rows, name, "stated by both sides", profile.statedBothWays(),
                    "both fields assert the same edge", profile.statedBothWaysMembers());
            measure(rows, name, "stated by one side only", profile.statedOneWay().size(),
                    "reading a single field would lose these",
                    oneSidedMembers(profile.statedOneWay()));
        }
        measure(rows, name, "reflexive", profile.reflexive(),
                "a member related to itself", profile.reflexiveMembers());
        measure(rows, name, "symmetry breaks", profile.symmetryBreaks(),
                statedSymmetric
                        ? "the catalogue states this property is its own inverse, so "
                                + "each unreciprocated edge is a gap; Findings shows "
                                + sampled(profile.symmetryBreakSamples().size(),
                                        profile.symmetryBreaks())
                        : "a→b is stated but b→a is not — the ordinary shape of a "
                                + "relation nothing states to be symmetric",
                symmetryMembers(profile.symmetryBreakSamples()));
        measure(rows, name, "transitivity breaks", profile.transitivityBreaks(),
                "a→b→c is stated but a→c is not; measured only — no catalogue "
                        + "declaration currently makes these findings",
                transitivityMembers(profile.transitivityBreakSamples()));
        measure(rows, name, "largest out-degree", profile.maxOutDegree(),
                "above one the relation branches, so it is not a chain",
                profile.maxOutDegreeMembers());
        measure(rows, name, "largest in-degree", profile.maxInDegree(),
                "above one the relation merges", profile.maxInDegreeMembers());
        measure(rows, name, "candidate equivalence classes", profile.components().size(),
                "groups formed by following the relation in either direction",
                componentRepresentatives(profile.components()));
        RelationProfile.Component largest = profile.components().stream()
                .max(java.util.Comparator.comparingInt(RelationProfile.Component::size))
                .orElse(null);
        measure(rows, name, "largest candidate equivalence class",
                largest == null ? 0 : largest.size(),
                "one class holding everything means grouping by it says nothing",
                largest == null ? List.of() : largest.members());
        measure(rows, name, "mutually reachable sets", profile.mutuallyReachable().size(),
                statedSymmetric
                        ? "this relation's equivalence classes, which for a symmetric "
                                + "relation are its groups"
                        : "the relation's own equivalence classes: a data error for an "
                                + "order-like relation, where each should be one member",
                flatten(profile.mutuallyReachable()));
        measure(rows, name, "edges leaving the population", profile.danglingEdges(),
                "targets that were never loaded, so their candidate classes are fragments",
                profile.leavingPopulation());

        int index = 0;
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
        int shownFindings = (int) rows.stream()
                .filter(row -> FINDING.equals(row.typeName())).count();
        int omittedSymmetryFindings = statedSymmetric
                ? Math.max(0, profile.symmetryBreaks()
                        - profile.symmetryBreakSamples().size())
                : 0;
        List<String> coverage = new ArrayList<>();
        if (!profile.statedOneWay().isEmpty()) {
            coverage.add("All " + profile.statedOneWay().size()
                    + " one-sided statement findings shown");
        }
        if (statedSymmetric && profile.symmetryBreaks() > 0) {
            int shown = profile.symmetryBreakSamples().size();
            coverage.add((shown < profile.symmetryBreaks() ? shown + " of " : "All ")
                    + profile.symmetryBreaks() + " stated-symmetry findings shown");
        }
        if (!profile.leavingPopulation().isEmpty()) {
            coverage.add("All " + profile.leavingPopulation().size()
                    + " population-boundary member findings shown");
        }
        return new Report(rows, shownFindings + omittedSymmetryFindings, coverage);
    }

    private static String sampled(int shown, int total) {
        return shown < total
                ? shown + " samples of " + total + " gaps"
                : total + (total == 1 ? " gap" : " gaps");
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

    private static void measure(
            List<Viewable> rows, String relation, String measure, int value, String reading) {
        measure(rows, relation, measure, value, reading, List.of());
    }

    private static void measure(List<Viewable> rows, String relation, String measure,
                                int value, String reading,
                                Collection<? extends Viewable> instances) {
        DynamicViewable row = new DynamicViewable(
                relation + "#" + measure, measure);
        row.type(MEASURE);
        row.put("relation", relation);
        row.put("value", value);
        row.put("reading", reading);
        if (instances != null && !instances.isEmpty()) {
            row.put("instances", List.copyOf(instances));
        }
        rows.add(row);
    }

    private static List<Viewable> oneSidedMembers(List<RelationProfile.OneSided> values) {
        LinkedHashSet<Viewable> result = new LinkedHashSet<>();
        values.forEach(value -> { result.add(value.from()); result.add(value.to()); });
        return List.copyOf(result);
    }

    private static List<Viewable> symmetryMembers(List<RelationProfile.SymmetryBreak> values) {
        LinkedHashSet<Viewable> result = new LinkedHashSet<>();
        values.forEach(value -> { result.add(value.from()); result.add(value.to()); });
        return List.copyOf(result);
    }

    private static List<Viewable> transitivityMembers(
            List<RelationProfile.TransitivityBreak> values) {
        LinkedHashSet<Viewable> result = new LinkedHashSet<>();
        values.forEach(value -> {
            result.add(value.from()); result.add(value.through()); result.add(value.to());
        });
        return List.copyOf(result);
    }

    private static List<Viewable> componentRepresentatives(
            List<RelationProfile.Component> components) {
        return components.stream().filter(component -> !component.members().isEmpty())
                .map(component -> component.members().getFirst()).toList();
    }

    private static List<Viewable> flatten(List<List<Viewable>> groups) {
        LinkedHashSet<Viewable> result = new LinkedHashSet<>();
        groups.forEach(result::addAll);
        return List.copyOf(result);
    }
}
