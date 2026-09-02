package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferentKindClassifierTest {
    @Test void admissionAloneDoesNotChooseARepresentation() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));
        model.addClass(new GeneratedClassModel("Person"));
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));

        WikidataDynamicObject nominee = new WikidataDynamicObject("Q1", "Human");
        nominee.type("Nominee");
        var api = new FakeWikidataApiClient()
                .entity("Q1", "Human", Map.of("P31", List.of("Q5")));

        var result = ReferentKindClassifier.apply(model, List.of(nominee), api, null);

        assertEquals(0, result.classified());
        assertEquals(java.util.Set.of("Nominee"), nominee.directClassNames());
    }

    @Test void replacesRoleClassesOnlyWhenEvidenceProvidesAReplacementKind() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.rootClass(nomination);
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        model.addClass(nominee);
        model.addClass(new GeneratedClassModel("ForWork"));
        model.addClass(new GeneratedClassModel("Person"));
        model.addClass(new GeneratedClassModel("Film"));
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        model.addEntityKindRule(new EntityKindRule("Film", List.of("Q11424")));
        model.representationClasses(nominee, List.of("Person", "Film"));

        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Person");
        person.type("Nominee");
        WikidataDynamicObject unknown = new WikidataDynamicObject("Q2", "Unknown");
        unknown.type("ForWork");
        WikidataDynamicObject event = new WikidataDynamicObject("Q3$stmt", "Nomination");
        event.type("Nomination");
        event.put("nominee", person);
        event.put("forWork", unknown);

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q1", "Person", Map.of("P31", List.of("Q5", "Q11424")))
                .entity("Q2", "Unknown", Map.of());
        var result = ReferentKindClassifier.apply(model,
                List.of(event, person, unknown), api, null);

        assertEquals(1, result.classified());
        assertEquals(0, result.unknown(),
                "an unconfigured role is outside contextual representation");
        assertEquals(java.util.Set.of("Person", "Film"), person.directClassNames());
        assertEquals("Person", person.typeName(),
                "the first explicit matching representation is the carrier");
        assertEquals("Person", person.typeKey());
        assertTrue(unknown.hasTypeStamp(),
                "an unknown thin referent must remain protected from string collapse");
        assertEquals(java.util.Set.of("ForWork"), unknown.directClassNames());
        BareReferenceCollapse.apply(List.of(event, person, unknown));
        assertTrue(event.get("forWork") instanceof WikidataDynamicObject,
                "the retained role stamp protects an unknown thin referent on reload");
        assertEquals(List.of(person), RoleSelections.materialize(model,
                List.of(event, person, unknown)).get("Nominee [Nomination.nominee]"));
        assertEquals(List.of(unknown), RoleSelections.materialize(model,
                List.of(event, person, unknown)).get("ForWork [Nomination.forWork]"));
    }

    @Test void remoteEvidenceIsRequestedOnlyForProducerScopedRoles() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.rootClass(nomination);
        GeneratedClassModel nomineeClass = new GeneratedClassModel("Nominee");
        var type = nomineeClass.addField(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.mapping().propertyPid("P31");
        model.addClass(nomineeClass);
        GeneratedClassModel workClass = new GeneratedClassModel("ForWork");
        var genre = workClass.addField(
                "genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        model.addClass(workClass);
        model.addClass(new GeneratedClassModel("Person"));
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        model.representationClasses(nomineeClass, List.of("Person"));

        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Person");
        person.type("Nominee");
        WikidataDynamicObject work = new WikidataDynamicObject("Q2", "Work");
        work.type("ForWork");
        WikidataDynamicObject event = new WikidataDynamicObject("Q3$stmt", "Nomination");
        event.type("Nomination");
        event.put("nominee", person);
        event.put("forWork", work);

        class RecordingApi extends FakeWikidataApiClient {
            List<String> asked = List.of();
            @Override public PartialEntities getEntityClaimsPartial(
                    List<String> qids, List<String> pids, BatchLog log) {
                asked = List.copyOf(qids);
                return super.getEntityClaimsPartial(qids, pids, log);
            }
        }
        RecordingApi api = new RecordingApi();
        api.entity("Q1", "Person", Map.of("P31", List.of("Q5")));
        api.entity("Q2", "Work", Map.of("P31", List.of("Q11424")));

        var result = ReferentKindClassifier.apply(
                model, List.of(event, person, work), api, null);

        assertEquals(List.of("Q1"), api.asked);
        assertEquals(1, result.classified());
        assertEquals(0, result.unknown(), "ForWork is outside the P31 producer scope");
    }
}
