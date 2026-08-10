package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership is decided by TWO different queries — {@code valuesQuery} for a single-root
 * class and {@code membershipBackboneQuery} for a multi-root one — and they must decide
 * it the same way.
 *
 * <p>They did not. {@code backboneCopy} carefully carries the required-field constraints
 * and the class ranking, and the batched builder dropped both, so a multi-root class got
 * an unranked slice whose members need not carry the required property — the same defect
 * that produced 40% location-less movies on the single-root path, surviving in the other
 * builder.
 */
class BackbonePathParityTest {

    private static RuleNode rankedNodeWithRequiredField(boolean multiRoot) {
        RuleNode node = new RuleNode("Movies", "movies");
        node.sourceQid("Q11424");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(20000);
        node.rankBySitelinks(true);
        node.rankDescending(true);
        if (multiRoot) {
            node.addAdditionalSourceQid("Q24862");    // short film
            node.addAdditionalSourceQid("Q202866");   // animated film
        }

        RuleIncludedField required = new RuleIncludedField(
                "locations", "P840", "narrative location",
                RuleIncludedField.FieldKind.ENTITY, /* optional */ false);
        required.collection(true);
        required.direction(RuleDirection.ROOT_TO_ITEM);
        node.addIncludedField(required);
        return node;
    }

    /** What membership must look like, whichever builder produced it. */
    private static void assertDecidesMembershipTheSameWay(String query, String path) {
        assertTrue(query.contains("wdt:P840"),
                path + ": the required field must constrain membership\n" + query);
        assertFalse(query.contains("OPTIONAL"),
                path + ": a required field is not optional\n" + query);
        assertFalse(query.contains("?locations"),
                path + ": it is a constraint, not a selected value — selecting a "
                        + "multi-valued property makes the LIMIT count pairs\n" + query);
        assertTrue(query.contains("wikibase:sitelinks"),
                path + ": the ranking decides WHICH members are kept\n" + query);
        assertTrue(query.contains("ORDER BY DESC"), path + ": ranking order\n" + query);
    }

    @Test void theSingleRootPathConstrainsAndRanks() {
        assertDecidesMembershipTheSameWay(
                RuleNodeQueryBuilder.valuesQuery(
                        rankedNodeWithRequiredField(false).backboneCopy(20000)),
                "valuesQuery");
    }

    @Test void theBatchedPathConstrainsAndRanksIdentically() {
        assertDecidesMembershipTheSameWay(
                RuleNodeQueryBuilder.membershipBackboneQuery(
                        rankedNodeWithRequiredField(true).backboneCopy(20000)),
                "membershipBackboneQuery");
    }

    @Test void theNoLabelBatchedPathAlsoConstrainsAndRanks() {
        assertDecidesMembershipTheSameWay(
                RuleNodeQueryBuilder.membershipBackboneQueryNoLabel(
                        rankedNodeWithRequiredField(true).backboneCopy(20000)),
                "membershipBackboneQueryNoLabel");
    }

    /** An unranked node still gets its plain LIMIT on every path. */
    @Test void anUnrankedNodeKeepsItsLimit() {
        RuleNode node = rankedNodeWithRequiredField(true);
        node.rankBySitelinks(false);
        RuleNode backbone = node.backboneCopy(500);

        assertTrue(RuleNodeQueryBuilder.membershipBackboneQuery(backbone)
                           .contains("LIMIT 500"));
        assertFalse(RuleNodeQueryBuilder.membershipBackboneQuery(backbone)
                            .contains("wikibase:sitelinks"));
    }
}
