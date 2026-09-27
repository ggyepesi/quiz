package quiz.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report is read for three different reasons, so it has three kinds of row: the
 * numbers a decision needs, the candidate groups, and the things somebody can act on.
 *
 * <p>What it must never do is choose. Showing that a component has one unique end is
 * what tells a modeller a naming rule is available; picking that end here would make the
 * report an author of the grouping it exists to inform.
 */
class RelationProfileRowsTest {

    @Test void aBranchingRelationSaysNoEndCanNameTheComponent() {
        DynamicViewable surviving = office("survivor", "Spanish ambassador to the Reich");
        DynamicViewable bavaria = office("bavaria", "Spanish envoy in Bavaria");
        DynamicViewable saxony = office("saxony", "Spanish envoy in Saxony");
        surviving.put("replaces", List.of(bavaria, saxony));
        bavaria.put("replacedBy", List.of(surviving));
        saxony.put("replacedBy", List.of(surviving));

        List<Viewable> rows = RelationProfileRows.of("replaces ⇄ replacedBy",
                RelationProfile.of(List.of(surviving, bavaria, saxony),
                        "replaces", "replacedBy"));

        assertEquals(2, value(rows, "largest out-degree"),
                "one office absorbing two is not a chain, and the report says so");
        assertEquals(1, value(rows, "components"));
        Viewable component = rowOfType(rows, RelationProfileRows.COMPONENT);
        assertEquals("Component of 3", component.getDisplayName());
        assertEquals("only the origin is unique", field(component, "namingRule"));
        assertEquals(true, field(component, "complete"));
    }

    @Test void aOneSidedStatementBecomesARowNamingTheEditToMake() {
        DynamicViewable later = office("later", "Later office");
        DynamicViewable earlier = office("earlier", "Earlier office");
        later.put("replaces", List.of(earlier));

        List<Viewable> rows = RelationProfileRows.of("succession",
                RelationProfile.of(List.of(later, earlier), "replaces", "replacedBy"));

        Viewable finding = rowOfType(rows, RelationProfileRows.FINDING);
        assertEquals("Stated only by replaces", finding.getDisplayName());
        assertEquals("replacedBy", field(finding, "missing"));
        assertEquals(later, field(finding, "from"));
        assertEquals(earlier, field(finding, "to"),
                "an instance to open, not a number to wonder about");
    }

    @Test void aMemberWhoseChainLeavesThePopulationIsItsOwnFinding() {
        DynamicViewable inside = office("inside", "Loaded office");
        inside.put("replaces", List.of(office("outside", "Never loaded")));

        List<Viewable> rows = RelationProfileRows.of("succession",
                RelationProfile.of(List.of(inside), "replaces", "replacedBy"));

        Viewable finding = rowOfType(rows, RelationProfileRows.FINDING);
        assertEquals("leaves the loaded population", field(finding, "finding"));
        assertEquals(inside, field(finding, "member"));
        assertEquals(1, value(rows, "edges leaving the population"));
    }

    /**
     * A chain breaks symmetry at every edge and transitivity at every second one, and
     * neither is work to do: reciprocating a succession makes a cycle, and closing it
     * transitively asserts a succession that never happened. Over History's
     * replaces ⇄ replacedBy that was 81 of 85 edges plus 30 more, all offered as edits.
     *
     * <p>So they are measured and not offered. When the catalogue states the property
     * should hold — P1696 naming a property its own inverse, a P2302 transitivity
     * constraint — the same samples become findings; until then nothing does.
     */
    @Test void aBreakIsMeasuredButNotOfferedAsSomethingToDo() {
        DynamicViewable a = office("a", "A");
        DynamicViewable b = office("b", "B");
        DynamicViewable c = office("c", "C");
        a.put("next", List.of(b));
        b.put("next", List.of(c));

        RelationProfile profile = RelationProfile.of(List.of(a, b, c), "next", "");
        List<Viewable> rows = RelationProfileRows.of("next", profile);

        assertEquals(2, value(rows, "symmetry breaks"));
        assertEquals(1, value(rows, "transitivity breaks"));
        assertEquals(2, profile.symmetryBreakSamples().size(),
                "the evidence for the measure is kept");
        assertEquals(1, profile.transitivityBreakSamples().size());
        Viewable transitivity = rows.stream()
                .filter(row -> "transitivity breaks".equals(row.getDisplayName()))
                .findFirst().orElseThrow();
        assertEquals("a→b→c is stated but a→c is not; measured only — no catalogue "
                        + "declaration currently makes these findings",
                field(transitivity, "reading"));
        assertTrue(rows.stream().noneMatch(
                        row -> RelationProfileRows.FINDING.equals(row.typeName())),
                "but a chain with nothing wrong with it offers no work");
        assertEquals(List.of(), RelationProfileRows.witnesses(rows),
                "and no witness sections for findings that were not offered");
    }

