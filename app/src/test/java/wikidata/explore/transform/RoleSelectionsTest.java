package wikidata.explore.transform;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RoleSelection;
import wikidata.explore.model.StatementClassSource;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSelectionsTest {

    @Test void infersStatementRolesAndPreservesTheirIntersection() {
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

        WikidataDynamicObject shared = new WikidataDynamicObject("Q42", "Shared");
        WikidataDynamicObject nominationValue =
                new WikidataDynamicObject("Q1$stmt", "Nomination");
        nominationValue.type("Nomination");
        nominationValue.put("nominee", shared);
        nominationValue.put("forWork", shared);

        assertEquals(List.of("Nominee", "ForWork"),
                RoleSelections.definitions(model).stream().map(RoleSelection::name).toList());
        Map<String, List<Viewable>> selections =
                RoleSelections.materialize(model, List.of(nominationValue, shared));
        assertEquals(List.of(shared), selections.get("Nominee [Nomination.nominee]"));
        assertEquals(List.of(shared), selections.get("ForWork [Nomination.forWork]"));
    }

    @Test void explicitRoleOverridesTheCompatibilityInference() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));
        model.addSelection(new RoleSelection("Nominee", "Nomination", "source"));

        RoleSelection role = RoleSelections.definitions(model).get(0);
        assertEquals("source", role.fieldName());
    }

    @Test void twoFieldsTargetingTheSameClassRemainDistinctRoles() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel credit = new GeneratedClassModel("Credit");
        credit.statementSource(new StatementClassSource("P1"));
        credit.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        credit.addField("director", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        model.rootClass(credit);
        model.addClass(new GeneratedClassModel("Person"));

        assertEquals(List.of("Person [Credit.nominee]", "Person [Credit.director]"),
                RoleSelections.definitions(model).stream().map(RoleSelection::key).toList());
    }

    @Test void roleMembershipRoundTripsWithCanonicalReferences(@TempDir Path dir)
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));

        WikidataDynamicObject nominee = new WikidataDynamicObject("Q42", "Nominee");
        nominee.type("Nominee");
        WikidataDynamicObject event = new WikidataDynamicObject("Q1$stmt", "Nomination");
        event.type("Nomination");
        event.put("nominee", nominee);

        var file = dir.resolve("roles.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(List.of(event), file, model);
        var loaded = store.loadAllWithFieldGraph(file);

        assertEquals(List.of("Nominee [Nomination.nominee]"),
                List.copyOf(loaded.roleSelections().keySet()));
        Viewable selected = loaded.roleSelections()
                .get("Nominee [Nomination.nominee]").get(0);
        assertEquals("Q42", selected.getIdentifier());
        assertEquals(loaded.objects().stream()
                        .filter(value -> "Q42".equals(value.getIdentifier())).findFirst().orElseThrow(),
                selected, "selection membership resolves to the canonical loaded object");
    }

    @Test void productCompilerUsesPersistedMembershipWhenItExists() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Nominee"));

        WikidataDynamicObject fromField = new WikidataDynamicObject("Q1", "Field value");
        fromField.type("Nominee");
        WikidataDynamicObject persisted = new WikidataDynamicObject("Q2", "Persisted value");
        persisted.type("Nominee");
        WikidataDynamicObject event = new WikidataDynamicObject("Q3$stmt", "Nomination");
        event.type("Nomination");
        event.put("nominee", fromField);
        String key = "Nominee [Nomination.nominee]";

        var domain = ProductCompiler.compile(model,
                new java.util.ArrayList<>(List.of(event, fromField, persisted)),
                Map.of(key, List.of(persisted)));

        assertEquals(List.of(persisted), domain.selectionMembers(key),
                "persisted membership is authoritative; field rematerialization is fallback");
    }

    @Test void changedModelRoleDefinitionOverridesStalePersistedMembership() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        nomination.addField("director", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Person");
        model.rootClass(nomination);
        model.addClass(new GeneratedClassModel("Person"));

        WikidataDynamicObject current = new WikidataDynamicObject("Q1", "Current");
        current.type("Person");
        WikidataDynamicObject stale = new WikidataDynamicObject("Q2", "Stale");
        stale.type("Person");
        WikidataDynamicObject event = new WikidataDynamicObject("Q3$stmt", "Nomination");
        event.type("Nomination");
        event.put("director", current);
        String currentKey = "Person [Nomination.director]";

        var domain = ProductCompiler.compile(model,
                new java.util.ArrayList<>(List.of(event, current, stale)),
                Map.of("Person [Nomination.nominee]", List.of(stale)));

        assertEquals(List.of(current), domain.selectionMembers(currentKey));
        assertTrue(domain.selectionMembers("Person [Nomination.nominee]").isEmpty());
    }
}
