package wikidata.explore.rule;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.QualifierCondition;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A root class whose membership is a qualified statement — a star listed in the Bright
 * Star Catalogue, P528 with P972 = Q499138 — has no target entity. With two entity-list
 * fields its values query takes the split form, which read "no target" as "no
 * membership" and wrote an empty result: the population was silently nothing (#399).
 */
class QualifiedMembershipSplitQueryTest {

    private static RuleNode catalogueStars(int entityListFields) {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.qualifiedRelation("P528", List.of(), false,
                new QualifierCondition("P972", List.of("Q499138"))));
        for (int i = 0; i < entityListFields; i++) {
            GeneratedFieldModel list = star.addField(
                    "list" + i, FieldType.ENTITY, FieldCardinality.COLLECTION);
            list.mapping().propertyPid("P" + (361 + i));
            list.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        }
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(star);
        return RuleTreeCompiler.compileClass(star, project);
    }

    @Test void theSplitFormKeepsAQualifiedMembership() {
        // Two entity-list fields are inlined, and the inlined query splits them.
        String sparql = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(catalogueStars(2));

        assertTrue(sparql.contains("%values"), "the split form: " + sparql);
        assertFalse(sparql.contains("empty result"), sparql);
        assertTrue(sparql.contains("pq:P972"), sparql);
        assertTrue(sparql.contains("wd:Q499138"), sparql);
        assertTrue(sparql.contains("p:P528"), sparql);
    }

    @Test void theSingleFormAgreesWithTheSplitForm() {
        String sparql = RuleNodeQueryBuilder.valuesQuery(catalogueStars(0));

        assertFalse(sparql.contains("empty result"), sparql);
        assertTrue(sparql.contains("pq:P972") && sparql.contains("p:P528"), sparql);
    }

    @Test void aQualifiedMembershipHasNoStratifiedSample() {
        RuleNode node = catalogueStars(0);
        node.sourceQid("Q523");
        node.addAdditionalSourceQid("Q5871");
        assertNull(RuleNodeQueryBuilder.stratifiedSampleQuery(node),
                "the plain values query states the statement test; a sample over the "
                        + "targets alone would drop it");
    }
}
