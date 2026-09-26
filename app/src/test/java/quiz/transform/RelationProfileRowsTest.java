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
        assertTrue(rows.stream().noneMatch(
                        row -> RelationProfileRows.FINDING.equals(row.typeName())),
                "but a chain with nothing wrong with it offers no work");
        assertEquals(List.of(), profile.findingWitnesses(),
                "and no witness sections for findings that were not offered");
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
