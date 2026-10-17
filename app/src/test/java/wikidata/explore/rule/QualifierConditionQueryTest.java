package wikidata.explore.rule;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.QualifierCondition;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A constellation's brightest six stars (#395): the {@code <= 6} filter, the ascending
 * ranking and the stored magnitude all come from one per-constellation query, and all
 * three must read the V-band statements. Read through {@code wdt:P1215}, an unranked
 * IRAS source answered with its infrared K band (2.93) and was ranked among
 * Capricornus' brightest while its visual magnitude is 9.16.
 */
class QualifierConditionQueryTest {

    private static final QualifierCondition V_BAND =
            new QualifierCondition("P1227", List.of("Q4892529"));

    private static GeneratedProjectModel constellations(QualifierCondition condition) {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.relation("P31", List.of("Q523"), false));
        GeneratedFieldModel magnitude =
                star.addField("apparentMagnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        magnitude.mapping().propertyPid("P1215");
        magnitude.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        magnitude.mapping().qualifierCondition(condition);
        magnitude.required(true);
        magnitude.filterOperator(wikidata.explore.filter.WikidataValueFilterOperator.LE);
        magnitude.filterValue(6.0);

        GeneratedClassModel constellation = new GeneratedClassModel("Constellation");
        constellation.membership(EntityBound.relation("P31", List.of("Q8928"), false));
        GeneratedFieldModel stars =
                constellation.addField("stars", FieldType.ENTITY, FieldCardinality.COLLECTION);
        stars.entityClassName("Star");
        stars.mapping().propertyPid("P59");
        stars.mapping().direction(RuleDirection.ITEM_TO_ROOT);
        stars.mapping().productionKind(FieldProductionKind.CHILD_OBJECTS);
        stars.mapping().limit(6);
        stars.sortFieldName("apparentMagnitude");
        stars.sortDescending(false);

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(star);
        project.rootClass(constellation);
        return project;
    }

    private static String starQuery(QualifierCondition condition) {
        GeneratedProjectModel project = constellations(condition);
        RuleNode root = RuleTreeCompiler.compileClass(project.rootClass(), project);
        RuleNode stars = root.edges().get(0).childNode();
        return RuleNodeQueryBuilder.childQueryForParent(stars, "Q10476", 6);
    }

    @Test void theFilterTheRankingAndTheValueReadOnlyTheVisualBand() {
        String sparql = starQuery(V_BAND);

        assertFalse(sparql.contains("wdt:P1215"),
                "the truthy leg answers with every band of an unranked star: " + sparql);
        assertTrue(sparql.contains("p:P1215"), sparql);
        assertTrue(sparql.contains("pq:P1227"), sparql);
        assertTrue(sparql.contains("wd:Q4892529"), sparql);
        assertTrue(sparql.contains("ps:P1215"), sparql);
        assertTrue(sparql.contains("wikibase:DeprecatedRank"), sparql);
        assertTrue(sparql.contains("wikibase:PreferredRank"), sparql);
        // One binding feeds the filter, the MIN that ranks and the value stored.
        assertEquals(1, count(sparql, "ps:P1215"), sparql);
        assertTrue(sparql.contains("FILTER(xsd:decimal(?apparentMagnitude"), sparql);
        assertTrue(sparql.contains("MIN(?apparentMagnitude"), sparql);
    }

    @Test void withoutAConditionTheQueryIsTheTruthyOneItWasBefore() {
        String sparql = starQuery(null);

        assertTrue(sparql.contains("wdt:P1215"), sparql);
        assertFalse(sparql.contains("pq:"), sparql);
        assertFalse(sparql.contains("ps:P1215"), sparql);
    }

    @Test void aClassRankedByTheFieldRanksByTheSameStatements() {
        GeneratedProjectModel project = constellations(V_BAND);
        GeneratedClassModel star = project.findClass("Star");
        star.instanceMapping().rankBy("apparentMagnitude");
        star.instanceMapping().rankDescending(false);
        RuleNode node = RuleTreeCompiler.compileClass(star, project);

        String sparql = RuleNodeQueryBuilder.valuesQuery(node);

        assertFalse(sparql.contains("wdt:P1215"), sparql);
        assertTrue(sparql.contains("?rankMeasure"), sparql);
        assertTrue(sparql.contains("pq:P1227"), sparql);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
}
