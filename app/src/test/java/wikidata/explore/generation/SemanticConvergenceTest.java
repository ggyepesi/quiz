package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
