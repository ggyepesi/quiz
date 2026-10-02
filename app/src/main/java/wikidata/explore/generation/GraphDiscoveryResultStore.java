package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import datasource.graph.execution.GraphDiscoveryExecutor;
import wikidata.explore.extract.SnapshotDomain;
import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.query.logical.ConfiguredGraphDiscoveryQuery;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Materializes one graph execution as an ordinary TransformApp snapshot. */
public final class GraphDiscoveryResultStore {
    public static final String GRAPH_DECISION = "Graph decision";
    public static final String MANUAL_DECISION = "Manual decision";
    public static final String ANNOTATED_INSTANCE = "Annotated instance";
    public static final String GRAPH_ANNOTATION = "Graph annotation";
    public static final String OUTPUT_APPLIED = "Output applied";
    public static final String REVIEW_DISPOSITION = "Review disposition";
    public static final String AWAIT_DECISION = "Await decision";
    /** The signature of the graph configuration that produced a result, on each of its
     *  annotations, so a later build can tell whether the result is still current. */
    public static final String CONFIGURATION_SIGNATURE = "Configuration signature";

    private GraphDiscoveryResultStore() { }

    /**
     * The annotation set is keyed by the constraint's authored name, so substituting a
     * default here is how a set gets written under one name and looked for under
     * another. Refuse instead: an unnamed constraint has nowhere to put its result.
     */
    public static String domainName(String graphConstraintName) {
        String name = graphConstraintName == null ? "" : graphConstraintName.trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "Name the graph constraint before running or saving it: its name is "
                    + "the identity of its annotation set");
        }
        return name;
    }

    public static File destination(String projectName, String graphConstraintName) {
        return destination(DomainStorage.inDefaultLocation(),
                projectName, graphConstraintName);
    }

    public static File destination(
            DomainStorage storage, String projectName, String graphConstraintName) {
        return new File(storage.directory(projectName),
                DomainStorage.key(graphConstraintName) + ".graph.snapshot.json");
    }

    public static String save(
            String projectName, String graphConstraintName,
            ConfiguredGraphDiscoveryQuery.Result result)
            throws Exception {
        return save(artifact(projectName, graphConstraintName, result));
    }

    public static Artifact artifact(String graphConstraintName,
                             ConfiguredGraphDiscoveryQuery.Result result) {
        return artifact("", graphConstraintName, result);
    }

    public static Artifact artifact(String projectName, String graphConstraintName,
                             ConfiguredGraphDiscoveryQuery.Result result) {
        String type = domainName(graphConstraintName);
        ResultObjects built = resultObjects(type, result);
        List<WikidataDynamicObject> records = built.annotations();
        wikidata.explore.extract.SnapshotFieldGraph model =
                fieldGraph(type, built.outputClass(), records);
        datasource.graph.GraphDiscoveryConfiguration.PopulationOperation operation =
                result.graph().nodes().stream()
                        .filter(node -> node.configuration().use()
                                == datasource.graph.GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                        .findFirst().map(node -> node.configuration().populationOperation())
                        .orElse(datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.NARROW);
        return new Artifact(projectName, type, built.outputClass(), operation, records,
                built.candidates(), new SnapshotDomain(records, model));
    }

    /**
     * Where an artifact's annotations go — the one expression, so the file a save dialog
     * NAMES and the file a save WRITES cannot be two answers.
     *
     * <p>They were: the plan line was built from the live project and class names while
     * the write took the names the artifact recorded when it ran, and a rename between
     * the run and the save made them disagree silently.
     */
    public static File destinationOf(Artifact artifact) {
        return destination(artifact.projectName(), artifact.type());
    }

    /**
     * The artifact under the names its owners have now: the project, the graph class and
     * its output class. Returns {@code artifact} itself when none was renamed.
     *
     * <p>A rename keeps the declaration id, so the result still belongs to its class; what
     * it recorded is the names its annotations and candidates are STAMPED with and its
     * file is keyed by. Those are restamped together here, so the set is never written
     * under one name while typed another. It used to be dropped instead, which lost the
     * run and every manual decision on it to a rename.
     */
    public static Artifact renamed(Artifact artifact, String projectName, String graphName,
                            String outputClass) {
        if (artifact == null) return null;
        String project = projectName == null ? artifact.projectName() : projectName;
        String type = graphName == null || graphName.isBlank() ? artifact.type() : graphName;
        String output = outputClass == null || outputClass.isBlank()
                ? artifact.outputClass() : outputClass;
        if (project.equals(artifact.projectName()) && type.equals(artifact.type())
                && output.equals(artifact.outputClass())) {
            return artifact;
        }
        Map<String, String> renames = new LinkedHashMap<>();
        if (!type.equals(artifact.type())) renames.put(artifact.type(), type);
        if (!output.equals(artifact.outputClass())) renames.put(artifact.outputClass(), output);
        List<WikidataDynamicObject> stamped = new java.util.ArrayList<>(artifact.instances());
        stamped.addAll(artifact.candidates());
        wikidata.explore.generation.GenerationRuns.renameClasses(stamped, renames);
        return new Artifact(project, type, output, artifact.populationOperation(),
                artifact.instances(), artifact.candidates(),
                new SnapshotDomain(artifact.instances(),
                        fieldGraph(type, output, artifact.instances())));
    }

    public static String save(Artifact artifact) throws Exception {
        return save(artifact, DomainStorage.inDefaultLocation());
    }

    public static Artifact load(String projectName, String graphConstraintName,
                         String outputClass) throws Exception {
        return load(projectName, graphConstraintName, outputClass,
                datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.NARROW);
    }

    public static Artifact load(String projectName, String graphConstraintName,
                         String outputClass,
                         datasource.graph.GraphDiscoveryConfiguration.PopulationOperation operation)
            throws Exception {
        return load(projectName, graphConstraintName, outputClass, operation,
                DomainStorage.inDefaultLocation());
    }

    public static Artifact load(String projectName, String graphConstraintName,
                         String outputClass,
                         datasource.graph.GraphDiscoveryConfiguration.PopulationOperation operation,
                         DomainStorage storage) throws Exception {
        File file = destination(storage, projectName, graphConstraintName);
        if (!file.isFile()) return null;
        WikidataDynamicObjectJsonStore.LoadedSnapshot loaded =
                new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);
        List<WikidataDynamicObject> annotations = loaded.objects();
        List<WikidataDynamicObject> candidates = annotations.stream()
                .map(value -> value.get(ANNOTATED_INSTANCE))
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).distinct().toList();
        return new Artifact(projectName, domainName(graphConstraintName), outputClass, operation,
                annotations, candidates,
                new SnapshotDomain(annotations, loaded.fieldGraph()));
    }

    /**
     * Restores a graph result already carried by the project's saved instance pool.
     * Applied graph annotations remain reachable from their annotated instances, so
     * Load instances has everything Show instances needs even when an older sidecar was
     * saved under a former graph name.
     */
    public static Artifact restore(String projectName, String graphConstraintName,
            String outputClass, java.util.Collection<WikidataDynamicObject> pool) {
        String type = domainName(graphConstraintName);
        List<WikidataDynamicObject> annotations = pool == null ? List.of() : pool.stream()
                .filter(java.util.Objects::nonNull)
                .filter(value -> type.equals(value.typeName())
                        || value.directClassNames().contains(type))
                .toList();
        if (annotations.isEmpty()) return null;
        List<WikidataDynamicObject> candidates = annotations.stream()
                .map(value -> value.get(ANNOTATED_INSTANCE))
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).distinct().toList();
        return new Artifact(projectName, type, outputClass, annotations, candidates,
                new SnapshotDomain(annotations, fieldGraph(type, outputClass, annotations)));
    }

    /**
     * The field graph of one annotation set — the one construction, used by a fresh run,
     * a restore from the pool and an Apply alike. Each used to build its own: a restored
     * set lost the annotation's reference to its instance and served the candidate class
     * as a member, and an applied set lost the decision facets as well.
     */
    public static wikidata.explore.extract.SnapshotFieldGraph fieldGraph(
            String type, String outputClass, List<WikidataDynamicObject> annotations) {
        wikidata.explore.extract.SnapshotFieldGraph model =
                wikidata.explore.extract.SnapshotFieldGraph.derive(annotations);
        model.declareExhaustiveValues(type, GRAPH_DECISION,
                List.of("Accepted", "Review", "Rejected"));
        model.declareExhaustiveValues(type, MANUAL_DECISION,
                List.of("Accepted", "Rejected"));
        var annotationShape = model.types.get(type);
        if (annotationShape != null) {
            var reverse = annotationShape.fields.get(ANNOTATED_INSTANCE);
            if (reverse != null) reverse.reference = true;
        }
        var candidateShape = outputClass == null ? null : model.types.get(outputClass);
        if (candidateShape != null) {
            candidateShape.member = false;
            var reverse = candidateShape.fields.get(GRAPH_ANNOTATION);
            if (reverse != null) reverse.structural = true;
        }
        return model;
    }

    /**
     * The write itself, with its destination as a parameter so the path production takes
     * is the path a test takes. Tested through a writer stand-in instead, the file this
     * method exists to produce was the part nothing looked at.
     *
     * <p>It used to register a dataset row too, so that TransformApp could open the
     * annotations. That was true when a graph result was only a sidecar; it stopped being
     * true when applying a graph started writing the annotations into the project's own
     * instances and the result panel began restoring them from there. The row then listed
     * a second copy of data the project already carries, under a second name, in every
     * navigator — and a row is how a thing becomes something the reader must account for.
     * Opening the project reaches them.
     */
    public static String save(Artifact artifact, DomainStorage storage)
            throws Exception {
        if (artifact.projectName().isBlank()) {
            throw new IllegalArgumentException("The graph result has no owning project");
        }
        File file = destination(storage, artifact.projectName(), artifact.type());
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                artifact.instances(), file, artifact.model());
        return "Saved graph annotations \"" + artifact.type() + "\" for \""
                + artifact.projectName() + "\": " + artifact.instances().size()
                + " instances to " + file.getPath()
                + ". They are part of \"" + artifact.projectName()
                + "\"; opening that project reaches them.";
    }

    public static String save(
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
    public static String save(Artifact artifact, ResultWriter writer) throws Exception {
        return writer.save(artifact.type(), artifact.instances(), artifact.model());
    }

    public record Artifact(String projectName, String type, String outputClass,
                    datasource.graph.GraphDiscoveryConfiguration.PopulationOperation populationOperation,
                    List<WikidataDynamicObject> instances,
                    List<WikidataDynamicObject> candidates, SnapshotDomain model) {
        public Artifact(String projectName, String type, String outputClass,
                List<WikidataDynamicObject> instances,
                List<WikidataDynamicObject> candidates, SnapshotDomain model) {
            this(projectName, type, outputClass,
                    datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.NARROW,
                    instances, candidates, model);
        }
        public Artifact {
            projectName = projectName == null ? "" : projectName.trim();
            outputClass = outputClass == null ? "" : outputClass.trim();
            populationOperation = populationOperation == null
                    ? datasource.graph.GraphDiscoveryConfiguration.PopulationOperation.NARROW
                    : populationOperation;
            instances = List.copyOf(instances);
            candidates = List.copyOf(candidates);
            java.util.Objects.requireNonNull(model, "model");
        }

        public List<WikidataDynamicObject> acceptedCandidates() {
            return instances.stream().filter(GraphDiscoveryResultStore::included)
                    .map(value -> value.get(ANNOTATED_INSTANCE))
                    .filter(WikidataDynamicObject.class::isInstance)
                    .map(WikidataDynamicObject.class::cast).distinct().toList();
        }

        /**
         * The effective population as IDENTITIES. A candidate object carries a QID, a
         * label and a reverse reference to its annotation — it is a record of what the
         * traversal reached, never a generated instance, so what a graph result
         * contributes to the project is this set of ids and nothing else.
         */
        public java.util.Set<String> acceptedIdentities() {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (WikidataDynamicObject candidate : acceptedCandidates()) {
                String id = candidate == null ? null : candidate.getIdentifier();
                if (id != null && !id.isBlank()) ids.add(id);
            }
            return ids;
        }

        /** The entries applying this result must wait for, in result order. */
        public List<WikidataDynamicObject> awaitingDecision() {
            return instances.stream()
                    .filter(GraphDiscoveryResultStore::awaitingDecision).toList();
        }

        /** The configuration signature recorded on this result, or blank when it predates
         *  the record. */
        public String configurationSignature() {
            return instances.stream().map(value -> value.get(CONFIGURATION_SIGNATURE))
                    .filter(String.class::isInstance).map(String.class::cast)
                    .findFirst().orElse("");
        }

        public boolean applied() {
            return instances.stream().anyMatch(value ->
                    Boolean.TRUE.equals(value.get(OUTPUT_APPLIED)));
        }
    }

    /** Records the explicit Apply on the annotation set itself, so Save domain and
     * Load instances retain which graph result may contribute pending generation input. */
    public static Artifact applied(Artifact artifact) {
        if (artifact == null || artifact.applied()) return artifact;
        artifact.instances().forEach(value -> value.put(OUTPUT_APPLIED, true));
        SnapshotDomain refreshed = new SnapshotDomain(artifact.instances(),
                fieldGraph(artifact.type(), artifact.outputClass(), artifact.instances()));
        return new Artifact(artifact.projectName(), artifact.type(), artifact.outputClass(),
                artifact.populationOperation(), artifact.instances(), artifact.candidates(),
                refreshed);
    }

    private record ResultObjects(String outputClass,
                                 List<WikidataDynamicObject> annotations,
                                 List<WikidataDynamicObject> candidates) { }

    public static List<WikidataDynamicObject> records(String type,
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
            List<String> traversalWitnesses = node.edges().stream()
                    .filter(edge -> entity.equals(edge.source())
                            || edge.target() instanceof EntityRef target && entity.equals(target))
                    .map(edge -> traversalWitness(result, edge))
                    .distinct().toList();
            if (!traversalWitnesses.isEmpty()) {
                record.put("Traversal witnesses", traversalWitnesses);
            }
        }
        if (classification != null) {
            record.put(REVIEW_DISPOSITION, switch (classification.reviewDisposition()) {
                case INCLUDE_AND_REPORT -> "Include";
                case EXCLUDE_AND_REPORT -> "Exclude";
                case AWAIT_DECISION -> AWAIT_DECISION;
            });
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

    private static String traversalWitness(
            ConfiguredGraphDiscoveryQuery.Result result,
            datasource.graph.store.GraphEdge edge) {
        String source = result.label(edge.source()) + " (" + edge.source().id() + ")";
        String target = edge.target() instanceof EntityRef entity
                ? result.label(entity) + " (" + entity.id() + ")"
                : String.valueOf(edge.target());
        return source + " —" + edge.relation().relationId() + "→ " + target;
    }

    public static void manualDecision(WikidataDynamicObject annotation, String decision) {
        if (annotation == null) return;
        if (decision == null || decision.isBlank()) {
            annotation.dynamicFields().remove(MANUAL_DECISION);
        } else {
            annotation.put(MANUAL_DECISION, decision);
        }
    }

    public static boolean included(WikidataDynamicObject annotation) {
        Object manual = annotation.get(MANUAL_DECISION);
        if ("Accepted".equals(manual)) return true;
        if ("Rejected".equals(manual)) return false;
        Object original = annotation.get(GRAPH_DECISION);
        if ("Accepted".equals(original)) return true;
        if (!"Review".equals(original)) return false;
        Object disposition = annotation.get(REVIEW_DISPOSITION);
        return "Include".equals(disposition);
    }

    /**
     * A Review entry whose graph said to wait for the reviewer, and that has no manual
     * decision yet. Asked the same way {@link #included} is, so an entry is always exactly
     * one of included, excluded or awaiting.
     */
    public static boolean awaitingDecision(WikidataDynamicObject annotation) {
        if (annotation == null || annotation.get(MANUAL_DECISION) != null) return false;
        return "Review".equals(annotation.get(GRAPH_DECISION))
                && AWAIT_DECISION.equals(annotation.get(REVIEW_DISPOSITION));
    }

    /** The graph's immutable classification; manual curation is deliberately separate. */
    public static List<String> originalDecisions(WikidataDynamicObject annotation) {
        Object decision = annotation == null ? null : annotation.get(GRAPH_DECISION);
        if (decision instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return decision == null ? List.of() : List.of(String.valueOf(decision));
    }

    private static String nativeIdentifier(EntityRef entity) {
        return "wikidata".equalsIgnoreCase(entity.namespace())
                ? entity.id() : entity.namespace() + ":" + entity.id();
    }

    @FunctionalInterface
    public interface ResultWriter {
        String save(String name, List<WikidataDynamicObject> members,
                    SnapshotDomain schema) throws Exception;
    }
}
