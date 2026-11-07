package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
