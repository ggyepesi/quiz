package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DescendantMembershipQueryTest {

    private static RuleNode position() {
        RuleNode node = new RuleNode("Position", "position");
        node.sourceQid("Q4164871");
        node.propertyPid("P31");
        node.membershipIncludesDescendants(true);
        node.requireSitelink(true);
        node.addExcludedQid("Q404");
        RuleIncludedField required = new RuleIncludedField(
                "jurisdiction", "P1001", "jurisdiction",
                RuleIncludedField.FieldKind.ENTITY, false);
        node.addMembershipConstraint(required);
        node.limit(200000);
        return node;
    }

    @Test void ordinaryAndBatchedBackbonesUseTheSameClosureAndFilters() {
        for (String query : java.util.List.of(
                RuleNodeQueryBuilder.valuesQuery(position()),
                RuleNodeQueryBuilder.membershipBackboneQueryNoLabel(position()))) {
            assertTrue(query.contains("VALUES ?membershipRoot { wd:Q4164871 }"), query);
            assertTrue(query.contains("?root wdt:P279* ?membershipRoot"), query);
            assertTrue(query.contains("?value wdt:P31 ?root"), query);
            assertTrue(query.contains("schema:about ?value"),
                    "sitelink membership filter must survive\n" + query);
            assertTrue(query.contains("wdt:P1001"),
                    "required fields must survive\n" + query);
            assertTrue(query.contains("Q404"),
                    "explicit exclusions must survive\n" + query);
            assertTrue(query.contains("LIMIT 200000"), query);
        }
    }

    @Test void aSubclassLayerIsItsOwnPopulation() {
        // P279 into "position", with the closure: every transitive subclass, which is
        // the class layer of a hierarchy rather than its instances. The closure bounds
        // the OBJECT, so the relation reaching it is free to be P279 as well.
        RuleNode node = new RuleNode("PositionKind", "positionKind");
        node.sourceQid("Q4164871");
        node.propertyPid("P279");
        node.membershipIncludesDescendants(true);

        String query = RuleNodeQueryBuilder.valuesQuery(node);

        assertTrue(query.contains("VALUES ?membershipRoot { wd:Q4164871 }"), query);
        assertTrue(query.contains("?root wdt:P279* ?membershipRoot"), query);
        assertTrue(query.contains("?value wdt:P279 ?root"),
                "the membership relation is P279 too — subclass of a subclass\n" + query);
    }

    @Test void ruleCompilationCarriesTheAuthoredClosure() {
        wikidata.explore.model.GeneratedClassModel editable =
                new wikidata.explore.model.GeneratedClassModel("Position");
        editable.membership(wikidata.explore.model.EntityBound.relation(
                "P31", java.util.List.of("Q4164871"), true));

        assertTrue(wikidata.explore.rule.RuleTreeCompiler.compileClass(editable)
                .membershipIncludesDescendants());
    }
}
