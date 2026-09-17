package wikidata.explore.workbench;

import datasource.EntityRef;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.execution.GraphDiscoveryExecutor;
import quiz.transform.app.SnapshotDomain;
import quiz.DatasetRegistry;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Materializes one graph execution as an ordinary TransformApp snapshot. */
final class GraphDiscoveryResultStore {
    static final String TYPE = "GraphDiscoveryResult";
    static final String GRAPH_DECISION = "Graph decision";
    static final String MANUAL_DECISION = "Manual decision";
    static final String ANNOTATED_INSTANCE = "Annotated instance";
    static final String GRAPH_ANNOTATION = "Graph annotation";

    private GraphDiscoveryResultStore() { }

    static String domainName(String graphConstraintName) {
        return graphConstraintName == null || graphConstraintName.isBlank()
                ? TYPE : graphConstraintName;
    }

    static File destination(String projectName, String graphConstraintName) {
        return destination(DomainStorage.inDefaultLocation(),
                projectName, graphConstraintName);
    }

    static File destination(
            DomainStorage storage, String projectName, String graphConstraintName) {
        return new File(storage.directory(projectName),
                DomainStorage.key(graphConstraintName) + ".graph.snapshot.json");
    }

    static String save(
            String projectName, String graphConstraintName,
            ConfiguredGraphDiscoveryQuery.Result result)
            throws Exception {
        return save(artifact(projectName, graphConstraintName, result));
    }

    static Artifact artifact(ConfiguredGraphDiscoveryQuery.Result result) {
        return artifact("", TYPE, result);
    }

    static Artifact artifact(String graphConstraintName,
                             ConfiguredGraphDiscoveryQuery.Result result) {
        return artifact("", graphConstraintName, result);
    }

    static Artifact artifact(String projectName, String graphConstraintName,
                             ConfiguredGraphDiscoveryQuery.Result result) {
        String type = domainName(graphConstraintName);
        ResultObjects built = resultObjects(type, result);
        List<WikidataDynamicObject> records = built.annotations();
        wikidata.explore.extract.SnapshotFieldGraph model =
                wikidata.explore.extract.SnapshotFieldGraph.derive(records);
        model.declareExhaustiveValues(type, GRAPH_DECISION,
                List.of("Accepted", "Review", "Rejected"));
        model.declareExhaustiveValues(type, MANUAL_DECISION,
                List.of("Accepted", "Rejected"));
        var annotationShape = model.types.get(type);
        if (annotationShape != null) {
            var reverse = annotationShape.fields.get(ANNOTATED_INSTANCE);
            if (reverse != null) reverse.reference = true;
        }
        var candidateShape = model.types.get(built.outputClass());
        if (candidateShape != null) {
            candidateShape.member = false;
            var reverse = candidateShape.fields.get(GRAPH_ANNOTATION);
            if (reverse != null) reverse.structural = true;
        }
        return new Artifact(projectName, type, built.outputClass(), records,
                built.candidates(), new SnapshotDomain(records, model));
    }

    static String save(Artifact artifact) throws Exception {
        return save(artifact, DomainStorage.inDefaultLocation(),
                DatasetRegistry.defaultFile());
    }

