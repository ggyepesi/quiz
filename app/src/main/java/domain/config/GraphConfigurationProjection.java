package domain.config;

import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.constraint.GraphEvidenceCondition;
import domain.datasource.graph.constraint.GraphNodeCondition;
import domain.datasource.graph.constraint.GraphNodeIsUnder;
import domain.datasource.graph.constraint.GraphPath;
import domain.datasource.graph.constraint.GraphRelationAbsent;
import domain.datasource.graph.constraint.GraphRelationExists;
import domain.datasource.graph.constraint.GraphRelationReaches;
import domain.datasource.graph.constraint.GraphRelationReachesUnder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** The one model-layer configuration tree for a graph class. */
public final class GraphConfigurationProjection {
    public static final String DEFINITION = "graph-definition";
    public static final String START = "graph-start";
    public static final String PATH = "graph-path";
    public static final String EDGE = "graph-edge";
    public static final String NODE = "graph-node";
    public static final String EVIDENCE = "graph-evidence";
    public static final String IDENTITY = "graph-identity";
    public static final String DISPLAY_NAME = "graph-display-name";
    private static final String ADD_NODE = "path/add";

    private GraphConfigurationProjection() { }

    public static ConfigurationNode project(
            GeneratedProjectModel project, GeneratedClassModel graphClass) {
        if (project == null || graphClass == null) {
            throw new IllegalArgumentException("Project and graph class are required");
        }
        GraphClassSource source = graphClass.graphSource();
        return project(project, graphClass, source == null ? null : source.startNode(),
                source == null ? List.of() : source.nextNodes());
    }

