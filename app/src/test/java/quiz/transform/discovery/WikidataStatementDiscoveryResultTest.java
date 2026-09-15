package quiz.transform.discovery;

import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.statement.EntityStatementSummary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataStatementDiscoveryResultTest {
    @Test void unionSampleContainsFieldsFoundOnlyOnLaterInstancesAndStatements() {
        WikidataDynamicObject firstStatement = new WikidataDynamicObject("S1", "first");
        firstStatement.type("Wikidata statement");
        firstStatement.put("Value", "one");
        WikidataDynamicObject secondStatement = new WikidataDynamicObject("S2", "second");
        secondStatement.type("Wikidata statement");
        secondStatement.put("Value", "two");
        secondStatement.put("point in time (P585)", "2020");
        WikidataDynamicObject first = new WikidataDynamicObject("Q1", "One");
        first.put("instance of (P31)", firstStatement);
        WikidataDynamicObject second = new WikidataDynamicObject("Q2", "Two");
        second.put("position held (P39)", secondStatement);

        WikidataDynamicObject union = WikidataStatementDiscoveryResult.unionSample(
                List.of(first, second));

        Object sources = FieldSet.of(union).read("wikidataSource");
        assertTrue(sources instanceof List<?> values && values.size() == 1,
                "the union shape must expose wikidataSource to ObjectView");
        quiz.source.WikidataSource source = (quiz.source.WikidataSource)
                ((List<?>) sources).getFirst();
        assertEquals("https://www.wikidata.org/wiki/Q1", source.wikidataUrl());
        assertTrue(union.dynamicFields().containsKey("instance of (P31)"));
        WikidataDynamicObject position = (WikidataDynamicObject)
                union.get("position held (P39)");
        assertEquals("2020", position.get("point in time (P585)"));
    }

    @Test void preservesStatementsAndQualifiersAndReusesOnlyLoadedEntities() {
        WikidataDynamicObject selected = new WikidataDynamicObject("Q1", "Selected");
        selected.type("Person");
        WikidataDynamicObject loadedValue = new WikidataDynamicObject("Q2", "Loaded value");
        loadedValue.type("Office");
        EntityStatementSummary summary = EntityStatementSummary.of("Q1", List.of(
                new EntityStatementSummary.Property("P39", "position held", List.of(
                        new EntityStatementSummary.Statement("Q1$abc", "Loaded value", "Q2",
                                List.of(new EntityStatementSummary.Qualifier(
                                        "P580", "start time", "Outside", "Q3")))))));

        List<WikidataDynamicObject> result = WikidataStatementDiscoveryResult.materialize(
                List.of(summary), List.of(selected, loadedValue));

        assertEquals(1, result.size());
        WikidataDynamicObject root = result.getFirst();
        assertEquals("Q1", root.getIdentifier());
        assertEquals(1, ((List<?>) FieldSet.of(root).read("wikidataSource")).size());
        WikidataDynamicObject statement = (WikidataDynamicObject) root.get("P39");
        assertEquals("position held (P39)", root.dynamicFieldSchema().field("P39").label());
        assertSame(loadedValue, statement.get("Value"));
        WikidataDynamicObject outside = (WikidataDynamicObject)
                statement.get("P580");
        assertEquals("start time (P580)",
                statement.dynamicFieldSchema().field("P580").label());
        assertEquals("Q3", outside.getIdentifier());
        assertTrue(outside.dynamicFields().isEmpty(),
                "an entity outside the loaded instances has only its declared wikidataSource");
        assertEquals(1, ((List<?>) FieldSet.of(outside).read("wikidataSource")).size());
    }

    @Test void aWikidataLabelContainingAPeriodIsNotUsedAsAFieldPathKey() {
        EntityStatementSummary summary = EntityStatementSummary.of("Q1", List.of(
                new EntityStatementSummary.Property("P8814",
                        "WordNet 3.1 Synset ID", List.of(
                        new EntityStatementSummary.Statement("Q1$wordnet", "value", null,
                                List.of())))));

        WikidataDynamicObject root = WikidataStatementDiscoveryResult.materialize(
                List.of(summary), List.of()).getFirst();
        WikidataDynamicObject union = WikidataStatementDiscoveryResult.unionSample(List.of(root));

        assertTrue(union.dynamicFields().containsKey("P8814"));
        assertEquals("WordNet 3.1 Synset ID (P8814)",
                union.dynamicFieldSchema().field("P8814").label());
        assertEquals(List.of("P8814"),
                objectview.field.FieldPath.of("P8814").segments());
    }
}
