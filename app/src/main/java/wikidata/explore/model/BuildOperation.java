package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One step of a project's build: run, apply or save something the model declares
 * (directive 23). A declaration says WHAT; an operation says to run it.
 *
 * <p>A project's operations are an ordered list, and the order is the stored sequence a
 * build follows. Cross-project prerequisites are explicit {@link Kind#BUILD_DEPENDENCY}
 * operations; arbitrary same-project dependency edges are not modelled yet, so list order
 * remains authoritative inside one project.
 *
 * <p>An operation on a graph names its graph class by declaration id, so renaming the class
 * does not detach the step.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class BuildOperation {

    /** What an operation does. */
    public enum Kind {
        GENERATE_PROJECT("Generate project", Target.NONE),
        BUILD_DEPENDENCY("Build dependency", Target.PROJECT),
        RUN_TRANSFORMATION("Run transformation", Target.CLASS),
        PUBLISH_POPULATION("Publish population", Target.SELECTION),
        RUN_GRAPH_CONSTRAINT("Run graph", Target.GRAPH),
        APPLY_GRAPH_DECISIONS("Apply graph decisions", Target.GRAPH),
        SAVE_PROJECT_RESULT("Save project", Target.NONE);

        public enum Target { NONE, PROJECT, CLASS, GRAPH, SELECTION }

        private final String label;
        private final Target target;

        Kind(String label, Target target) {
            this.label = label;
            this.target = target;
        }

        public String label() { return label; }

        /** Whether the operation acts on one graph class, named by its target. */
        public Target target() { return target; }
        public boolean targetsGraph() { return target == Target.GRAPH; }
        public boolean requiresTarget() { return target != Target.NONE; }
    }

    private String declarationId = DeclarationIds.create();
    private Kind kind = Kind.GENERATE_PROJECT;
    private String targetDeclarationId = "";
    private String targetProjectName = "";

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

    public String targetProjectName() {
        return targetProjectName == null ? "" : targetProjectName.trim();
    }
    public void targetProjectName(String value) {
        targetProjectName = value == null ? "" : value.trim();
    }

    public static BuildOperation dependency(String projectName) {
        BuildOperation operation = new BuildOperation(Kind.BUILD_DEPENDENCY, "");
        operation.targetProjectName(projectName);
        return operation;
    }

    /** The class this operation targets in {@code project}, or null when it names none or
     *  one the project no longer declares. */
    public GeneratedClassModel target(GeneratedProjectModel project) {
        if (project == null
                || (kind().target() != Kind.Target.GRAPH
                        && kind().target() != Kind.Target.CLASS)
                || targetDeclarationId().isBlank()) {
            return null;
        }
        return project.classes().stream()
                .filter(clazz -> clazz != null
                        && targetDeclarationId().equals(clazz.declarationId()))
                .findFirst().orElse(null);
    }

    public Selection selectionTarget(GeneratedProjectModel project) {
        return project == null || kind().target() != Kind.Target.SELECTION
                ? null : project.findSelectionById(targetDeclarationId());
    }

    /** How the operation reads in {@code project}: its kind, and the graph it acts on. */
    public String describe(GeneratedProjectModel project) {
        if (!kind().requiresTarget()) return kind().label();
        String name = switch (kind().target()) {
            case GRAPH -> target(project) == null ? "" : target(project).className();
            case PROJECT -> targetProjectName();
            case CLASS -> target(project) == null ? "" : target(project).className();
            case SELECTION -> selectionTarget(project) == null ? ""
                    : selectionTarget(project).name();
            case NONE -> "";
        };
        return kind().label() + " " + (name.isBlank()
                ? "(missing target " + targetDeclarationId() + ")" : name);
    }

    public BuildOperation copy() {
        BuildOperation copy = new BuildOperation(kind, targetDeclarationId);
        copy.declarationId = declarationId;
        copy.targetProjectName = targetProjectName;
        return copy;
    }
}
