package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.execution.GraphDiscoveryExecutor;
import quiz.transform.app.DomainSaver;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Materializes one graph execution as an ordinary TransformApp snapshot. */
final class GraphDiscoveryResultStore {
    static final String TYPE = "GraphDiscoveryResult";

    private GraphDiscoveryResultStore() { }

    static String domainName(String projectName) {
        return (projectName == null || projectName.isBlank()
                ? "Graph" : projectName) + " — graph result";
    }

    static File destination(String projectName) {
        return DomainSaver.destination(domainName(projectName));
    }

    static String save(String projectName, ConfiguredGraphDiscoveryQuery.Result result)
            throws Exception {
        return save(projectName, artifact(result),
                (name, members, schema) -> new DomainSaver().save(name, members, schema));
    }

    static Artifact artifact(ConfiguredGraphDiscoveryQuery.Result result) {
        List<WikidataDynamicObject> records = records(result);
        wikidata.explore.extract.SnapshotFieldGraph model =
                wikidata.explore.extract.SnapshotFieldGraph.derive(records);
        model.declareExhaustiveValues(TYPE, "Decision",
                List.of("Start", "Accepted", "Review", "Rejected"));
        return new Artifact(records, new SnapshotDomain(records, model));
    }

    static String save(String projectName, Artifact artifact) throws Exception {
        return save(projectName, artifact,
                (name, members, schema) -> new DomainSaver().save(name, members, schema));
    }

    static String save(String projectName, ConfiguredGraphDiscoveryQuery.Result result,
                       ResultWriter writer) throws Exception {
        return save(projectName, artifact(result), writer);
    }

    static String save(String projectName, Artifact artifact,
                       ResultWriter writer) throws Exception {
        return writer.save(domainName(projectName), artifact.instances(), artifact.model());
    }

    record Artifact(List<WikidataDynamicObject> instances, SnapshotDomain model) {
        Artifact {
            instances = List.copyOf(instances);
            java.util.Objects.requireNonNull(model, "model");
        }
    }

    static List<WikidataDynamicObject> records(
            ConfiguredGraphDiscoveryQuery.Result result) {
        if (result == null || result.graph() == null) return List.of();
        Map<String, WikidataDynamicObject> records = new LinkedHashMap<>();
        for (EntityRef entity : result.graph().start()) {
            record(result, records, 0, entity, "Start", null, null);
        }
        for (GraphDiscoveryExecutor.NodeResult node : result.graph().nodes()) {
            Map<EntityRef, GraphEvidenceConditionResult> classifications =
                    new LinkedHashMap<>();
            node.classifications().forEach(value ->
                    classifications.put(value.node(), value));
            for (EntityRef entity : node.reached()) {
                GraphEvidenceConditionResult classification = classifications.get(entity);
                String decision = classification == null ? "Accepted"
                        : switch (classification.decision()) {
                            case ACCEPTED -> "Accepted";
                            case REJECTED -> "Rejected";
                            case REVIEW -> "Review";
                        };
                record(result, records, node.index(), entity, decision,
                        node, classification);
            }
        }
        return List.copyOf(records.values());
    }

    private static void record(
            ConfiguredGraphDiscoveryQuery.Result result,
            Map<String, WikidataDynamicObject> records,
            int step,
            EntityRef entity,
            String decision,
            GraphDiscoveryExecutor.NodeResult node,
            GraphEvidenceConditionResult classification) {
        String entityKey = entity.namespace() + ":" + entity.id();
        WikidataDynamicObject record = records.computeIfAbsent(entityKey, ignored -> {
            WikidataDynamicObject value = new WikidataDynamicObject(
                    nativeIdentifier(entity), result.label(entity));
            value.type(TYPE);
            return value;
        });
        record.merge("Step", step);
        record.merge("Decision", decision);
        if (node != null && node.traversal() != null) {
            record.merge("Relation", node.traversal().relation().relationId());
            record.merge("Direction", node.traversal().direction().name());
            record.merge("Reached as", node.traversal().targetNodeClass());
            if (node.incomplete().contains(entity)) record.merge("Adjacency", "Incomplete");
            if (node.unavailable().contains(entity)) record.merge("Adjacency", "Unavailable");
        }
        if (classification != null) {
            if (!classification.conditionName().isBlank()) {
                record.merge("Condition", classification.conditionName());
            }
            if (!classification.reason().isBlank()) {
                record.merge("Reason", classification.reason());
            }
        }
    }

    private static String nativeIdentifier(EntityRef entity) {
        return "wikidata".equalsIgnoreCase(entity.namespace())
                ? entity.id() : entity.namespace() + ":" + entity.id();
    }

    @FunctionalInterface
    interface ResultWriter {
        String save(String name, List<WikidataDynamicObject> members,
                    SnapshotDomain schema) throws Exception;
    }
}
