package domain.workbench.modelbuilder.ui;

import domain.DomainModel;
import domain.build.GraphDiscoveryResultStore;
import domain.config.ConstructInventory;
import domain.config.GeneratedProjectModel;
import domain.transform.app.ConfiguredGroups;
import domain.workbench.execution.ui.QueryObjectResultPanel;
import wikidata.explore.query.result.ObjectQueryResult;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** The ordinary classes, configured groups and graph peers of a project's instances. */
public final class ProjectInstancesPresentation {
    private ProjectInstancesPresentation() { }

    public static void show(QueryObjectResultPanel viewer, GeneratedProjectModel model,
            ObjectQueryResult result, Collection<GraphDiscoveryResultStore.Artifact> graphs,
            Function<GraphDiscoveryResultStore.Artifact, QueryObjectResultPanel.GroupedSection> review) {
        viewer.configuredGroups(ConfiguredGroups.presentationBindings(model, result.objects()));
        viewer.acceptGrouped(new ObjectQueryResult(
                ConstructInventory.of(model).visibleInstances(result.objects()), result.primaryClass(),
                result.generatedSource(), result.typeOrder(), result.partTypes()), sections(graphs, review));
    }

    public static void showSaved(QueryObjectResultPanel viewer, GeneratedProjectModel model,
            DomainModel domain, Collection<GraphDiscoveryResultStore.Artifact> graphs) {
        viewer.configuredGroups(ConfiguredGroups.presentationBindings(model, domain.instances()));
        viewer.acceptSavedRoots(ConstructInventory.of(model).visibleInstances(domain.memberRoots()),
                sections(graphs, graph -> GraphResultPresentation.review(graph, java.util.List.of(), null)));
    }

    private static Map<String, QueryObjectResultPanel.GroupedSection> sections(
            Collection<GraphDiscoveryResultStore.Artifact> graphs,
            Function<GraphDiscoveryResultStore.Artifact, QueryObjectResultPanel.GroupedSection> review) {
        var sections = new LinkedHashMap<String, QueryObjectResultPanel.GroupedSection>();
        graphs.forEach(graph -> sections.put(graph.type(), review.apply(graph)));
        return sections;
    }
}
