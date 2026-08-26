package wikidata.explore.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A date is read from the statement's value node, because the truthy leg drops
 * facts the numbers cannot carry: Wikidata records 1047 as Gregorian and 1576 as
 * Julian, and pads a year-precision value to -01-01 exactly as a real 1 January is
 * written. Neither is recoverable afterwards, so the query has to ask for them.
 */
class DateValueNodeQueryTest {

    private static GeneratedProjectModel rulers(boolean unusedSort) {
        GeneratedClassModel ruler = new GeneratedClassModel("Ruler");
        ruler.instanceMapping().propertyPid("P39");
        ruler.instanceMapping().additionalTypeQids().add("Q6412254");

        GeneratedFieldModel born =
                ruler.addField("born", FieldType.DATE, FieldCardinality.SINGLE);
        born.mapping().propertyPid("P569");

        GeneratedFieldModel house =
                ruler.addField("house", FieldType.ENTITY, FieldCardinality.SINGLE);
        house.mapping().propertyPid("P53");

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(ruler);
        return p;
    }

    private static String query(boolean sortByBorn) {
        GeneratedProjectModel p = rulers(sortByBorn);
        RuleNode node = RuleTreeCompiler.compileClass(p.rootClass(), p);
        if (sortByBorn) {
            // Sorting is declared on the node the compiler produces (a collection
            // field carries it down); set it here rather than model a nesting the
            // point does not need.
            node.sortFieldName("born");
        }
        return RuleNodeQueryBuilder.valuesQuery(node);
    }

    @Test void aDateFieldAsksTheValueNodeForItsCalendarAndPrecision() {
        String sparql = query(false);

        assertTrue(sparql.contains("p:P569"), sparql);
        assertTrue(sparql.contains("psv:P569"), sparql);
        assertTrue(sparql.contains("wikibase:timeValue"), sparql);
        assertTrue(sparql.contains("wikibase:timeCalendarModel"), sparql);
        assertTrue(sparql.contains("wikibase:timePrecision"), sparql);
        assertTrue(sparql.contains("a wikibase:BestRank"),
                "the value-node leg must preserve wdt: best-rank semantics: " + sparql);
        assertFalse(sparql.contains("wdt:P569"),
                "the truthy leg cannot carry either fact, so it is not used: " + sparql);
    }

    @Test void aNonDateFieldKeepsTheTruthyLeg() {
        // The value node costs a join per field. Only a date needs one.
        String sparql = query(false);

        assertTrue(sparql.contains("wdt:P53"), sparql);
        assertFalse(sparql.contains("psv:P53"), sparql);
    }

    @Test void groupedScalarDatesAlsoUseTheValueNode() {
        GeneratedProjectModel p = rulers(false);
        GeneratedFieldModel relatives = p.rootClass().addField(
                "relatives", FieldType.ENTITY, FieldCardinality.COLLECTION);
        relatives.mapping().propertyPid("P1038");
        RuleNode node = RuleTreeCompiler.compileClass(p.rootClass(), p);

        String sparql = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(node);

        assertTrue(sparql.contains("psv:P569"), sparql);
        assertFalse(sparql.contains("wdt:P569"), sparql);
        assertTrue(sparql.contains("AS ?born_0_s"), sparql);
    }

    @Test void aCollectionDateIsStillAnOutgoingLiteral() {
        GeneratedClassModel events = new GeneratedClassModel("Event");
        GeneratedFieldModel dates = events.addField(
                "dates", FieldType.DATE, FieldCardinality.COLLECTION);
        dates.mapping().propertyPid("P585");
        dates.mapping().direction(wikidata.explore.model.RuleDirection.ITEM_TO_ROOT);

        RuleIncludedField compiled = RuleTreeCompiler.compileField(dates);

        assertTrue(compiled.direction()
                == wikidata.explore.model.RuleDirection.ROOT_TO_ITEM);
    }

    @Test void timeCalendarAndPrecisionArriveAsOneSampledValue() {
        // SAMPLE picks each variable independently, so three separate selections
        // could pair one statement's time with another's calendar. One packed
        // string cannot be taken apart that way.
        String sparql = query(false);

        assertTrue(sparql.contains("BIND(CONCAT("), sparql);
        assertTrue(sparql.contains("[precision="), sparql);
        assertTrue(sparql.contains("AS ?born_0)"),
                "one packed value, bound to the field's own variable: " + sparql);

        // The query states no calendar identifier of its own. It passes the model
        // through, and CalendarModelCodec is the only thing that reads it — so a
        // model nobody has seen is that codec's question, not a silent Gregorian.
        assertFalse(sparql.contains("Q1985786"), sparql);
        assertFalse(sparql.contains("Julian"), sparql);
    }

    @Test void theAggregatingQuerySortsOnTheTimeNotThePackedString() {
        // childQueryForParent aggregates with SAMPLE/MIN/MAX and orders by the
        // result. Packed, "-0500-…" would sort by its digits and a BC date land the
        // wrong way round — the era a history model is mostly made of.
        GeneratedProjectModel p = rulers(true);
        RuleNode node = RuleTreeCompiler.compileClass(p.rootClass(), p);
        node.sortFieldName("born");

        String sparql = RuleNodeQueryBuilder.childQueryForParent(node, "wd:Q1", 50);

        // The value is the packed string; the ordering is the time it contains.
        assertTrue(sparql.contains("(MIN(?born_0_s) AS ?born_0)"), sparql);
        assertFalse(sparql.contains("MIN(?born_0_t)"),
                "a separately aggregated time can belong to another statement: " + sparql);
        assertTrue(sparql.contains(
                        "ASC(xsd:dateTime(STRBEFORE(?born_0, \"|\")))"),
                "ordering recovers the time from the selected packed value: " + sparql);
        // A non-date field keeps plain SAMPLE over its truthy value.
        assertTrue(sparql.contains("SAMPLE(?house_1_s)"), sparql);
    }
}
