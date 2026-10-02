package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One step of a project's build: run, apply or save something the model declares
 * (directive 22). A declaration says WHAT; an operation says to run it.
 *
 * <p>A project's operations are an ordered list, and the order is the stored sequence a
 * build follows. The design's named prerequisites between operations — one output feeding
 * several consumers — are not modelled yet; nothing a project can declare today needs them.
 *
 * <p>An operation on a graph names its graph class by declaration id, so renaming the class
 * does not detach the step.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class BuildOperation {

    /** What an operation does. The first vocabulary is the design's, without transformations. */
    public enum Kind {
        GENERATE_PROJECT("Generate project", false),
        RUN_GRAPH_CONSTRAINT("Run graph", true),
        APPLY_GRAPH_DECISIONS("Apply graph decisions", true),
        SAVE_PROJECT_RESULT("Save project", false);

        private final String label;
        private final boolean targetsGraph;

        Kind(String label, boolean targetsGraph) {
            this.label = label;
            this.targetsGraph = targetsGraph;
        }

        public String label() { return label; }

        /** Whether the operation acts on one graph class, named by its target. */
        public boolean targetsGraph() { return targetsGraph; }
    }

    private String declarationId = DeclarationIds.create();
    private Kind kind = Kind.GENERATE_PROJECT;
    private String targetDeclarationId = "";

    public BuildOperation() { }

    public BuildOperation(Kind kind, String targetDeclarationId) {
        kind(kind);
        targetDeclarationId(targetDeclarationId);
    }

    public String declarationId() { return DeclarationIds.clean(declarationId); }

    public Kind kind() { return kind == null ? Kind.GENERATE_PROJECT : kind; }
    public void kind(Kind value) { kind = value == null ? Kind.GENERATE_PROJECT : value; }

    /** The graph class acted on, for a kind that {@link Kind#targetsGraph() targets one}. */
    public String targetDeclarationId() { return DeclarationIds.clean(targetDeclarationId); }
    public void targetDeclarationId(String value) {
        targetDeclarationId = DeclarationIds.clean(value);
    }

    /** The class this operation targets in {@code project}, or null when it names none or
     *  one the project no longer declares. */
    public GeneratedClassModel target(GeneratedProjectModel project) {
        if (project == null || !kind().targetsGraph() || targetDeclarationId().isBlank()) {
            return null;
        }
        return project.classes().stream()
                .filter(clazz -> clazz != null
                        && targetDeclarationId().equals(clazz.declarationId()))
                .findFirst().orElse(null);
    }

    /** How the operation reads in {@code project}: its kind, and the graph it acts on. */
    public String describe(GeneratedProjectModel project) {
        if (!kind().targetsGraph()) return kind().label();
        GeneratedClassModel target = target(project);
        return kind().label() + " " + (target == null
                ? "(missing graph " + targetDeclarationId() + ")" : target.className());
    }

    public BuildOperation copy() {
        BuildOperation copy = new BuildOperation(kind, targetDeclarationId);
        copy.declarationId = declarationId;
        return copy;
    }
}
