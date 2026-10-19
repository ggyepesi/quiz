package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationsSavedModelTest {
    private static final File MODEL =
            new File("../data/wikidata/constellations/constellations.model.json");

    @Test void constellationUsesReusableDiscoveryAndArticleCorrespondence()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);
        GeneratedFieldModel discovery = model.findClass("Constellation").fields().stream()
                .filter(field -> field.name().equals("discovery"))
                .findFirst().orElseThrow();
        GeneratedClassModel constellation = model.findClass("Constellation");

        assertEquals("Discovery", discovery.entityClassName());
        assertEquals(FieldProductionKind.OWNED_COMPONENT,
                discovery.mapping().productionKind());
        org.junit.jupiter.api.Assertions.assertNull(
                model.findClass("ConstellationDiscovery"));
        assertEquals("Discovery", model.findClass("Discovery").importedFrom());
        assertEquals("Astronomy", model.findClass("CelestialBody").importedFrom());
        assertEquals("Astronomy", model.findClass("Star").importedFrom());
        assertEquals("CelestialBody", model.findClass("Star").baseClassName());
        assertEquals(java.util.List.of("Q8928", "Q4193029"),
                constellation.membership().qids());
        assertTrue(constellation.instanceMapping().excludedQids().contains("Q4158896"));
        assertTrue(constellation.fields().stream()
                .filter(field -> field.name().equals("abbreviation"))
                .findFirst().orElseThrow().required(),
                "the IAU abbreviation is the positive boundary of the 88-constellation set");
        assertTrue(model.entityRepresentationRules().stream().anyMatch(rule ->
                rule.roleClassName().equals("Owner")
                        && rule.representationClassName().equals("Constellation")));
        assertTrue(model.entityRepresentationRules().stream().anyMatch(rule ->
                rule.roleClassName().equals("Owner")
                        && rule.representationClassName().equals("CelestialBody")));
        assertNotNull(ClassSourceBindings.articleCorrespondence(
                model.findClass("Constellation")));
        assertTrue(ArticleCorrespondencePlan.classes(model,
                ModelSourceExecutionPlan.compile(model, datasource.Datasources.standard()))
                .contains("Star"));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
        assertEquals(java.util.List.of(BuildOperation.Kind.GENERATE_PROJECT,
                        BuildOperation.Kind.SAVE_PROJECT_RESULT),
                model.buildOperations().stream().map(BuildOperation::kind).toList(),
                "the saved configuration is executable without the desktop UI");

        wikidata.explore.rule.RuleNode rule =
                wikidata.explore.rule.RuleTreeCompiler.compileProject(model);
        String rootQuery = wikidata.explore.query.template.rule.RuleNodeQueryBuilder
                .valuesQuery(rule);
        assertTrue(rule.excludedQids().contains("Q4158896"));
        assertTrue(rootQuery.contains("FILTER(?value NOT IN (wd:Q4158896))"), rootQuery);

        wikidata.explore.rule.RuleNode stars = rule.edges().stream()
                .filter(edge -> edge.fieldName().equals("stars"))
                .findFirst().orElseThrow().childNode();
        assertEquals(new wikidata.QualifierCondition(
                        "P972", java.util.List.of("Q499138")),
                stars.membershipFilterQualifierCondition(),
                "the imported Star boundary reaches the Constellation.stars query");
    }
}
