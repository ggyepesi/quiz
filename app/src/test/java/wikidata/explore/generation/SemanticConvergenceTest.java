package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticConvergenceTest {

    @Test void reportsProductivePassesRatherThanTheFinalNoOpProbe() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel recordClass = new GeneratedClassModel("Record");
        var subject = recordClass.addField(
                "subject", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.entityClassName("Subject");
        model.rootClass(recordClass);
        model.addClass(new GeneratedClassModel("Subject"));

        WikidataDynamicObject subjectValue = new WikidataDynamicObject("Q1", "Subject");
        WikidataDynamicObject record = new WikidataDynamicObject("R1", "Record");
        record.type("Record");
        record.put("subject", subjectValue);

        SemanticConvergence.Result result = SemanticConvergence.apply(
                model, new java.util.ArrayList<>(List.of(record)),
                new FakeWikidataApiClient(), null,
                List.of(), new GenerationQualityTracker());

        assertEquals(1, result.iterations(),
                "the following no-op fixed-point probe is not productive work");
    }

    /**
     * The loop terminates because its steps are idempotent over their own output, and
     * the fragile one is role stamping: classification RETRACTS a role when it assigns a
     * kind, so a stamp pass that put the role back would make stamping and classifying
     * undo each other for ever. The loop would then never see an unproductive pass and
     * every generation would run MAX_ITERATIONS full passes with network work in each.
     * Guarded here, where the cost lands, as well as in ReferentClassStampTest.
     */
    @Test void anEntityThatAlreadyHasItsKindDoesNotKeepTheLoopGoing() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        var nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));
        model.addClass(new GeneratedClassModel("Person"));

        WikidataDynamicObject record = new WikidataDynamicObject("N1", "a nomination");
        record.type("Nomination");
        WikidataDynamicObject classified =
                new WikidataDynamicObject("Q42", "Meryl Streep");
        classified.type("Person");            // a kind was already settled for her
        record.put("nominee", classified);

        SemanticConvergence.Result result = SemanticConvergence.apply(
                model, new java.util.ArrayList<>(List.of(record)),
                new FakeWikidataApiClient(), null,
                List.of(), new GenerationQualityTracker());

        assertEquals(1, result.iterations(),
                "stamping must not re-role a classified kind, or nothing ever converges");
        assertEquals("Person", classified.typeName());
    }

    @Test void anEarlierFieldFetchPlansTheP31ConsumedByFinalization() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("deathDate", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P570");
        model.rootClass(person);
        model.addEntityKindRule(new wikidata.explore.model.EntityKindRule(
                "Person", List.of("Q5")));

        var manifest = wikidata.explore.transform.ReferentFieldLoad.compileManifest(
                model, GenerationFactDemandPlan.compile(model).all());

        assertEquals(java.util.Set.of("P570", "P31"),
                manifest.propertiesFor("Person"),
                "P570 acquisition must also bank P31 for disambiguation pruning");
    }

    /** Local reconstruction distinguishes retained evidence from evidence it lacks. */
    @Test void localConvergenceUsesPriorEvidenceAndReportsWhatIsStillMissing() {
        GeneratedProjectModel model = kindModel();
        WikidataDynamicObject target = new WikidataDynamicObject("Q1", "a nominee");
        target.type("Nominee");
        WikidataDynamicObject record = new WikidataDynamicObject("N1", "a nomination");
        record.type("Nomination");
        record.put("nominee", target);
        WikidataDynamicObject evidence = new WikidataDynamicObject("Q1", "a nominee");
        evidence.type("Nominee");
        evidence.put("type", new WikidataDynamicObject("Q5", "human"));

        SemanticConvergence.Result resolved = SemanticConvergence.apply(
                model, new java.util.ArrayList<>(List.of(record, target)), List.of(evidence),
                null, null, List.of(), new GenerationQualityTracker(), null);

        assertEquals("Person", target.typeName());
        assertTrue(resolved.unresolvedKindQids().isEmpty());

        WikidataDynamicObject missing = new WikidataDynamicObject("Q2", "another nominee");
        missing.type("Nominee");
        WikidataDynamicObject otherRecord =
                new WikidataDynamicObject("N2", "another nomination");
        otherRecord.type("Nomination");
        otherRecord.put("nominee", missing);
        GenerationQualityTracker quality = new GenerationQualityTracker();

        SemanticConvergence.Result unresolved = SemanticConvergence.apply(
                model, new java.util.ArrayList<>(List.of(otherRecord, missing)), List.of(),
                null, null, List.of(), quality, null);

        assertEquals(java.util.Set.of("Q2"), unresolved.unresolvedKindQids());
        assertTrue(quality.quality().complete(),
                "not acquiring is a run policy, not a new acquisition failure");
    }

    private static GeneratedProjectModel kindModel() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        var nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        model.rootClass(nomination);
        GeneratedClassModel nomineeClass = new GeneratedClassModel("Nominee");
        var type = nomineeClass.addField(
                "type", FieldType.ENTITY, FieldCardinality.SINGLE);
        type.entityClassName("NomineeType");
        type.mapping().propertyPid("P31");
        model.addClass(nomineeClass);
        model.addClass(new GeneratedClassModel("Person"));
        model.addEntityKindRule(new wikidata.explore.model.EntityKindRule(
                "Person", List.of("Q5")));
        model.representationClasses(nomineeClass, List.of("Person"));
        return model;
    }
}
