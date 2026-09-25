package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two fields are one relation when Wikidata says their properties are converses, never
 * because their names look like a pair. The pairing is read from P1696 in the cached
 * property catalogue, so a property with no stated converse — P279 has none — is
 * profiled alone rather than guessed into a pair.
 */
class RelationFieldsTest {

    @Test void aStatedConversePairIsOneRelationAndAnUnpairedPropertyIsAnother() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        entityField(position, "replaces", "Position", "P1365");
        entityField(position, "replacedBy", "Position", "P1366");
        entityField(position, "superClasses", "Position", "P279");
        entityField(position, "countries", "Country", "P17");
        model.rootClass(position);

        List<RelationFields.Relation> relations = RelationFields.of(position, model,
                Map.of("P1365", Set.of("P1366"), "P1366", Set.of("P1365")));

        assertEquals(2, relations.size(), "a country field is not a relation on Position");
        RelationFields.Relation succession = relations.getFirst();
        assertTrue(succession.paired());
        assertEquals("replaces ⇄ replacedBy", succession.label());
        assertEquals("P1366", succession.inversePid());

        RelationFields.Relation subclass = relations.get(1);
        assertFalse(subclass.paired(), "P279 states no inverse property");
        assertEquals("superClasses", subclass.label());
    }

    @Test void anUnrefreshedCatalogueProposesEachFieldAloneRatherThanAPairing() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        entityField(position, "replaces", "Position", "P1365");
        entityField(position, "replacedBy", "Position", "P1366");
        model.rootClass(position);

        List<RelationFields.Relation> relations =
                RelationFields.of(position, model, Map.of());

        assertEquals(List.of("replaces", "replacedBy"),
                relations.stream().map(RelationFields.Relation::label).toList(),
                "with no stated converse the names are not evidence of one");
    }

    @Test void aSubclassReachesTheRelationsItsBaseDeclares() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        entityField(position, "replaces", "Position", "P1365");
        model.rootClass(position);
        GeneratedClassModel withHolders = new GeneratedClassModel("PositionWithHolders");
        withHolders.baseClassName("Position");
        model.addClass(withHolders);

        List<RelationFields.Relation> relations =
                RelationFields.of(withHolders, model, Map.of());

        assertEquals(List.of("replaces"),
                relations.stream().map(RelationFields.Relation::label).toList(),
                "the 410 loaded members are PositionWithHolders; the field is Position's");
    }

    private static void entityField(
            GeneratedClassModel clazz, String name, String target, String pid) {
        GeneratedFieldModel field = clazz.addField(
                name, FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName(target);
        field.mapping().propertyPid(pid);
    }
}
