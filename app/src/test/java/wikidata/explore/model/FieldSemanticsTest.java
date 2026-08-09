package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldSemanticsTest {

    @Test void scalarLiteralIsAlwaysOutgoing() {
        GeneratedFieldModel population = new GeneratedFieldModel(
                "population", FieldType.NUMBER, FieldCardinality.SINGLE);
        population.mapping().direction(RuleDirection.ITEM_TO_ROOT);

        assertEquals(RuleDirection.ROOT_TO_ITEM,
                FieldSemantics.effectiveDirection(population));
    }

    @Test void entityFieldKeepsConfiguredDirection() {
        GeneratedFieldModel country = new GeneratedFieldModel(
                "country", FieldType.ENTITY, FieldCardinality.SINGLE);
        country.mapping().direction(RuleDirection.ITEM_TO_ROOT);

        assertEquals(RuleDirection.ITEM_TO_ROOT,
                FieldSemantics.effectiveDirection(country));
    }

    @Test void literalCollectionKeepsConfiguredDirection() {
        GeneratedFieldModel aliases = new GeneratedFieldModel(
                "aliases", FieldType.STRING, FieldCardinality.COLLECTION);
        aliases.mapping().direction(RuleDirection.ITEM_TO_ROOT);

        assertEquals(RuleDirection.ITEM_TO_ROOT,
                FieldSemantics.effectiveDirection(aliases));
    }
}