    @Test void aSampledFindingCountSaysHowManyAreShownAndHowManyExist() {
        java.util.ArrayList<Viewable> members = new java.util.ArrayList<>();
        for (int i = 0; i < 52; i++) {
            DynamicViewable from = office("from-" + i, "From " + i);
            DynamicViewable to = office("to-" + i, "To " + i);
            from.put("siblings", List.of(to));
            members.add(from);
            members.add(to);
        }

        RelationProfileRows.Report report = RelationProfileRows.report(
                "siblings", RelationProfile.of(members, "siblings", ""), true);

        assertEquals(50, report.shownFindings(), "the configured witness sample");
        assertEquals(52, report.totalFindings(), "the complete actionable count");
        assertEquals("Findings (50 of 52 shown)", report.findingsTabTitle(),
                "the tab must not present the sample size as the total");
        Viewable symmetry = report.rows().stream()
                .filter(row -> "symmetry breaks".equals(row.getDisplayName()))
                .findFirst().orElseThrow();
        assertEquals("the catalogue states this property is its own inverse, so each "
                        + "unreciprocated edge is a gap; Findings shows 50 samples of 52 gaps",
                field(symmetry, "reading"));
    }

    /**
     * The same measurement, the same numbers, and work offered only where the catalogue
     * says the relation was meant to hold. Sibling states itself as its own inverse, so
     * an unreciprocated edge is a gap somebody closes.
     */
    @Test void aStatedSymmetricRelationOffersItsBreaksAsWork() {
        DynamicViewable ann = office("a", "Ann");
        DynamicViewable bo = office("b", "Bo");
        ann.put("siblings", List.of(bo));

        RelationProfile profile = RelationProfile.of(List.of(ann, bo), "siblings", "");
        List<Viewable> stated = RelationProfileRows.of("siblings", profile, true);
        List<Viewable> unstated = RelationProfileRows.of("siblings", profile, false);

        Viewable finding = rowOfType(stated, RelationProfileRows.FINDING);
        assertEquals("breaks stated symmetry", field(finding, "finding"));
        assertEquals(ann, field(finding, "from"));
        assertEquals(bo, field(finding, "to"));
        assertEquals("Bo → Ann", field(finding, "missing"));
        assertEquals(List.of(ann, bo), RelationProfileRows.witnesses(stated));

        assertTrue(unstated.stream().noneMatch(
                        row -> RelationProfileRows.FINDING.equals(row.typeName())),
                "and nothing is offered where nothing states symmetry");
        assertEquals(1, value(stated, "symmetry breaks"));
        assertEquals(1, value(unstated, "symmetry breaks"),
                "the measure is the same either way; only its reading differs");
    }

    /**
     * Witnessed by what was offered. A witness list assembled beside the rows kept
     * naming the ends of symmetry samples after those stopped being findings, opening a
     * section for work nobody was given — so it is read back off the rows instead.
     */
    @Test void theWitnessesAreTheInstancesTheOfferedFindingsPointAt() {
        DynamicViewable later = office("later", "Later office");
        DynamicViewable earlier = office("earlier", "Earlier office");
        DynamicViewable leaving = office("leaving", "Leaves the population");
        DynamicViewable reciprocated = office("fine", "Reciprocated both ways");
        DynamicViewable itsPredecessor = office("pred", "Its predecessor");
        later.put("replaces", List.of(earlier));
        leaving.put("replaces", List.of(office("out", "Never loaded")));
        reciprocated.put("replaces", List.of(itsPredecessor));
        itsPredecessor.put("replacedBy", List.of(reciprocated));

        List<Viewable> rows = RelationProfileRows.of("succession", RelationProfile.of(
                List.of(later, earlier, leaving, reciprocated, itsPredecessor),
                "replaces", "replacedBy"));

        assertEquals(List.of(later, earlier, leaving), RelationProfileRows.witnesses(rows),
                "taking part in the relation is not enough to be a witness");
    }

    @Test void singletonsAreNotOfferedAsGroups() {
        List<Viewable> rows = RelationProfileRows.of("succession",
                RelationProfile.of(List.of(office("a", "A"), office("b", "B")),
                        "replaces", "replacedBy"));

        assertTrue(rows.stream().noneMatch(
                        row -> RelationProfileRows.COMPONENT.equals(row.typeName())),
                "a member related to nothing is not a candidate group");
        assertEquals(2, value(rows, "components"),
                "while the measure still counts them, because that is the ratio that "
                        + "says whether the relation partitions anything");
    }

    private static int value(List<Viewable> rows, String measure) {
        return (int) rows.stream()
                .filter(row -> RelationProfileRows.MEASURE.equals(row.typeName()))
                .filter(row -> measure.equals(row.getDisplayName()))
                .map(row -> field(row, "value"))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + measure));
    }

    private static Viewable rowOfType(List<Viewable> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.typeName()))
                .findFirst().orElseThrow(() -> new AssertionError("no " + type + " row"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Viewable row, String name) {
        return (T) ((Map<String, Object>) ((objectview.field.DynamicFields) row)
                .dynamicFieldValues()).get(name);
    }

    private static DynamicViewable office(String id, String name) {
        DynamicViewable value = new DynamicViewable(id, name);
        value.type("Position");
        return value;
    }
}
