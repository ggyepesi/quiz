package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotEntityKindClassifierTest {

    @Test void remapAssignsKindFromPersistedFieldEvidenceWithoutAnApi() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject target = entity("Q1", "Nominee");
        WikidataDynamicObject nomination = entity("N1", "Nomination");
        nomination.put("nominee", target);

        WikidataDynamicObject saved = entity("Q1", "Nominee");
        saved.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(saved), null);

        assertEquals(1, result.classified());
        assertTrue(target.directClassNames().contains("Person"));
        assertFalse(target.directClassNames().contains("Nominee"));
        assertEquals("Person", target.typeName());
    }

    @Test void missingStoredEvidenceKeepsTheRoleCarrier() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject target = entity("Q2", "Nominee");
        WikidataDynamicObject nomination = entity("N2", "Nomination");
        nomination.put("nominee", target);

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(target), null);

        assertEquals(0, result.classified());
        assertEquals(1, result.withoutStoredEvidence());
        assertTrue(target.directClassNames().contains("Nominee"));
    }

    @Test void sameNamedFieldOnAnotherClassCannotSupplyEvidence() {
        GeneratedProjectModel model = model();
        GeneratedClassModel other = new GeneratedClassModel("Other");
        GeneratedFieldModel type = other.addField(
                "type", FieldType.ENTITY, FieldCardinality.SINGLE);
        type.entityClassName("OtherType");
        type.mapping().propertyPid("P136");
        model.addClass(other);

        WikidataDynamicObject target = entity("Q3", "Nominee");
        WikidataDynamicObject nomination = entity("N3", "Nomination");
        nomination.put("nominee", target);
        WikidataDynamicObject unrelated = entity("Q3", "Other");
        unrelated.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(unrelated), null);

        assertEquals(0, result.classified());
        assertTrue(target.directClassNames().contains("Nominee"));
    }

    @Test void classificationPropagatesToEveryInMemoryCopyOfTheQid() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject pooled = entity("Q4", "Nominee");
        WikidataDynamicObject referencedCopy = entity("Q4", "Nominee");
        WikidataDynamicObject nomination = entity("N4", "Nomination");
        nomination.put("nominee", referencedCopy);
        WikidataDynamicObject saved = entity("Q4", "Nominee");
        saved.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.apply(model,
                List.of(nomination, pooled), List.of(saved), null);

        assertEquals("Person", pooled.typeName());
        assertEquals("Person", referencedCopy.typeName());
        assertFalse(pooled.directClassNames().contains("Nominee"));
        assertFalse(referencedCopy.directClassNames().contains("Nominee"));
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Nomination");
        model.rootClass().statementSource(new StatementClassSource());
        model.rootClass().statementSource().propertyPid("P1411");
        GeneratedFieldModel nominee = model.rootClass().addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        nominee.mapping().qualifierPid("P2453");

        GeneratedClassModel nomineeClass = new GeneratedClassModel("Nominee");
        GeneratedFieldModel type = nomineeClass.addField(
                "type", FieldType.ENTITY, FieldCardinality.SINGLE);
        type.entityClassName("NomineeType");
        type.mapping().propertyPid("P31");
        model.addClass(nomineeClass);
        model.addClass(new GeneratedClassModel("Person"));
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        return model;
    }

    private static WikidataDynamicObject entity(String id, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        object.typeKey(type);
        return object;
    }
}
