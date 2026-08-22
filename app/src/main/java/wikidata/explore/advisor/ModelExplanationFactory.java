package wikidata.explore.advisor;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSemantics;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Derives Guide/graph explanations from the model constructs used by generation. */
public final class ModelExplanationFactory {

    private ModelExplanationFactory() { }

    public static ModelElementExplanation explain(DecisionContext context) {
        if (context == null || context.project() == null) {
            return emptyModel();
        }
        if (context.field() != null && context.clazz() != null) {
            return explainField(context.project(), context.clazz(), context.field());
        }
        if (context.clazz() != null) {
            return explainClass(context.project(), context.clazz());
        }
        return explainModel(context.project());
    }

    public static ModelElementExplanation explainModel(GeneratedProjectModel project) {
        if (project == null) {
            return emptyModel();
        }
        int classes = project.classes().size();
        int fields = project.classes().stream()
                            .filter(java.util.Objects::nonNull)
                            .mapToInt(c -> c.fields().size())
                            .sum();
        List<String> advice = new ArrayList<>();
        long unmapped = project.classes().stream()
                               .filter(java.util.Objects::nonNull)
                               .flatMap(c -> c.fields().stream())
                               .filter(java.util.Objects::nonNull)
                               .filter(f -> f.mapping().propertyPid().isBlank())
                               .count();
        if (unmapped > 0) {
            advice.add(unmapped + " field" + plural(unmapped)
                               + " do not yet have a source property.");
        }
        if (advice.isEmpty()) {
            advice.add("Select a class or field for construction-specific guidance.");
        }
        return new ModelElementExplanation(
                ModelElementExplanation.Scope.MODEL,
                project.name(),
                project.name(),
                "Build the domain model and its extraction routes.",
                classes + " class" + plural(classes) + ", "
                        + fields + " field" + plural(fields),
                List.of(),
                "Select a field to see the concrete RDF triple used to populate it.",
                advice);
    }

    public static ModelElementExplanation explainClass(
            GeneratedProjectModel project,
            GeneratedClassModel clazz) {

        FieldSourceMapping source = clazz.effectiveInstanceMapping(project);
        List<SourceRouteExplanation> routes = source.propertyPid().isBlank()
                ? List.of()
                : List.of(route(1, source, classExample(clazz, source), false,
                                source.direction()));
        List<String> advice = new ArrayList<>();
        if (source.propertyPid().isBlank() && clazz.seedQids().isEmpty()) {
            advice.add("Configure a membership property and target, or add seed QIDs.");
        }
        if (clazz.generationDepth() > 1) {
            advice.add("Depth " + clazz.generationDepth()
                               + " enables recursive construction; verify each relationship is bounded.");
        }
        if (advice.isEmpty()) {
            advice.add("Select a field to inspect its value source, direction and cardinality.");
        }
        String shape = clazz.fields().size() + " declared field"
                + plural(clazz.fields().size()) + ", depth " + clazz.generationDepth();
        return new ModelElementExplanation(
                ModelElementExplanation.Scope.CLASS,
                project.name() + " › " + clazz.className(),
                clazz.className(),
                "Gather and construct " + clazz.className() + " entities.",
                shape,
                routes,
                routes.isEmpty() ? "" : routes.getFirst().example(),
                advice);
    }

