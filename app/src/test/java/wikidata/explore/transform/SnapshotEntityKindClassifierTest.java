package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
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

        SnapshotEntityKindClassifier.Result stable =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(saved), null);
        assertEquals(0, stable.classified(),
                "an evidence match already represented in the graph is not new work");
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

    @Test void aSettledKindIsNotReportedAsMissingEvidence() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject target = entity("Q2", "Person");
        WikidataDynamicObject nomination = entity("N2", "Nomination");
        nomination.put("nominee", target);

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(target), null);

        assertEquals(0, result.classified());
        assertEquals(0, result.unknown());
        assertEquals(0, result.withoutStoredEvidence());
        assertTrue(result.withoutStoredEvidenceQids().isEmpty(),
                "a settled kind must not trigger remote reclassification");
    }

    @Test void evidenceProducerScopesKindCandidatesToItsRole() {
        GeneratedProjectModel model = model();
        GeneratedFieldModel forWork = model.rootClass().addField(
                "forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        forWork.entityClassName("ForWork");
        GeneratedClassModel workClass = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre = workClass.addField(
                "genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        model.addClass(workClass);

        WikidataDynamicObject nominee = entity("Q10", "Nominee");
        WikidataDynamicObject work = entity("Q20", "ForWork");
        WikidataDynamicObject nomination = entity("N10", "Nomination");
        nomination.put("nominee", nominee);
        nomination.put("forWork", work);

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, nominee, work), List.of(), null);

        assertEquals(1, result.withoutStoredEvidence(),
                "P31 is produced by Nominee.type, so ForWork is not a Person candidate");
        assertEquals(java.util.Set.of("Q10"), result.withoutStoredEvidenceQids());
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

    @Test void evidenceOnAnAlreadyClassifiedCopyStillBelongsToItsProducerRole() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject target = entity("Q40", "Nominee");
        WikidataDynamicObject nomination = entity("N40", "Nomination");
        nomination.put("nominee", target);
        WikidataDynamicObject saved = entity("Q40", "Person");
        saved.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(model,
                        List.of(nomination, target), List.of(saved), null);

        assertEquals(1, result.classified());
        assertEquals("Person", target.typeName());
    }

    /**
     * A part carries its OWNER's identifier — that is how its fields load from the
     * owner's QID — but it is not the owner. Propagating a kind to "every copy of the
     * QID" reached the part too and rewrote its type key, which is the part's production
     * SITE and therefore its identity: owned composition could then no longer find it,
     * and produced a second part for the same owner on the next pass.
     */
    @Test void aPartIsNotAnotherCopyOfItsOwner() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject owner = entity("Q5000", "Nominee");
        WikidataDynamicObject nomination = entity("N5", "Nomination");
        nomination.put("nominee", owner);

        WikidataDynamicObject part = entity("Q5000", "BirthName");
        part.typeKey("BirthName@Person.birthName");
        part.part(true);
        owner.put("birthName", part);

        WikidataDynamicObject saved = entity("Q5000", "Nominee");
        saved.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.apply(model,
                List.of(nomination, owner, part), List.of(saved), null);

        assertEquals("Person", owner.typeName(), "the owner is classified as before");
        assertEquals("BirthName", part.typeName(), "the part keeps what it is");
        assertEquals("BirthName@Person.birthName", part.typeKey(),
                "the type key names the production site — the part's identity");
        assertFalse(part.directClassNames().contains("Person"),
                "a birth name is not a person");
    }

    @Test void aPartExposedAsARoleIsNotCountedAsAnEntityCandidate() {
        GeneratedProjectModel model = model();
        WikidataDynamicObject part = entity("Q5000", "Nominee");
        part.type("BirthName");
        part.typeKey("BirthName@Person.birthName");
        part.part(true);
        WikidataDynamicObject nomination = entity("N6", "Nomination");
        nomination.put("nominee", part);

        WikidataDynamicObject saved = entity("Q5000", "Nominee");
        saved.put("type", new WikidataDynamicObject("Q5", "human"));

        SnapshotEntityKindClassifier.Result result =
                SnapshotEntityKindClassifier.apply(
                        model, List.of(nomination, part), List.of(saved), null);

        assertEquals(0, result.classified());
        assertEquals(0, result.unknown());
        assertEquals("BirthName", part.typeName());
        assertEquals("BirthName@Person.birthName", part.typeKey());
        assertFalse(part.directClassNames().contains("Person"));
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
