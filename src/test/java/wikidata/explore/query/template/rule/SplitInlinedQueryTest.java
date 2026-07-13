package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleIncludedField.FieldKind;
import wikidata.explore.rule.RuleNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitInlinedQueryTest {

    private static RuleIncludedField entityList(String name, String pid) {
        RuleIncludedField f = new RuleIncludedField(name, pid, name, FieldKind.ENTITY, true);
        f.collection(true);   // inlineable = ENTITY + collection + PID
        return f;
    }

    private static RuleNode rootNode() {
        RuleNode node = new RuleNode("Thing", "?value");
        node.sourceQid("Q5");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);   // ?value wdt:P31 wd:Q5
        return node;
    }

    @Test void twoInlinedFieldsSplitIntoSeparateSubqueries() {
        RuleNode node = rootNode();
        node.addIncludedField(entityList("cast", "P161"));
        node.addIncludedField(entityList("genres", "P136"));

        String q = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(node);

        // Bounded base + one GROUP_CONCAT subquery per field + OPTIONAL-INCLUDE join.
        assertTrue(q.contains("} AS %values"), q);
        assertTrue(q.contains("AS %cast_inlined"), q);
        assertTrue(q.contains("AS %genres_inlined"), q);
        assertTrue(q.contains("GROUP_CONCAT(DISTINCT ?cast_pair"), q);
        assertTrue(q.contains("GROUP_CONCAT(DISTINCT ?genres_pair"), q);
        assertTrue(q.contains("OPTIONAL { INCLUDE %cast_inlined"), q);
        assertTrue(q.contains("OPTIONAL { INCLUDE %genres_inlined"), q);

        // The two concats must NOT share a single subquery (the cross-product):
        // there are exactly two GROUP_CONCAT occurrences, each in its own WITH.
        int concats = q.split("GROUP_CONCAT", -1).length - 1;
        assertTrue(concats == 2, "expected 2 GROUP_CONCATs, got " + concats + "\n" + q);
        assertFalse(q.contains("?cast_inlined) (GROUP_CONCAT"), "concats stacked in one subquery\n" + q);
    }

    @Test void inlinedFieldOnTheMembershipPredicateReusesACategoriesSubquery() {
        RuleNode node = new RuleNode("Nomination", "?value");
        node.sourceQid("Q102427");
        node.addAdditionalSourceQid("Q103360");         // multi-QID membership set
        node.propertyPid("P1411");
        node.direction(RuleDirection.ITEM_TO_ROOT);      // ?value wdt:P1411 <category>
        node.addIncludedField(entityList("type", "P31"));           // different predicate
        node.addIncludedField(entityList("target", "P1411"));       // SAME predicate as membership

        String q = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(node);

        // The membership set is hoisted and reused by base + the matching field.
        assertTrue(q.contains("} AS %categories"), q);
        assertTrue(q.contains("VALUES ?category"), q);
        assertTrue(q.contains("INCLUDE %categories"), q);
        // target (same predicate) concats the category from %categories, no self-join.
        assertTrue(q.contains("BIND(CONCAT(STR(?category)"), q);
        // type (different predicate) still self-traverses from ?value.
        assertTrue(q.contains("?value wdt:P31 ?type_gc"), q);
        // label via SERVICE, not an inline all-language FILTER in the base.
        assertTrue(q.contains("SERVICE wikibase:label"), q);
        assertFalse(q.contains("FILTER(LANG(?valueLabel)"), q);
    }

    @Test void plainPathServiceLabelsABoundedMultiQidMembership() {
        // No inlined fields (plain path), bounded multi-QID membership → the value
        // label must go via SERVICE (the inline all-language FILTER times out on
        // ~11k P1411 nominees).
        RuleNode node = new RuleNode("Nominee", "?value");
        node.sourceQid("Q102427");
        node.addAdditionalSourceQid("Q103360");
        node.propertyPid("P1411");
        node.direction(RuleDirection.ITEM_TO_ROOT);

        String q = RuleNodeQueryBuilder.valuesQuery(node);

        assertTrue(q.contains("SERVICE wikibase:label"), q);
        assertFalse(q.contains("FILTER(LANG(?valueLabel)"), q);
    }

    @Test void plainPathKeepsInlineLabelForABroadType() {
        // A broad single-type membership (Q523 "star", 3M) keeps the inline label —
        // there it RESTRICTS the scan to named entities, which SERVICE can't.
        RuleNode node = new RuleNode("Star", "?value");
        node.sourceQid("Q523");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);

        String q = RuleNodeQueryBuilder.valuesQuery(node);

        assertTrue(q.contains("FILTER(LANG(?valueLabel)"), q);
        assertFalse(q.contains("SERVICE wikibase:label"), q);
    }

    @Test void singleInlinedFieldKeepsTheSingleSubqueryPath() {
        RuleNode node = rootNode();
        node.addIncludedField(entityList("cast", "P161"));

        String q = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(node);

        // One inlined field has no cross-product, so it stays on the %limited path.
        assertTrue(q.contains("AS %limited"), q);
        assertFalse(q.contains("AS %values"), q);
    }
}
