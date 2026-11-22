package domain.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact maintained groups and title patterns are two authored forms of one category rule. */
class WikipediaCategoryRuleTest {

    @Test void anExactMappingWinsAndUsesItsConfiguredIdentity() {
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.pattern("<value> deities");
        rule.mappings().add(new WikipediaCategoryMapping(
                "Category:Falcon deities", "q43489"));

        WikipediaCategoryRule.Match exact = rule.match("Falcon deities");
        WikipediaCategoryRule.Match inferred = rule.match("Bird deities");

        assertEquals("Q43489", exact.valueQid());
        assertEquals("", exact.valueTitle());
        assertEquals("Bird", inferred.valueTitle());
        assertNull(rule.match("Egyptian gods"));
    }

    @Test void severalMaintainedGroupsMayMeanTheSameValue() {
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.mappings().add(new WikipediaCategoryMapping("Cat gods", "Q146"));
        rule.mappings().add(new WikipediaCategoryMapping("Cat goddesses", "Q146"));

        assertEquals("Q146", rule.match("Cat gods").valueQid());
        assertEquals("Q146", rule.match("Category:Cat goddesses").valueQid());
    }

    /** #463: the model rule and provider record convert through the rule itself, so an
     * incomplete exact mapping is dropped the same way on every model read. */
    @Test void theFieldBindingRoundTripsOneModelOwnedRule() {
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.pattern("<value> deities");
        rule.policy(CategoryCandidatePolicy.EVIDENCE_ONLY);
        rule.mappings().add(new WikipediaCategoryMapping("Cat gods", "Q146"));
        rule.mappings().add(new WikipediaCategoryMapping("Cat goddesses", "Q146"));
        rule.mappings().add(new WikipediaCategoryMapping("Unmapped group", ""));

        var binding = new domain.datasource.api.SourceBinding(
                domain.datasource.api.SourceBindingTarget.fieldValue(
                        "EgyptianDeity", "animal",
                        domain.datasource.api.SourceBindingSlot.CATEGORY_EVIDENCE),
                new domain.datasource.api.SourceRecipe(
                        domain.datasource.wikipedia.WikipediaDatasourceProvider.ID,
                        domain.datasource.wikipedia.WikipediaCategoryDiscoveryOperation.ID,
                        rule.recipeParameters()));
        GeneratedFieldModel field = new GeneratedClassModel("EgyptianDeity").addField(
                "animal", domain.datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
        FieldSourceBindings.put(field, binding);
        WikipediaCategoryRule viaBinding = field.wikipediaCategoryRule();

        assertEquals(rule.pattern(), viaBinding.pattern());
        assertEquals(rule.policy(), viaBinding.policy());
        assertEquals(java.util.List.of(new WikipediaCategoryMapping("Cat gods", "Q146"),
                new WikipediaCategoryMapping("Cat goddesses", "Q146")),
                viaBinding.mappings());
        assertEquals(binding.recipe().parameters(), rule.recipeParameters());
    }

    /** The forcing side: the provider record is built only by the rule's own conversion. */
    @Test void onlyTheRuleConvertsBetweenItsModelAndRecipeForms() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java");
        java.util.List<String> offenders;
        try (var files = java.nio.file.Files.walk(main)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("WikipediaCategoryRule.java")
                            && !path.endsWith("WikipediaDatasourceProvider.java"))
                    .filter(path -> {
                        try {
                            return java.nio.file.Files.readString(path)
                                    .contains("WikipediaDatasourceProvider.CategoryRule(");
                        } catch (java.io.IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .map(main::relativize).map(Object::toString).toList();
        }
        assertTrue(offenders.isEmpty(), "convert through WikipediaCategoryRule: " + offenders);
    }
}
