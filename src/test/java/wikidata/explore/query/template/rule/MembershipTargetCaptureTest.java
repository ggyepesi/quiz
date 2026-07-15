package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice-1 detection + the membership backbone query that captures the target field.
 * The fixture mirrors the real OscarNominations node: a P1411 / ITEM_TO_ROOT
 * relational membership over award categories, a {@code target} field that walks the
 * SAME relation (P1411 / ROOT_TO_ITEM — nominally opposite, same emitted triple) with
 * a redundant P31=Q19020 type constraint, and a {@code type} field on a DIFFERENT
 * predicate (P31) that must NOT be mistaken for a membership target.
 */
class MembershipTargetCaptureTest {

    private static RuleIncludedField field(
            String name, String pid, RuleDirection dir,
            String membershipQid) {
        RuleIncludedField f = new RuleIncludedField();
        f.fieldName(name);
        f.propertyPid(pid);
        f.direction(dir);
        f.kind(RuleIncludedField.FieldKind.ENTITY);
        f.collection(true);
        if (membershipQid != null) {
            f.membershipPid("P31");
            f.membershipQid(membershipQid);
        }
        return f;
    }

    private static RuleNode oscarNode() {
        RuleNode node = new RuleNode("OscarNominations", "oscarNominations");
        node.propertyPid("P1411");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.addAdditionalSourceQid("Q102427");
        node.addAdditionalSourceQid("Q103360");
        node.addAdditionalSourceQid("Q281939");
        // target: same relation as membership (P1411, opposite nominal direction),
        // with a redundant P31 type constraint subsumed by the explicit root set.
        node.addIncludedField(field("target", "P1411", RuleDirection.ROOT_TO_ITEM, "Q19020"));
        // type: a DIFFERENT predicate — not a membership target.
        node.addIncludedField(field("type", "P31", RuleDirection.ROOT_TO_ITEM, null));
        return node;
    }

    @Test
    void detectsOnlyTheFieldThatWalksTheMembershipRelation() {
        List<RuleIncludedField> targets =
                RuleNodeQueryBuilder.membershipTargetFields(oscarNode());
        assertEquals(1, targets.size(), "only target reuses the membership relation");
        assertEquals("target", targets.get(0).fieldName());
    }

    @Test
    void aScalarOrSingleFieldOnTheSamePredicateIsNotATarget() {
        RuleNode node = oscarNode();
        // Same predicate + direction as membership, but NOT a collection entity ref.
        RuleIncludedField single = field("one", "P1411", RuleDirection.ROOT_TO_ITEM, null);
        single.collection(false);
        node.addIncludedField(single);
        RuleIncludedField scalar = field("s", "P1411", RuleDirection.ROOT_TO_ITEM, null);
        scalar.kind(RuleIncludedField.FieldKind.AUTO);
        node.addIncludedField(scalar);

        List<RuleIncludedField> targets =
                RuleNodeQueryBuilder.membershipTargetFields(node);
        assertEquals(List.of("target"),
                targets.stream().map(RuleIncludedField::fieldName).toList());
    }

    @Test
    void noTargetsWithoutAFixedMultiQidMembership() {
        RuleNode node = new RuleNode("Star", "star");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.sourceQid("Q523");   // single-type membership, no additionalSourceQids
        node.addIncludedField(field("target", "P31", RuleDirection.ROOT_TO_ITEM, null));
        assertTrue(RuleNodeQueryBuilder.membershipTargetFields(node).isEmpty());
    }

    @Test
    void memberFieldQueryIsMemberBatchedDistinctAndHonoursDirection() {
        // type: P31 / ROOT_TO_ITEM, no type constraint → ?value wdt:P31 ?fieldValue.
        RuleIncludedField type = field("type", "P31", RuleDirection.ROOT_TO_ITEM, null);
        String q = RuleNodeQueryBuilder.memberFieldBatchQuery(
                type, List.of("Q11", "Q22"));

        assertTrue(q.contains("SELECT DISTINCT ?value ?fieldValue"), q);
        assertTrue(q.contains("VALUES ?value { wd:Q11 wd:Q22 }"), q);
        assertTrue(q.contains("?value wdt:P31 ?fieldValue"), q);
        assertFalse(q.contains("GROUP_CONCAT"), "no group-concat in the slice-2 query");
    }

    @Test
    void memberFieldQueryEmitsTheFieldTypeConstraintWhenPresent() {
        RuleIncludedField constrained =
                field("cast", "P161", RuleDirection.ROOT_TO_ITEM, "Q5");   // humans only
        String q = RuleNodeQueryBuilder.memberFieldBatchQuery(constrained, List.of("Q11"));
        assertTrue(q.contains("?value wdt:P161 ?fieldValue"), q);
        assertTrue(q.contains("?fieldValue wdt:P31 wd:Q5"), q);
    }

    @Test
    void backboneQueryCapturesTheRootAndTheValuesQueryDoesNot() {
        RuleNode node = oscarNode();
        String backbone = RuleNodeQueryBuilder.membershipBackboneQuery(node);
        String plain = RuleNodeQueryBuilder.valuesQuery(node);

        // The membership relation + the root VALUES set appear in both.
        assertTrue(backbone.contains("?value wdt:P1411 ?root"),
                "backbone walks the membership relation:\n" + backbone);
        assertTrue(backbone.contains("wd:Q281939"), "root VALUES set present");

        // Only the backbone SELECTs the root (the captured target).
        assertTrue(backbone.contains("?value ?root"),
                "backbone SELECTs the root:\n" + backbone);
        assertFalse(plain.contains("?value ?root"),
                "plain values query does not SELECT the root:\n" + plain);
    }
}