    public static ModelElementExplanation explainField(
            GeneratedProjectModel project,
            GeneratedClassModel clazz,
            GeneratedFieldModel field) {

        FieldSourceMapping source = field.mapping();
        RuleDirection effectiveDirection = FieldSemantics.effectiveDirection(field);
        String example = fieldExample(clazz, field, source, effectiveDirection);
        List<SourceRouteExplanation> routes = source.propertyPid().isBlank()
                ? List.of()
                : List.of(route(1, source, example, false, effectiveDirection));
        List<String> advice = new ArrayList<>();

        if (source.propertyPid().isBlank()) {
            advice.add("Choose the property that supplies this field.");
        }
        if (field.cardinality() == FieldCardinality.AUTO) {
            advice.add("Cardinality is auto-detected; sample the property before relying on a single value.");
        }
        if (!source.sourceType().implementedNow()) {
            advice.add(source.sourceType() + " is configured but not implemented yet.");
        }
        if (effectiveDirection != source.direction()) {
            advice.add("Scalar literal fields are queried as outgoing triples; the configured "
                               + source.direction() + " direction is not used by generation.");
        }
        if (source.sourceType().filledAfterExtraction()) {
            advice.add(source.sourceType() + " supplies this field after Wikidata extraction; "
                               + "preserve its source in review results.");
        }
        if (field.expectation() == wikidata.explore.model.FieldExpectation.EXPECTED) {
            advice.add("Missing values are reported but retained.");
        } else if (field.expectation() == wikidata.explore.model.FieldExpectation.REQUIRED) {
            advice.add("Rows missing this field are removed after transformation.");
        }
        if (advice.isEmpty()) {
            advice.add("Run a sample and verify that the example direction and result shape match the intended field.");
        }

        return new ModelElementExplanation(
                ModelElementExplanation.Scope.FIELD,
                project.name() + " › " + clazz.className() + " › " + field.name(),
                clazz.className() + "." + field.name(),
                "Populate " + clazz.className() + "." + field.name()
                        + " from " + source.sourceType() + ".",
                field.displayType() + (field.required() ? ", required during extraction" : ", optional during extraction"),
                routes,
                example,
                advice);
    }

    private static SourceRouteExplanation route(
            int priority,
            FieldSourceMapping source,
            String example,
            boolean fallback,
            RuleDirection direction) {
        return new SourceRouteExplanation(
                priority,
                source.sourceType(),
                source.propertyPid(),
                source.propertyLabel(),
                direction,
                example,
                fallback);
    }

    private static String classExample(
            GeneratedClassModel clazz,
            FieldSourceMapping source) {
        String entity = "?" + variable(clazz.className());
        String target = source.sourceQid().isBlank()
                ? "?membershipTarget" : "wd:" + source.sourceQid();
        return source.direction().triplePattern(target, entity, source.propertyPid());
    }

    private static String fieldExample(
            GeneratedClassModel clazz,
            GeneratedFieldModel field,
            FieldSourceMapping source,
            RuleDirection direction) {
        String entity = "?" + variable(clazz.className());
        String value = "?" + variable(field.name());
        if (source.propertyPid().isBlank()) {
            return entity + " → " + value + " (property not configured)";
        }
        if (source.sourceType() == FieldSourceType.DBPEDIA) {
            return entity + " dbo:" + source.propertyPid() + " " + value + " .";
        }
        if (source.sourceType() == FieldSourceType.WIKIPEDIA_INFOBOX) {
            // Not a triple at all: showing one claimed a Wikidata statement would be
            // queried for a value no SPARQL query will ever ask about.
            var key = datasource.evidence.InfoboxParameters.Key.parse(source.propertyPid());
            return key == null
                    ? entity + " → " + value + " (infobox parameter not configured)"
                    : entity + " enwiki {{" + key.template() + "}} | "
                            + key.parameter() + " = " + value;
        }
        return direction.triplePattern(entity, value, source.propertyPid());
    }

    private static String variable(String value) {
        String cleaned = value == null ? "value"
                : value.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isBlank()) {
            return "value";
        }
        return cleaned.substring(0, 1).toLowerCase(Locale.ROOT) + cleaned.substring(1);
    }

    private static String plural(long count) {
        return count == 1 ? "" : "s";
    }

    private static ModelElementExplanation emptyModel() {
        return new ModelElementExplanation(
                ModelElementExplanation.Scope.MODEL,
                "Model",
                "Model",
                "Select or load a model.",
                "",
                List.of(),
                "",
                List.of());
    }
}