    static Artifact load(String projectName, String graphConstraintName,
                         String outputClass) throws Exception {
        File file = destination(projectName, graphConstraintName);
        if (!file.isFile()) return null;
        WikidataDynamicObjectJsonStore.LoadedSnapshot loaded =
                new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);
        List<WikidataDynamicObject> annotations = loaded.objects();
        List<WikidataDynamicObject> candidates = annotations.stream()
                .map(value -> value.get(ANNOTATED_INSTANCE))
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).distinct().toList();
        return new Artifact(projectName, domainName(graphConstraintName), outputClass,
                annotations, candidates,
                new SnapshotDomain(annotations, loaded.fieldGraph()));
    }

    /**
     * The write itself, with its two destinations as parameters so the path production
     * takes is the path a test takes. Tested through a writer stand-in instead, the file
     * and the registry entry — the two things this method exists to produce — were the
     * parts nothing looked at.
     */
    static String save(Artifact artifact, DomainStorage storage, File registryFile)
            throws Exception {
        if (artifact.projectName().isBlank()) {
            throw new IllegalArgumentException("The graph result has no owning project");
        }
        File file = destination(storage, artifact.projectName(), artifact.type());
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                artifact.instances(), file, artifact.model());

        DatasetRegistry.Dataset dataset = new DatasetRegistry.Dataset();
        dataset.name(artifact.projectName() + " — " + artifact.type());
        dataset.key(DomainStorage.key(artifact.projectName()) + "--"
                + DomainStorage.key(artifact.type()));
        dataset.snapshotPath(file.getPath());
        dataset.types().add(artifact.type());
        dataset.rootClass(artifact.type());
        dataset.savedAt(java.time.LocalDateTime.now().toString());
        // Loadable in TransformApp, never served: these rows describe a run.
        dataset.served(false);
        DatasetRegistry registry = DatasetRegistry.load(registryFile);
        registry.upsert(dataset);
        registry.save(registryFile);
        return "Saved graph annotations \"" + artifact.type() + "\" for \""
                + artifact.projectName() + "\": " + artifact.instances().size()
                + " instances to " + file.getPath()
                + ". Loadable in TransformApp; not served.";
    }

    static String save(
            String graphConstraintName, ConfiguredGraphDiscoveryQuery.Result result,
            ResultWriter writer) throws Exception {
        return save(artifact(graphConstraintName, result), writer);
    }

    /**
     * The artifact already carries the name its instances are stamped with, so the write
     * asks it rather than recomputing from a string. Passing the name twice let the two
     * disagree: instances typed one way inside a domain called another, with nothing to
     * object.
     */
    static String save(Artifact artifact, ResultWriter writer) throws Exception {
        return writer.save(artifact.type(), artifact.instances(), artifact.model());
    }

    record Artifact(String projectName, String type, String outputClass,
                    List<WikidataDynamicObject> instances,
                    List<WikidataDynamicObject> candidates, SnapshotDomain model) {
        Artifact {
            projectName = projectName == null ? "" : projectName.trim();
            outputClass = outputClass == null ? "" : outputClass.trim();
            instances = List.copyOf(instances);
            candidates = List.copyOf(candidates);
            java.util.Objects.requireNonNull(model, "model");
        }

        List<WikidataDynamicObject> acceptedCandidates() {
            return instances.stream().filter(GraphDiscoveryResultStore::included)
                    .map(value -> value.get(ANNOTATED_INSTANCE))
                    .filter(WikidataDynamicObject.class::isInstance)
                    .map(WikidataDynamicObject.class::cast).distinct().toList();
        }
    }

    private record ResultObjects(String outputClass,
                                 List<WikidataDynamicObject> annotations,
                                 List<WikidataDynamicObject> candidates) { }

    static List<WikidataDynamicObject> records(
            ConfiguredGraphDiscoveryQuery.Result result) {
        return records(TYPE, result);
    }

    static List<WikidataDynamicObject> records(String type,
            ConfiguredGraphDiscoveryQuery.Result result) {
        return resultObjects(type, result).annotations();
    }

    private static ResultObjects resultObjects(String type,
            ConfiguredGraphDiscoveryQuery.Result result) {
        if (result == null || result.graph() == null) {
            return new ResultObjects("", List.of(), List.of());
        }
        GraphDiscoveryExecutor.NodeResult output = result.graph().nodes().stream()
                .filter(node -> node.configuration() != null
                        && node.configuration().use()
                        == datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                .findFirst().orElse(null);
        // Which node produces the output class is a stored decision — NodeUse
        // CLASS_POPULATION — and nothing else may stand in for it. Reading it off the
        // traversal's label meant asking whether that label began with "Graph node ",
        // the placeholder the executor writes for a node that has no class: inferring a
        // role from a name, which is the one thing this codebase forbids outright, and
        // it was inferring it only for hand-built fixtures that omitted the decision.
        if (output == null) return new ResultObjects("", List.of(), List.of());
        String outputClass = output.configuration().populationClass();
        Map<String, WikidataDynamicObject> candidates = new LinkedHashMap<>();
        Map<String, WikidataDynamicObject> records = new LinkedHashMap<>();
        {
            GraphDiscoveryExecutor.NodeResult node = output;
            Map<EntityRef, GraphEvidenceConditionResult> classifications =
                    new LinkedHashMap<>();
            node.classifications().forEach(value ->
                    classifications.put(value.node(), value));
            for (EntityRef entity : node.reached()) {
                WikidataDynamicObject candidate = new WikidataDynamicObject(
                        nativeIdentifier(entity), result.label(entity));
                candidate.type(outputClass);
                candidates.put(entity.namespace() + ":" + entity.id(), candidate);
                GraphEvidenceConditionResult classification = classifications.get(entity);
                String decision = classification == null
                        ? (node.rejected().contains(entity) ? "Rejected"
                        : node.review().contains(entity) ? "Review" : "Accepted")
                        : switch (classification.decision()) {
                            case ACCEPTED -> "Accepted";
                            case REJECTED -> "Rejected";
                            case REVIEW -> "Review";
                        };
                record(type, result, records, node.index(), entity, decision,
                        node, classification, candidate);
            }
        }
        return new ResultObjects(outputClass, List.copyOf(records.values()),
                List.copyOf(candidates.values()));
    }

    private static void record(
            String type,
            ConfiguredGraphDiscoveryQuery.Result result,
            Map<String, WikidataDynamicObject> records,
            int step,
            EntityRef entity,
            String decision,
            GraphDiscoveryExecutor.NodeResult node,
            GraphEvidenceConditionResult classification,
            WikidataDynamicObject candidate) {
        String entityKey = entity.namespace() + ":" + entity.id();
        WikidataDynamicObject record = records.computeIfAbsent(entityKey, ignored -> {
            WikidataDynamicObject value = new WikidataDynamicObject(
                    nativeIdentifier(entity), result.label(entity));
            value.type(type);
            return value;
        });
        record.merge("Step", step);
        record.merge(GRAPH_DECISION, decision);
        record.put(ANNOTATED_INSTANCE, candidate);
        candidate.put(GRAPH_ANNOTATION, record);
        if (node != null && node.traversal() != null) {
            record.merge("Relation", node.traversal().relation().relationId());
            record.merge("Direction", node.traversal().direction().name());
            record.merge("Reached as", node.traversal().targetNodeClass());
            if (node.incomplete().contains(entity)) record.merge("Adjacency", "Incomplete");
            if (node.unavailable().contains(entity)) record.merge("Adjacency", "Unavailable");
        }
        if (classification != null) {
            record.put("Review disposition",
                    classification.reviewDisposition()
                            == datasource.graph.constraint.GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT
                            ? "Include" : "Exclude");
            if (!classification.conditionName().isBlank()) {
                record.merge("Condition", classification.conditionName());
            }
            if (!classification.reason().isBlank()) {
                record.merge("Reason", classification.reason());
            }
            record.put("Evidence edges", classification.evidenceEdges().size());
            record.put("Test edges", classification.testEdges().size());
            record.put("Witnesses", classification.witnesses().size());
            record.put("Evidence observations", classification.coverage().size());
        }
    }

    static void manualDecision(WikidataDynamicObject annotation, String decision) {
        if (annotation == null) return;
        if (decision == null || decision.isBlank()) {
            annotation.dynamicFields().remove(MANUAL_DECISION);
        } else {
            annotation.put(MANUAL_DECISION, decision);
        }
    }

    static boolean included(WikidataDynamicObject annotation) {
        Object manual = annotation.get(MANUAL_DECISION);
        if ("Accepted".equals(manual)) return true;
        if ("Rejected".equals(manual)) return false;
        Object original = annotation.get(GRAPH_DECISION);
        if ("Accepted".equals(original)) return true;
        if (!"Review".equals(original)) return false;
        Object disposition = annotation.get("Review disposition");
        return "Include".equals(disposition);
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
