package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferentKindClassifierTest {
    @Test void replacesRoleClassesOnlyWhenEvidenceProvidesAReplacementKind() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));
        model.addClass(new GeneratedClassModel("ForWork"));
        model.addClass(new GeneratedClassModel("Person"));
        model.addClass(new GeneratedClassModel("Film"));
        model.addEntityKindRule(new EntityKindRule("Person", List.of("Q5")));
        model.addEntityKindRule(new EntityKindRule("Film", List.of("Q11424")));

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
        assertEquals(1, result.unknown());
        assertEquals(java.util.Set.of("Person", "Film"), person.directClassNames());
        assertEquals("Film", person.typeName(),
                "equal-depth kinds use the shared alphabetical carrier rule");
        assertEquals("Film", person.typeKey());
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
}
