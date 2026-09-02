package wikidata.explore.model;

import datasource.schema.FieldType;

import datasource.graph.GraphExpansionPolicy;
import wikidata.WikidataIds;

/** One definition of whether a modeled field can declare a Wikidata graph edge. */
public final class WikidataFieldGraphTraversalEligibility {
    private WikidataFieldGraphTraversalEligibility() { }

    public static boolean hasTypedModeledTarget(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        return field != null && hasTypedModeledTarget(
                project, field.type(), field.entityClassName());
    }

    /**
     * The same question asked of parts rather than a saved field, because the field
     * editor must answer it from live controls before anything is applied. One rule,
     * two callers with different shapes — as with the inverse-field resolution.
     */
    public static boolean hasTypedModeledTarget(
            GeneratedProjectModel project, FieldType type, String entityClassName) {
        return project != null
                && type == FieldType.ENTITY
                && entityClassName != null && !entityClassName.isBlank()
                && project.findClass(entityClassName) != null;
    }

    public static boolean hasPropertySource(GeneratedFieldModel field) {
        return field != null && hasPropertySource(
                field.mapping().sourceType(), field.mapping().propertyPid());
    }

    /** As above, from parts. */
    public static boolean hasPropertySource(FieldSourceType sourceType, String pid) {
        return sourceType == FieldSourceType.SPARQL && WikidataIds.isPid(pid);
    }

    public static boolean canCompile(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        return field != null
                && field.graphExpansionPolicy() != GraphExpansionPolicy.NONE
                && canDeclare(project, field);
    }

    /** Eligibility before a policy has been selected. */
    public static boolean canDeclare(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        return field != null
                && hasTypedModeledTarget(project, field)
                && hasRecursiveTarget(project, field)
                && hasPropertySource(field);
    }

    /** A frontier node can be expanded through the same field only when it is again
     * an instance of the field's owner class. Cross-class paths belong to a composed
     * graph plan; treating their target seeds as coverage of this edge would be false. */
    public static boolean hasRecursiveTarget(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        GeneratedClassModel owner = project == null || field == null
                ? null : project.declaringClass(field);
        return owner != null && owner.className().equals(field.entityClassName());
    }

    /** The same declaration question asked from an editor's live controls. */
    public static boolean canDeclare(
            GeneratedProjectModel project,
            String ownerClassName,
            FieldType type,
            String entityClassName,
            FieldSourceType sourceType,
            String pid) {
        return hasTypedModeledTarget(project, type, entityClassName)
                && ownerClassName != null && ownerClassName.equals(entityClassName)
                && hasPropertySource(sourceType, pid);
    }
}
