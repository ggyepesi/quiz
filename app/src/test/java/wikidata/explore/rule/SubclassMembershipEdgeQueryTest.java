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
 * A constellation's brightest stars are typed with subclasses of star — Beta Hydri is a
 * "G-type subgiant star", Rigel a "blue supergiant" — so an edge that requires P31 = star
 * exactly left them out (#399). When the class includes subclasses, the edge does too, and
 * its per-parent query tests the type against the resolved list after the value filters:
 * written as a P279* path it timed out on every constellation measured.
 */
class SubclassMembershipEdgeQueryTest {

    private static RuleNode stars(boolean includeSubclasses) {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.relation("P31", List.of("Q523"), includeSubclasses));
        GeneratedFieldModel magnitude =
                star.addField("apparentMagnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        magnitude.mapping().propertyPid("P1215");
        magnitude.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        magnitude.mapping().qualifierCondition(
                new QualifierCondition("P1227", List.of("Q4892529")));
        magnitude.required(true);
        magnitude.filterOperator(wikidata.explore.filter.WikidataValueFilterOperator.LE);
        magnitude.filterValue(6.0);

        GeneratedClassModel constellation = new GeneratedClassModel("Constellation");
        constellation.membership(EntityBound.relation("P31", List.of("Q8928"), false));
        GeneratedFieldModel field =
                constellation.addField("stars", FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName("Star");
        field.mapping().propertyPid("P59");
        field.mapping().direction(RuleDirection.ITEM_TO_ROOT);
        field.mapping().productionKind(FieldProductionKind.CHILD_OBJECTS);
        field.mapping().limit(6);
        field.sortFieldName("apparentMagnitude");

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(star);
        project.rootClass(constellation);
        return RuleTreeCompiler.compileClass(project.rootClass(), project)
                .edges().get(0).childNode();
    }

    @Test void theEdgeIncludesSubclassesWhenItsClassDoes() {
        assertTrue(stars(true).membershipFilterIncludesDescendants());
        assertFalse(stars(false).membershipFilterIncludesDescendants());
    }

    @Test void resolvedTypesAreTestedAfterTheValueFiltersInWrittenOrder() {
        String sparql = RuleNodeQueryBuilder.childQueryForParent(
                stars(true), "Q10416", 6, List.of("Q523", "Q5871", "Q1153690"));

        assertFalse(sparql.contains("P279"), "no subclass path per parent: " + sparql);
        assertTrue(sparql.contains("hint:Query hint:optimizer \"None\""), sparql);
        int hint = sparql.indexOf("hint:Query");
        int members = sparql.indexOf("wdt:P59 wd:Q10416");
        int magnitude = sparql.indexOf("<= 6)");
        int type = sparql.indexOf("FILTER(?membershipFilterValue IN (wd:Q523, wd:Q5871, wd:Q1153690))");
        int label = sparql.indexOf("rdfs:label");
        assertTrue(hint < members && members < magnitude && magnitude < type
                && type < label, "parent's members, then the magnitude, then the type:\n"
                + sparql);
    }

    @Test void unresolvedTypesKeepTheSubclassPath() {
        String sparql = RuleNodeQueryBuilder.childQueryForParent(stars(true), "Q10416", 6);
        assertTrue(sparql.contains("?membershipFilterValue wdt:P279* wd:Q523"), sparql);
        assertFalse(sparql.contains("hint:Query"), sparql);
    }

    @Test void anExactMembershipIsUnchanged() {
        String sparql = RuleNodeQueryBuilder.childQueryForParent(
                stars(false), "Q10416", 6, List.of("Q523", "Q5871"));
        assertTrue(sparql.contains("?value wdt:P31 wd:Q523 ."), sparql);
        assertFalse(sparql.contains("hint:Query") || sparql.contains("IN (wd:Q523"), sparql);
    }

    @Test void theSubclassListIsOneWalkDownTheHierarchy() {
        assertEquals("SELECT DISTINCT ?t WHERE { ?t wdt:P279* wd:Q523 . }",
                RuleNodeQueryBuilder.descendantTypesQuery("Q523"));
    }

    @Test void previewNamesTheDependentTypeListInsteadOfPretendingItIsBaseOnly() {
        RuleNode parent = new RuleNode("Constellation", "constellation");
        parent.sourceQid("Q8928");
        parent.propertyPid("P31");
        parent.addEdge(new RuleEdge("stars", stars(true), true));

        String preview = String.join("\n",
                new wikidata.explore.extract.RuleTreeExtractor(null)
                        .previewQueries(parent, 1));

        assertTrue(preview.contains("<subclass QIDs returned above>"), preview);
        assertFalse(preview.contains(
                "FILTER(?membershipFilterValue IN (wd:Q523))"), preview);
    }


    @Test void qualifiedStatementMembershipIsTheSameRootAndEdgeConstraint() {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.qualifiedRelation("P528", List.of(), false,
                new QualifierCondition("P972", List.of("Q499138"))));
        GeneratedFieldModel magnitude =
                star.addField("apparentMagnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        magnitude.mapping().propertyPid("P1215");
        magnitude.required(true);
        magnitude.filterOperator(wikidata.explore.filter.WikidataValueFilterOperator.LE);
        magnitude.filterValue(6.0);

        GeneratedClassModel constellation = new GeneratedClassModel("Constellation");
        constellation.membership(EntityBound.instancesOf("Q8928"));
        GeneratedFieldModel stars = constellation.addField(
                "stars", FieldType.ENTITY, FieldCardinality.COLLECTION);
        stars.entityClassName("Star");
        stars.mapping().propertyPid("P59");
        stars.mapping().direction(RuleDirection.ITEM_TO_ROOT);
        stars.mapping().productionKind(FieldProductionKind.CHILD_OBJECTS);

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(star);
        project.rootClass(constellation);

        RuleNode root = RuleTreeCompiler.compileClass(star, project);
        RuleNode edge = RuleTreeCompiler.compileClass(constellation, project)
                .edges().getFirst().childNode();
        String rootQuery = RuleNodeQueryBuilder.valuesQuery(root);
        String edgeQuery = RuleNodeQueryBuilder.childQueryForParent(edge, "Q886", 6);

        for (String query : List.of(rootQuery, edgeQuery)) {
            assertTrue(query.contains("pq:P972 ?membership"), query);
            assertTrue(query.contains("wd:Q499138"), query);
            assertTrue(query.contains("p:P528 ?membership"), query);
            assertTrue(query.contains("wikibase:DeprecatedRank"), query);
            assertFalse(query.contains("wikibase:PreferredRank"),
                    "boolean membership must not rank matching statements:\n" + query);
            assertFalse(query.contains("P279"), query);
        }
        assertTrue(edgeQuery.indexOf("VALUES ?membershipFilterStatement_qv")
                        < edgeQuery.indexOf("pq:P972"),
                "qualifier values must be bound before their indexed lookup:\n" + edgeQuery);
        assertTrue(edgeQuery.indexOf("pq:P972") < edgeQuery.indexOf("wdt:P59 wd:Q886"),
                "selective catalogue membership precedes the broad constellation edge:\n"
                        + edgeQuery);
    }
}
