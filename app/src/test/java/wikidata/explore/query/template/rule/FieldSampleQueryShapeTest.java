package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sparse field is sampled from members that have it, with its real edge semantics. */
class FieldSampleQueryShapeTest {

    @Test void parentSamplingRequiresTheSparsePropertyBeforeApplyingItsLimit() {
        RuleNode positions = new RuleNode("Position", "position");
        positions.sourceQid("Q4164871");
        positions.propertyPid("P31");
        positions.direction(RuleDirection.ITEM_TO_ROOT);
        positions.limit(8);

        RuleIncludedField abolished = new RuleIncludedField(
                "abolished", "P576", "abolished",
                RuleIncludedField.FieldKind.DATE, true);

        String sparql = RuleTreeQueries.valuesQueryWithRequiredField(
                positions, abolished);

        assertTrue(sparql.contains("?value wdt:P576 ?required0 ."), sparql);
        assertTrue(sparql.indexOf("wdt:P576") < sparql.indexOf("LIMIT 8"), sparql);
    }

    @Test void classAndFieldSamplingUseDescendantMembershipLikeGeneration() {
        RuleNode positions = new RuleNode("Position", "position");
        positions.sourceQid("Q4164871");
        positions.propertyPid("P31");
        positions.membershipIncludesDescendants(true);
        positions.limit(8);

        RuleIncludedField jurisdiction = new RuleIncludedField(
                "jurisdiction", "P1001", "jurisdiction",
                RuleIncludedField.FieldKind.ENTITY, false);

        for (String sparql : List.of(
                RuleTreeQueries.valuesQueryWithoutIncludedFields(positions.sampleCopy(8)),
                RuleTreeQueries.valuesQueryWithRequiredField(positions, jurisdiction))) {
            assertTrue(sparql.contains("?root wdt:P279* ?membershipRoot"), sparql);
            assertTrue(sparql.contains("?value wdt:P31 ?root"), sparql);
        }
    }

    @Test void valueSamplingHonoursIncomingDirectionAndTheChosenClass() {
        RuleIncludedField holders = new RuleIncludedField(
                "holders", "P39", "position held",
                RuleIncludedField.FieldKind.ENTITY, true);
        holders.direction(RuleDirection.ITEM_TO_ROOT);
        holders.membershipPid("P31");
        holders.membershipQid("Q5");

        String sparql = RuleTreeQueries.fieldValueSampleQuery(
                holders, List.of("Q11696"), null, 24);

        assertTrue(sparql.contains("?holders_0 wdt:P39 ?parent ."), sparql);
        assertTrue(sparql.contains("?holders_0 wdt:P31 wd:Q5 ."), sparql);
    }
}
