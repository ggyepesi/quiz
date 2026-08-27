package wikidata.explore.model;

import datasource.graph.GraphExpansionPolicy;
import wikidata.WikidataIds;

/** One definition of whether a modeled field can declare a Wikidata graph edge. */
public final class WikidataFieldGraphTraversalEligibility {
    private WikidataFieldGraphTraversalEligibility() { }

    public static boolean hasTypedModeledTarget(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        return project != null && field != null
                && field.type() == FieldType.ENTITY
                && !field.entityClassName().isBlank()
                && project.findClass(field.entityClassName()) != null;
    }

    public static boolean hasPropertySource(GeneratedFieldModel field) {
        return field != null
                && field.mapping().sourceType() == FieldSourceType.SPARQL
                && WikidataIds.isPid(field.mapping().propertyPid());
    }

    public static boolean canCompile(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        return field != null
                && field.graphExpansionPolicy() != GraphExpansionPolicy.NONE
                && hasTypedModeledTarget(project, field)
                && hasPropertySource(field);
    }
}
