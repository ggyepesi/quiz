package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import process.ProcessWorkflowPipeline;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import wikidata.api.FactDemandPlan;

class GenerateDomainPipelineTest {

    /**
     * The plan tab is read BEFORE the run, so it is what the reader trusts. It must name
     * what acquisition will actually do — which is now decided by the BINDING, not by the
     * legacy mapping the binding was projected from. Blanking the mapping is the whole
     * test: if the explanation still found "Infobox film.country" through it, the two
     * could drift the moment they stopped agreeing.
     */
    @Test void thePlanTabNamesTheInfoboxParameterTheBindingCarries() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var country = movie.addField("country", FieldType.STRING, FieldCardinality.SINGLE);
        country.mapping().sourceType(wikidata.explore.model.FieldSourceType.SPARQL);
        country.mapping().propertyPid("");
        country.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Movie", "country",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikipedia", "infobox-parameter",
                        java.util.Map.of("property", "Infobox film.country"))));
        model.rootClass(movie);

        String evidence = details(
                GenerateDomainPipeline.configured(model),
                GenerateDomainPipeline.EXTERNAL_EVIDENCE);

        assertTrue(evidence.contains("Movie.country — native Infobox Infobox film.country"),
                evidence);
    }

    @Test void statementAcquisitionShowsItsSourcePropertyQualifiersAndConstruction() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new wikidata.explore.model.StatementClassSource("P1411"));
        var category = nomination.addField(
                "category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        category.mapping().allowedQids().add("Q19020");
        var nominee = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.COLLECTION);
        nominee.mapping().qualifierPid("P2453");
        // Declared, not implied: reification used to invent a "source" field for
        // the subject, so fixtures inherited one they never wrote down. A
        // statement must now say where its subject goes.
        nomination.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(
                        wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        model.rootClass(nomination);

        ProcessWorkflowPipeline pipeline = GenerateDomainPipeline.configured(model);
        String acquisition = details(pipeline, GenerateDomainPipeline.ACQUIRE_STATEMENTS);
        assertTrue(acquisition.contains("discover subjects"), acquisition);
        assertTrue(acquisition.contains("P1411 statements"), acquisition);
        assertTrue(acquisition.contains("nominee ← P2453 (ENTITY, list)"), acquisition);
        String construction = details(pipeline, GenerateDomainPipeline.CONSTRUCT);
        assertTrue(construction.contains("promote __Nomination records"), construction);
        assertTrue(construction.contains("value → category"), construction);
        FactDemandPlan demands = GenerationFactDemandPlan.compile(model);
        assertTrue(demands.all().stream().anyMatch(d ->
                        d.consumer().equals("statement acquisition")
                                && d.propertyPids().contains("P1411")),
                demands.all().toString());
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

        assertEquals(9, pipeline.snapshot().size());
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

        var semantic = pipeline.snapshot().stream()
                .filter(state -> state.phase().id().equals(GenerateDomainPipeline.SEMANTIC))
                .findFirst().orElseThrow().phase().explanation();
        assertTrue(semantic.operations().stream().anyMatch(s -> s.startsWith("Repeat")),
                semantic.operations().toString());
        assertTrue(semantic.operations().stream().anyMatch(s ->
                        s.contains("⟨entity QID, property⟩")
                                && s.contains("before acquisition")),
                semantic.operations().toString());
        assertTrue(semantic.examples().stream().anyMatch(e ->
                        e.title().startsWith("Plan facts before loading ")
                                && e.evidence().stream().anyMatch(value ->
                                value.contains("P31"))
                                && e.output().stream().anyMatch(value ->
                                value.contains("retention plans"))),
                semantic.examples().toString());
        assertTrue(semantic.references().stream().anyMatch(r ->
                r.kind() == process.PhaseExplanation.ReferenceKind.KIND_RULE
                        && r.owner().equals("Person")));
        assertTrue(semantic.examples().stream().anyMatch(e ->
                e.title().contains("Classify") && e.evidence().stream()
                        .anyMatch(value -> value.contains("Q5"))),
                semantic.examples().toString());
        assertTrue(semantic.examples().stream().anyMatch(e ->
                e.title().contains("owned Name")), semantic.examples().toString());

        var discovery = pipeline.snapshot().stream()
                .filter(state -> state.phase().id().equals(GenerateDomainPipeline.DISCOVER))
                .findFirst().orElseThrow().phase().explanation();
        assertTrue(discovery.examples().stream().anyMatch(e ->
                e.title().equals("Discover Nomination")), discovery.examples().toString());
        assertTrue(discovery.operations().stream().anyMatch(e ->
                        e.contains("downstream fact needs")), discovery.operations().toString());
        assertTrue(discovery.examples().stream().anyMatch(e ->
                        e.title().equals("Carry facts forward for Nomination")
                                && e.evidence().stream().anyMatch(v -> v.contains("P31"))
                                && e.output().stream().anyMatch(v -> v.contains("instead of refetching"))),
                discovery.examples().toString());
        assertTrue(GenerationFactDemandPlan.compile(model).all().stream().anyMatch(d ->
                        d.consumer().equals("semantic convergence")
                                && d.targetClass().equals("Nominee")
                                && d.propertyPids().contains("P31")),
                GenerationFactDemandPlan.compile(model).all().toString());
    }

    private static String details(ProcessWorkflowPipeline pipeline, String id) {
        return String.join("\n", pipeline.snapshot().stream()
                .filter(state -> state.phase().id().equals(id)).findFirst().orElseThrow()
                .phase().details());
    }
}
