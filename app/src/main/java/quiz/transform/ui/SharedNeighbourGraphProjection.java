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

/** Application adapter from a structure result to the provider-neutral graph renderer DTO. */
public final class SharedNeighbourGraphProjection {
    private SharedNeighbourGraphProjection() { }

    public static GraphViewModel of(StructureDiscovery.Result result) {
        if (result == null) return new GraphViewModel(List.of(), List.of());
        Set<Integer> connected = new LinkedHashSet<>();
        result.links().forEach(link -> {
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
        for (StructureDiscovery.SharedLink relation : result.links()) {
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
