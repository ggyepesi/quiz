package wikidata.explore.workbench;

import wikidata.explore.filter.WikidataValueFilterOperator;
import wikidata.explore.model.EdgeMembershipMode;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldRenderMode;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import java.util.List;

/**
 * Curated, explained field-configuration recipes — each is a pattern validated
 * while building the constellations/stars project, so they double as living
 * documentation. Keep this set small and high-value (it can rot as the model
 * evolves).
 */
public final class FieldRecipes {

    private FieldRecipes() {}

    public static List<FieldRecipe> all() {
        return List.of(
                brightNamedStars(),
                wikipediaBrightestStar(),
                wikipediaMainStarCount(),
                borderingReferences(),
                skyChartImage());
    }

    /** Worked recipes own and evaluate their own prerequisites. */
    public static List<FieldRecipe> applicableTo(
            GeneratedProjectModel project, GeneratedFieldModel field) {
        if (project == null || field == null) return List.of();
        return all().stream().filter(recipe -> recipe.appliesTo(field, project)).toList();
    }

    private static FieldRecipe brightNamedStars() {
        return new FieldRecipe(
                "A constellation's brightest named stars",
                "Fetch each constellation's brightest English-named stars as child objects.",
                List.of(
                        new FieldRecipe.Step("Type / Shape", "Object (Star), list",
                                "A constellation holds several stars."),
                        new FieldRecipe.Step("Load as", "Child objects",
                                "Fetch full Star objects with their own fields, not just names."),
                        new FieldRecipe.Step("Property / Direction", "P59 (constellation), item → root",
                                "A star points to its constellation via P59."),
                        new FieldRecipe.Step("Edge membership", "NONE",
                                "Don't require P31 = star: the brightest are often a SUBCLASS "
                                        + "(variable/double star), so requiring exactly 'star' drops them."),
                        new FieldRecipe.Step("Numeric filter (on Star.apparentMagnitude)", "≤ 6",
                                "Keep naked-eye stars; drops faint catalog dumps. This lives on the "
                                        + "Star class field, not the edge."),
                        new FieldRecipe.Step("Sort children by", "apparentMagnitude ascending",
                                "Brightest first, so the limit keeps the brightest — not arbitrary."),
                        new FieldRecipe.Step("Limit per parent", "6",
                                "A handful of bright stars per constellation.")),
                (field, project) -> isConstellationField(field, project)
                        && project.findClass("Star") != null,
                (f, project) -> {
                    f.type(FieldType.ENTITY);
                    f.cardinality(FieldCardinality.COLLECTION);
                    f.entityClassName("Star");
                    f.renderMode(FieldRenderMode.AUTO);
                    f.edgeMembership(EdgeMembershipMode.NONE);
                    f.sortFieldName("apparentMagnitude");
                    f.sortDescending(false);
                    FieldSourceMapping m = f.mapping();
                    m.sourceType(FieldSourceType.SPARQL);
                    m.propertyPid("P59");
                    m.propertyLabel("constellation");
                    m.direction(RuleDirection.ITEM_TO_ROOT);
                    m.productionKind(FieldProductionKind.CHILD_OBJECTS);
                    m.limit(6);
                    // The magnitude filter is class-scoped: set it on Star's field.
                    GeneratedFieldModel mag = childField(project, "Star", "apparentMagnitude");
                    if (mag != null) {
                        mag.filterOperator(WikidataValueFilterOperator.LE);
                        mag.filterValue(6.0);
                    }
                });
    }

    private static FieldRecipe wikipediaBrightestStar() {
        return new FieldRecipe(
                "Brightest star name (from Wikipedia)",
                "A constellation's brightest star, from the Wikipedia infobox (not in Wikidata).",
                List.of(
                        new FieldRecipe.Step("Source", "DBpedia (Wikipedia infobox)",
                                "This fact lives in the infobox, not Wikidata."),
                        new FieldRecipe.Step("Property", "brighteststarname",
                                "The infobox field. Use \"Discover properties\" to browse the rest."),
                        new FieldRecipe.Step("Type", "String",
                                "A name; the resource is resolved to its English label."),
                        new FieldRecipe.Step("Join", "owl:sameAs by QID",
                                "DBpedia is matched to each entity after the Wikidata extraction.")),
                FieldRecipes::isConstellationField,
                (f, project) -> {
                    f.type(FieldType.STRING);
                    f.cardinality(FieldCardinality.SINGLE);
                    f.mapping().sourceType(FieldSourceType.DBPEDIA);
                    f.mapping().propertyPid("brighteststarname");
                    f.mapping().propertyLabel("brightest star");
                });
    }

