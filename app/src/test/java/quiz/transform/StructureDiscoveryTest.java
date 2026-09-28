package quiz.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.transform.ui.SharedNeighbourGraphProjection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The first structure pipeline keeps every intermediate result inspectable. */
class StructureDiscoveryTest {

    @Test void relationFamiliesChooseTheMostHeldMemberAndConnectThroughSharedHolders() {
        DynamicViewable oldKing = value("old", "Old kingship", "Position");
        DynamicViewable newKing = value("new", "New kingship", "Position");
        DynamicViewable president = value("pres", "President", "Position");
        newKing.put("replaces", List.of(oldKing));
        oldKing.put("replacedBy", List.of(newKing));
        RelationProfile relation = RelationProfile.of(
                List.of(oldKing, newKing, president), "replaces", "replacedBy");

        DynamicViewable alice = value("Q1", "Alice", "Person");
        DynamicViewable bob = value("Q2", "Bob", "Person");
        List<Viewable> holdings = List.of(
                holding("h1", oldKing, alice),
                holding("h2", newKing, alice),
                holding("h3", newKing, bob),
                holding("h4", president, alice));

        StructureDiscovery.Result result = StructureDiscovery.discover(relation, holdings,
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(2, result.families().size());
        StructureDiscovery.Family monarchy = result.families().stream()
                .filter(family -> family.members().size() == 2).findFirst().orElseThrow();
        assertEquals(newKing, monarchy.representative(),
                "two distinct holders choose the new kingship over the old one's one");
        assertEquals(2, monarchy.sharedEntities().size());
        assertEquals(1, result.links().size());
        assertEquals(List.of(alice), result.links().getFirst().sharedEntities());

        List<Viewable> familyRows = StructureDiscoveryRows.families(result);
        assertEquals(2, familyRows.size());
        Viewable monarchyRow = familyRows.stream()
                .filter(row -> field(row, "memberCount").equals(2))
                .findFirst().orElseThrow();
        assertEquals("New kingship — 2 members", monarchyRow.getDisplayName());
        assertEquals(1, StructureDiscoveryRows.links(result).size());
        assertEquals(2, SharedNeighbourGraphProjection.of(result).nodes().size());
        assertEquals("1 shared entity",
                SharedNeighbourGraphProjection.of(result).edges().getFirst().label());
    }

    /**
     * An isolated family stays inspectable and stays out of the graph.
     *
     * <p>The note says only families in a shared link are drawn. The first test cannot
     * see that rule — both its families are linked, so it passes with the filter removed.
     */
    @Test void aFamilySharingNothingIsARowButNotANode() {
        DynamicViewable connectedOne = value("c1", "Connected one", "Position");
        DynamicViewable connectedTwo = value("c2", "Connected two", "Position");
        DynamicViewable alone = value("alone", "Shares nobody", "Position");
        RelationProfile relation = RelationProfile.of(
                List.of(connectedOne, connectedTwo, alone), "replaces", "replacedBy");
        DynamicViewable shared = value("Q1", "Held both", "Person");

        StructureDiscovery.Result result = StructureDiscovery.discover(relation,
                List.of(holding("h1", connectedOne, shared),
                        holding("h2", connectedTwo, shared),
                        holding("h3", alone, value("Q9", "Held only this", "Person"))),
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(3, StructureDiscoveryRows.families(result).size(),
                "every family is inspectable");
        assertEquals(1, result.links().size());
        assertEquals(2, SharedNeighbourGraphProjection.of(result).nodes().size(),
                "the isolated family is not drawn");
    }

    /**
     * One shared entity across three families is three links, and the count that names
     * each edge is of entities shared by that PAIR, not by the group.
     */
    @Test void oneSharedEntityAcrossThreeFamiliesIsThreePairwiseLinks() {
        DynamicViewable first = value("f1", "First", "Position");
        DynamicViewable second = value("f2", "Second", "Position");
        DynamicViewable third = value("f3", "Third", "Position");
        RelationProfile relation = RelationProfile.of(
                List.of(first, second, third), "replaces", "replacedBy");
        DynamicViewable everywhere = value("Q1", "Held all three", "Person");

        StructureDiscovery.Result result = StructureDiscovery.discover(relation,
                List.of(holding("h1", first, everywhere),
                        holding("h2", second, everywhere),
                        holding("h3", third, everywhere)),
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(3, result.links().size());
        assertEquals(List.of(1, 1, 1),
                result.links().stream().map(link -> link.sharedEntities().size()).toList());
        assertEquals(3, SharedNeighbourGraphProjection.of(result).edges().size());
    }

    /**
     * The rows keep every link; the graph draws the ones worth reading.
     *
     * <p>Over History 755 of 1,610 links rest on a single shared person — one office-holder
     * who happened to hold two unrelated offices — which buries the hundred carrying ten
     * or more. A family left with no drawn link is not drawn either, by the same rule that
     * already excluded a family sharing nothing.
     */
    @Test void aLinkTooWeakToReadIsARowButNotAnEdge() {
        DynamicViewable strongOne = value("s1", "Strong one", "Position");
        DynamicViewable strongTwo = value("s2", "Strong two", "Position");
        DynamicViewable weak = value("w", "Weakly linked", "Position");
        RelationProfile relation = RelationProfile.of(
                List.of(strongOne, strongTwo, weak), "replaces", "replacedBy");
        DynamicViewable both = value("Q1", "Held both strong", "Person");
        DynamicViewable alsoBoth = value("Q2", "Held both strong too", "Person");
        DynamicViewable coincidence = value("Q3", "Held one of each", "Person");

        StructureDiscovery.Result result = StructureDiscovery.discover(relation,
                List.of(holding("h1", strongOne, both), holding("h2", strongTwo, both),
                        holding("h3", strongOne, alsoBoth),
                        holding("h4", strongTwo, alsoBoth),
                        holding("h5", strongOne, coincidence),
                        holding("h6", weak, coincidence)),
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(2, result.links().size(), "both links are in the result");
        assertEquals(2, StructureDiscoveryRows.links(result).size(),
                "and both are inspectable as rows");
        assertEquals(2, SharedNeighbourGraphProjection.of(result).edges().size(),
                "projecting the result draws all of it");
        assertEquals(1, SharedNeighbourGraphProjection.of(result,
                        SharedNeighbourGraphProjection.DEFAULT_MINIMUM_SHARED)
                .edges().size(),
                "but where the reader starts, one shared person is a coincidence");
        assertEquals(2, SharedNeighbourGraphProjection.of(result,
                        SharedNeighbourGraphProjection.DEFAULT_MINIMUM_SHARED)
                .nodes().size(),
                "and the family only the weak link reached is not drawn");
        assertEquals(0, SharedNeighbourGraphProjection.of(result, 3).edges().size(),
                "and nothing here survives a floor of three");
    }

    /** A bridge row naming a member outside the analyzed population is counted, not lost. */
    @Test void aMemberOutsideTheAnalyzedPopulationIsReported() {
        DynamicViewable inside = value("in", "Inside", "Position");
        RelationProfile relation = RelationProfile.of(List.of(inside), "replaces", "replacedBy");
        DynamicViewable outside = value("out", "Never analyzed", "Position");
        DynamicViewable person = value("Q1", "Somebody", "Person");

        StructureDiscovery.Result result = StructureDiscovery.discover(relation,
                List.of(holding("h1", inside, person), holding("h2", outside, person)),
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(2, result.bridgeRows());
        assertEquals(1, result.unmatchedMemberReferences(),
                "a family built from half the rows is not a family anybody should trust");
        assertEquals(0, result.links().size(),
                "and the unmatched member forms no family to link to");
    }

    /** Equal counts fall back to label, so the same data names the same representative. */
    @Test void anEqualCountTieBreaksOnLabelNotOnTraversalOrder() {
        DynamicViewable zulu = value("z", "Zulu office", "Position");
        DynamicViewable alpha = value("a", "Alpha office", "Position");
        zulu.put("replaces", List.of(alpha));
        alpha.put("replacedBy", List.of(zulu));
        RelationProfile relation = RelationProfile.of(List.of(zulu, alpha), "replaces", "replacedBy");
        DynamicViewable one = value("Q1", "One", "Person");
        DynamicViewable two = value("Q2", "Two", "Person");

        StructureDiscovery.Result result = StructureDiscovery.discover(relation,
                List.of(holding("h1", zulu, one), holding("h2", alpha, two)),
                new StructureDiscovery.Bridge("OfficeHolding", "position", "holder"));

        assertEquals(alpha, result.families().getFirst().representative(),
                "one shared entity each, so the label decides");
    }

    private static DynamicViewable holding(
            String id, Viewable position, Viewable holder) {
        DynamicViewable value = value(id, id, "OfficeHolding");
        value.put("position", position);
        value.put("holder", holder);
        return value;
    }

    private static DynamicViewable value(String id, String name, String type) {
        DynamicViewable value = new DynamicViewable(id, name);
        value.type(type);
        return value;
    }

    private static Object field(Viewable value, String name) {
        return objectview.field.FieldSet.of(value).read(name);
    }
}
