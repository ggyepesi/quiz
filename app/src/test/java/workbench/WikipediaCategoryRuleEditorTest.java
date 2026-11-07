package workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.CategoryCandidatePolicy;
import wikidata.explore.model.WikipediaCategoryMapping;
import wikidata.explore.model.WikipediaCategoryRule;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ModelBuilder and Transform edit the same complete category rule component. */
class WikipediaCategoryRuleEditorTest {

    @Test void openingTheEditorPreservesPatternsMappingsAndPolicy() {
        WikipediaCategoryRule configured = new WikipediaCategoryRule();
        configured.pattern("<value> deities");
        configured.policy(CategoryCandidatePolicy.EVIDENCE_ONLY);
        configured.mappings().add(
                new WikipediaCategoryMapping("Falcon deities", "Q43489"));

        WikipediaCategoryRule shown =
                new WikipediaCategoryRuleEditor(configured, "Bird deities").rule();

        assertEquals(configured.pattern(), shown.pattern());
        assertEquals(configured.policy(), shown.policy());
        assertEquals(configured.mappings(), shown.mappings());
        assertEquals("<value> deities + 1 exact mapping — Corroborate existing values only",
                WikipediaCategoryRuleEditor.summary(shown));
    }
}