    private static FieldRecipe wikipediaMainStarCount() {
        return new FieldRecipe(
                "Number of main stars (from Wikipedia)",
                "The constellation's main-star count from the Wikipedia infobox.",
                List.of(
                        new FieldRecipe.Step("Source", "DBpedia (Wikipedia infobox)",
                                "Counts like this aren't in Wikidata."),
                        new FieldRecipe.Step("Property", "numbermainstars",
                                "The infobox field."),
                        new FieldRecipe.Step("Type", "Number",
                                "A plain integer from the infobox.")),
                FieldRecipes::isConstellationField,
                (f, project) -> {
                    f.type(FieldType.NUMBER);
                    f.cardinality(FieldCardinality.SINGLE);
                    f.mapping().sourceType(FieldSourceType.DBPEDIA);
                    f.mapping().propertyPid("numbermainstars");
                    f.mapping().propertyLabel("main stars");
                });
    }

    private static FieldRecipe borderingReferences() {
        return new FieldRecipe(
                "Bordering constellations (clickable references)",
                "The neighbouring constellations, as links you can navigate to.",
                List.of(
                        new FieldRecipe.Step("Type / Shape", "Object (Constellation), list",
                                "A constellation borders several others."),
                        new FieldRecipe.Step("Property", "P47 (shares border with)",
                                "Wikidata's adjacency property."),
                        new FieldRecipe.Step("Render mode", "Reference",
                                "Show each as a clickable link to that constellation, not inline.")),
                FieldRecipes::isConstellationField,
                (f, project) -> {
                    GeneratedClassModel owner = project.declaringClass(f);
                    f.type(FieldType.ENTITY);
                    f.cardinality(FieldCardinality.COLLECTION);
                    f.entityClassName(owner.className());
                    f.renderMode(FieldRenderMode.REFERENCE);
                    f.mapping().sourceType(FieldSourceType.SPARQL);
                    f.mapping().propertyPid("P47");
                    f.mapping().propertyLabel("shares border with");
                    f.mapping().direction(RuleDirection.ITEM_TO_ROOT);
                });
    }

    private static FieldRecipe skyChartImage() {
        return new FieldRecipe(
                "Sky chart image",
                "The constellation's chart/illustration image.",
                List.of(
                        new FieldRecipe.Step("Type", "Image",
                                "Rendered as a picture, not a filename."),
                        new FieldRecipe.Step("Property", "P18 (image)",
                                "Wikidata's image property; the media file is resolved to a URL.")),
                FieldRecipes::isConstellationField,
                (f, project) -> {
                    f.type(FieldType.IMAGE);
                    f.cardinality(FieldCardinality.SINGLE);
                    f.mapping().sourceType(FieldSourceType.SPARQL);
                    f.mapping().propertyPid("P18");
                    f.mapping().propertyLabel("image");
                });
    }

    /**
     * Q8928 is a prerequisite of these Wikidata recipes, not the Java/model class
     * name. This keeps the worked examples available when the user renames the
     * class while declining them for an unrelated root such as Person.
     */
    private static boolean isConstellationField(
            GeneratedFieldModel field, GeneratedProjectModel project) {
        GeneratedClassModel owner = project == null ? null : project.declaringClass(field);
        return owner != null && owner.instanceMapping() != null
                && "Q8928".equals(owner.instanceMapping().sourceQid());
    }

    private static GeneratedFieldModel childField(
            GeneratedProjectModel project, String className, String fieldName) {
        if (project == null) {
            return null;
        }
        GeneratedClassModel cls = project.findClass(className);
        if (cls == null) {
            return null;
        }
        for (GeneratedFieldModel f : cls.fields()) {
            if (f != null && fieldName.equalsIgnoreCase(f.name())) {
                return f;
            }
        }
        return null;
    }
}
