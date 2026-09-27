package quiz.transform.ui;

import graphview.GraphViewModel;
import quiz.source.SourceIdentities;
import quiz.transform.StructureDiscovery;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application adapter from a structure result to the provider-neutral graph renderer DTO.
 *
 * <p>A view of the result, with the result's own legibility rules: a family nothing links
 * to is not drawn, and neither is a link too weak to be worth reading. Over History's 410
 * positions the pipeline finds 1,610 links of which 755 rest on a single shared person —
 * one office-holder who happened to hold two unrelated offices — so drawing every link
 * buries the 100 that carry ten or more. The rows keep all of them; this decides what is
 * worth looking at.
 */
public final class SharedNeighbourGraphProjection {
    /**
     * Where the reader starts: one shared entity is a coincidence, two is the weakest
     * claim worth drawing. It is the VIEW's opening position and not this projection's
     * default, because "render the result" and "render the part of it worth reading" are
     * different questions and the no-argument form answers the first one faithfully.
     */
    public static final int DEFAULT_MINIMUM_SHARED = 2;

    private SharedNeighbourGraphProjection() { }

    /** Every link the result holds. */
    public static GraphViewModel of(StructureDiscovery.Result result) {
        return of(result, 1);
    }

    /**
     * @param minimumShared draw only links carrying at least this many shared entities.
     *                      1 draws everything the result holds.
     */
    public static GraphViewModel of(StructureDiscovery.Result result, int minimumShared) {
        if (result == null) return new GraphViewModel(List.of(), List.of());
        int floor = Math.max(1, minimumShared);
        List<StructureDiscovery.SharedLink> drawn = result.links().stream()
                .filter(link -> link.sharedEntities().size() >= floor).toList();
        Set<Integer> connected = new LinkedHashSet<>();
        drawn.forEach(link -> {
            connected.add(link.left().index());
            connected.add(link.right().index());
        });
        List<GraphViewModel.Node> nodes = result.families().stream()
                .filter(family -> connected.contains(family.index()))
                .map(family -> {
                    String qid = SourceIdentities.wikidataQid(family.representative());
                    URI link = qid == null ? null
                            : URI.create("https://www.wikidata.org/wiki/" + qid);
                    return new GraphViewModel.Node(id(family),
                            family.representative().getDisplayName(), link, 0,
                            GraphViewModel.State.DEFAULT,
                            Map.of("Family size", Integer.toString(family.members().size()),
                                    "Shared by representative",
                                    Integer.toString(family.representativeSharedCount()),
                                    "Shared by family",
                                    Integer.toString(family.sharedEntities().size())),
                            family.representative());
                }).toList();
        List<GraphViewModel.Edge> edges = new ArrayList<>();
        int index = 0;
        for (StructureDiscovery.SharedLink relation : drawn) {
            int count = relation.sharedEntities().size();
            edges.add(new GraphViewModel.Edge("shared-" + index++, id(relation.left()),
                    id(relation.right()), count + " shared" + (count == 1 ? " entity" : " entities"),
                    false));
        }
        return new GraphViewModel(nodes, edges);
    }

    private static String id(StructureDiscovery.Family family) {
        return "family-" + family.index();
    }
}
