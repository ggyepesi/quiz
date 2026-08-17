package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateDomainPipelineTest {

    @Test void statementAcquisitionShowsItsSourcePropertyQualifiersAndConstruction() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new wikidata.explore.model.StatementClassSource("P1411"));
        var category = nomination.addField(
                "category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        var nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.COLLECTION);
        nominee.mapping().qualifierPid("P2453");
        model.rootClass(nomination);

        ProcessWorkflowPipeline pipeline = GenerateDomainPipeline.configured(model);
        String acquisition = details(pipeline, GenerateDomainPipeline.ACQUIRE_STATEMENTS);
        assertTrue(acquisition.contains("discover subjects"), acquisition);
        assertTrue(acquisition.contains("P1411 statements"), acquisition);
        assertTrue(acquisition.contains("nominee ← P2453 (ENTITY, list)"), acquisition);
        String construction = details(pipeline, GenerateDomainPipeline.CONSTRUCT);
        assertTrue(construction.contains("promote __Nomination records"), construction);
        assertTrue(construction.contains("value → category"), construction);
    }

    @Test void derivesFieldPropertyKindAndOwnedDetailsFromTheConfiguredModel() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.instanceMapping().propertyPid("P31");
        nomination.instanceMapping().sourceQid("Q1");
        var nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.entityClassName("Nominee");
        nominee.mapping().propertyPid("P2453");
        model.rootClass(nomination);

        GeneratedClassModel nomineeClass = new GeneratedClassModel("Nominee");
        var type = nomineeClass.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.entityClassName("NomineeType");
        type.mapping().propertyPid("P31");
        model.addClass(nomineeClass);
        model.addClass(new GeneratedClassModel("NomineeType"));

        GeneratedClassModel person = new GeneratedClassModel("Person");
        var name = person.addField(
                "name", FieldType.ENTITY, FieldCardinality.SINGLE);
        name.entityClassName("Name");
        name.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        model.addClass(person);
        GeneratedClassModel nameClass = new GeneratedClassModel("Name");
        nameClass.ownedClass(true);
        model.addClass(nameClass);
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        ProcessWorkflowPipeline pipeline = GenerateDomainPipeline.configured(model);

        assertEquals(8, pipeline.snapshot().size());
        String roleDetails = details(pipeline, GenerateDomainPipeline.ROLE_EVIDENCE);
        assertTrue(roleDetails.contains("Nominee.type")
                && roleDetails.contains("NomineeType") && roleDetails.contains("P31"),
                roleDetails);
        assertTrue(details(pipeline, GenerateDomainPipeline.CLASSIFY)
                .contains("Person ← P31 in Q5 — candidates from Nominee.type"));
        String ownedDetails = details(pipeline, GenerateDomainPipeline.OWNED);
        assertTrue(ownedDetails.contains("Person.name")
                && ownedDetails.contains("Name") && ownedDetails.contains("owner QID"),
                ownedDetails);
    }

    private static String details(ProcessWorkflowPipeline pipeline, String id) {
        return String.join("\n", pipeline.snapshot().stream()
                .filter(state -> state.phase().id().equals(id)).findFirst().orElseThrow()
                .phase().details());
    }
}