    /** Projects the editor's one graph draft with the same grammar as the saved source. */
    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel graphClass, GraphDiscoveryConfiguration.StartNode start,
            List<GraphDiscoveryConfiguration.NextNode> nodes) {
        if (project == null || graphClass == null) {
            throw new IllegalArgumentException("Project and graph class are required");
        }
        List<GraphDiscoveryConfiguration.NextNode> path = nodes == null
                ? List.of() : List.copyOf(nodes);
        GraphClassSource saved = graphClass.graphSource();
        boolean savedDraft = saved != null
                && java.util.Objects.equals(saved.startNode(), start)
                && saved.nextNodes().equals(path);
        // References are inventory facts about the saved model. While the editor holds
        // another draft, showing the saved node-N reference on draft node N would be a
        // false dependency after an insertion/removal. The draft still names its target
        // in the node caption; it becomes navigable from the inventory after Apply.
        List<ConfigurationReferenceInventory.Reference> references = savedDraft
                ? ConfigurationReferenceInventory.of(project).fromClass(graphClass)
                : List.of();

        List<ConfigurationNode> children = new ArrayList<>();
        children.add(node(graphClass, "definition", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Definition", DEFINITION, List.of()));
        children.add(start(graphClass, start, references));
        children.add(path(graphClass, path, references));
        children.add(node(graphClass, "identity", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Identity", IDENTITY, List.of()));
        children.add(node(graphClass, "display-name",
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Display name", DISPLAY_NAME, List.of()));
        return node(graphClass, "graph", ConfigurationNode.Kind.STORED_CONFIGURATION,
                graphClass.className(), DEFINITION, children);
    }

    /** Every detail below one ordered node carries its owner index in the node id. */
    public static int pathNodeIndex(ConfigurationNode.Id id) {
        if (id == null) return -1;
        String key = id.key();
        int start = key.indexOf("path/node:");
        if (start < 0) return -1;
        start += "path/node:".length();
        int end = key.indexOf('/', start);
        String value = end < 0 ? key.substring(start) : key.substring(start, end);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static boolean isAddNodeSlot(ConfigurationNode.Id id) {
        return id.key().equals(ADD_NODE) || id.key().startsWith(ADD_NODE + "/");
    }

    private static ConfigurationNode start(GeneratedClassModel owner,
            GraphDiscoveryConfiguration.StartNode start,
            List<ConfigurationReferenceInventory.Reference> references) {
        List<ConfigurationNode> children = new ArrayList<>();
        String caption = "Start";
        if (start == null) {
            children.add(node(owner, "start/add", ConfigurationNode.Kind.AVAILABLE_SLOT,
                    "+ Choose start class or population", START, List.of()));
        } else {
            caption += start.populationSelection().isBlank()
                    ? ": class " + start.qidSourceClass()
                    : ": population " + start.populationSelection();
            reference(references, start.populationSelection().isBlank()
                            ? ConfigurationReferenceInventory.Relation.GRAPH_START_CLASS
                            : ConfigurationReferenceInventory.Relation.GRAPH_START_POPULATION,
                    "start/reference", START).ifPresent(children::add);
            children.add(node(owner, "start/use", ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "Use as: " + use(start.use()), START, List.of()));
        }
        return node(owner, "start", ConfigurationNode.Kind.STORED_CONFIGURATION,
                caption, START, children);
    }

    private static ConfigurationNode path(GeneratedClassModel owner,
            List<GraphDiscoveryConfiguration.NextNode> path,
            List<ConfigurationReferenceInventory.Reference> references) {
        List<ConfigurationNode> children = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            children.add(pathNode(owner, path.get(index), index, references));
        }
        children.add(node(owner, ADD_NODE, ConfigurationNode.Kind.AVAILABLE_SLOT,
                "+ Add next node", EDGE, List.of(
                        node(owner, ADD_NODE + "/edge",
                                ConfigurationNode.Kind.AVAILABLE_SLOT,
                                "Incoming edge", EDGE, List.of()),
                        node(owner, ADD_NODE + "/use",
                                ConfigurationNode.Kind.AVAILABLE_SLOT,
                                "Use as", NODE, List.of()),
                        node(owner, ADD_NODE + "/evidence",
                                ConfigurationNode.Kind.AVAILABLE_SLOT,
                                "Evidence", EVIDENCE, List.of()))));
        return node(owner, "path", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Path — " + path.size() + " node" + (path.size() == 1 ? "" : "s"),
                PATH, children);
    }

    private static ConfigurationNode pathNode(GeneratedClassModel owner,
            GraphDiscoveryConfiguration.NextNode value, int index,
            List<ConfigurationReferenceInventory.Reference> references) {
        ConfigurationReferenceInventory.Site site =
                ConfigurationReferenceInventory.graphNodeSite(owner, index);
        String prefix = site.fieldPath();
        List<ConfigurationReferenceInventory.Reference> nodeReferences = references.stream()
                .filter(reference -> reference.source().equals(site)).toList();
        List<ConfigurationNode> children = new ArrayList<>();
        List<ConfigurationNode> edgeChildren = new ArrayList<>();
        for (int alternative = 0; alternative < value.alternativeEdges().size(); alternative++) {
            GraphDiscoveryConfiguration.Edge edge = value.alternativeEdges().get(alternative);
            edgeChildren.add(node(owner, prefix + "/edge/alternative:" + alternative,
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "Alternative: " + edge.property().relationId() + " "
                            + direction(edge.direction()), EDGE, List.of()));
        }
        children.add(node(owner, prefix + "/edge",
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Incoming edge: " + value.property().relationId() + " "
                        + direction(value.directionFromPrevious()), EDGE, edgeChildren));

        List<ConfigurationNode> useChildren = new ArrayList<>();
        if (value.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION) {
            reference(nodeReferences, ConfigurationReferenceInventory.Relation.GRAPH_OUTPUT_CLASS,
                    prefix + "/output-reference", NODE).ifPresent(useChildren::add);
        }
        children.add(node(owner, prefix + "/use",
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                value.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                        ? "Output: " + value.populationClass() + " ("
                                + value.populationOperation().name().toLowerCase() + ")"
                        : "Use as: intermediate only",
                NODE, useChildren));

        if (!value.admissionPopulationSelection().isBlank()) {
            reference(nodeReferences,
                    ConfigurationReferenceInventory.Relation.GRAPH_ADMISSION_POPULATION,
                    prefix + "/admission-reference", NODE).ifPresentOrElse(
                            children::add,
                            () -> children.add(node(owner, prefix + "/admission",
                                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                                    "Admit: " + value.admissionPopulationSelection(),
                                    NODE, List.of())));
        } else {
            children.add(node(owner, prefix + "/admission",
                    ConfigurationNode.Kind.AVAILABLE_SLOT,
                    "+ Limit admission to a population", NODE, List.of()));
        }
        if (value.repeatUntilStable()) {
            children.add(node(owner, prefix + "/repeat",
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "Repeat until stable", NODE, List.of()));
        }
        children.add(evidence(owner, prefix, value.evidenceCondition()));

        return node(owner, prefix, ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Node " + (index + 1) + " — " + value.property().relationId() + " "
                        + direction(value.directionFromPrevious()), NODE, children);
    }

    private static ConfigurationNode evidence(GeneratedClassModel owner, String prefix,
            GraphEvidenceCondition evidence) {
        List<ConfigurationNode> children = new ArrayList<>();
        if (evidence == null) {
            children.add(node(owner, prefix + "/evidence/add",
                    ConfigurationNode.Kind.AVAILABLE_SLOT,
                    "+ Add evidence", EVIDENCE, List.of()));
        } else {
            for (int index = 0; index < evidence.evidencePaths().size(); index++) {
                GraphPath path = evidence.evidencePaths().get(index);
                children.add(node(owner, prefix + "/evidence/path:" + index,
                        ConfigurationNode.Kind.STORED_CONFIGURATION,
                        "Relation: " + path.relation().relationId() + " "
                                + direction(path.direction()), EVIDENCE, List.of()));
            }
            for (int index = 0; index < evidence.tests().size(); index++) {
                children.add(node(owner, prefix + "/evidence/test:" + index,
                        ConfigurationNode.Kind.STORED_CONFIGURATION,
                        "Test: " + evidenceTestCaption(
                                evidence.tests().get(index), Function.identity(), Function.identity()),
                        EVIDENCE, List.of()));
            }
            children.add(node(owner, prefix + "/evidence/disposition",
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "Incomplete evidence: " + reviewDispositionCaption(evidence.reviewDisposition()),
                    EVIDENCE, List.of()));
        }
        return node(owner, prefix + "/evidence",
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                evidence == null ? "Evidence" : "Evidence: " + evidence.name(),
                EVIDENCE, children);
    }

    public static String evidenceTestCaption(GraphNodeCondition condition,
            Function<String, String> propertyLabel, Function<String, String> entityLabel) {
        String edge = propertyLabel.apply(condition.relation().relationId())
                + " " + direction(condition.direction());
        return switch (condition) {
            case GraphRelationExists ignored -> edge + " has a value";
            case GraphRelationAbsent ignored -> edge + " has no value";
            case GraphRelationReaches reaches -> edge + " reaches "
                    + entityLabel.apply(reaches.entity().id());
            case GraphRelationReachesUnder reaches -> edge + " reaches "
                    + entityLabel.apply(reaches.entity().id()) + " or anything under it via "
                    + propertyLabel.apply(reaches.via().relationId());
            case GraphNodeIsUnder under -> "Node is "
                    + entityLabel.apply(under.entity().id()) + " or anything under it via "
                    + propertyLabel.apply(under.via().relationId());
        };
    }

    public static String reviewDispositionCaption(
            GraphEvidenceCondition.ReviewDisposition disposition) {
        return switch (disposition) {
            case AWAIT_DECISION -> "Wait for a manual decision before applying";
            case INCLUDE_AND_REPORT -> "Include unless manually rejected";
            case EXCLUDE_AND_REPORT -> "Exclude unless manually accepted";
        };
    }

    private static Optional<ConfigurationNode> reference(
            List<ConfigurationReferenceInventory.Reference> references,
            ConfigurationReferenceInventory.Relation relation,
            String key, String detail) {
        return references.stream().filter(value -> value.relation() == relation)
                .findFirst().map(value -> new ConfigurationNode(
                        new ConfigurationNode.Id(value.source().declarationId(), "", key),
                        ConfigurationNode.Kind.REFERENCE,
                        value.role() + ": " + value.target().displayName(), detail,
                        value.target(), List.of()));
    }

    private static ConfigurationNode node(GeneratedClassModel owner, String key,
            ConfigurationNode.Kind kind, String caption, String detail,
            List<ConfigurationNode> children) {
        return new ConfigurationNode(new ConfigurationNode.Id(
                owner.declarationId(), "", key), kind, caption, detail, children);
    }

    private static String use(GraphDiscoveryConfiguration.NodeUse use) {
        return use == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION
                ? "members of configured class" : "intermediate only";
    }

    private static String direction(GraphTraversalDirection direction) {
        return direction == GraphTraversalDirection.INCOMING ? "in" : "out";
    }
}
