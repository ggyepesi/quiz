package quiz.transform.ui;

import aux.FlexibleDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import flag.State;
import flag.USState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubclassDomainTest {

    @TempDir File dir;

    @Test void reflectionDiscoversHierarchyAndInheritedMembership() {
        State france = new State("France");
        USState alabama = new USState("Alabama");
        alabama.setAdmissionDate(FlexibleDate.parse("1819-12-14"));

        ReflectionDomain domain = new ReflectionDomain(List.of(france, alabama));

        assertEquals("State", domain.baseType("USState"));
        assertEquals(List.of(france, alabama), domain.instancesOf("State"));
        assertEquals(List.of(alabama), domain.instancesOf("USState"));
        assertNull(domain.fieldSchema("State").field("admissionDate"));
        assertNotNull(domain.fieldSchema("USState").field("admissionDate"));

        ReflectionDomain onlySubtype = new ReflectionDomain(List.of(alabama));
        assertTrue(onlySubtype.types().contains("State"));
        assertEquals(List.of(alabama), onlySubtype.instancesOf("State"));
    }

    @Test void directClassClaimsAndBaseClassRoundTripInSnapshot() throws Exception {
        WikidataDynamicObject france = state("France");
        WikidataDynamicObject alabama = state("Alabama");
        SnapshotDomain source = new SnapshotDomain(List.of(france, alabama));
        WorkingDomain working = new WorkingDomain(source);
        working.defineSubclass("USState", "State", List.of(alabama));
        working.addField("USState", objectview.field.FieldRef.described(
                "admissionDate", objectview.field.FieldKind.ORDERED,
                objectview.field.FieldKind.ORDERED, "Ordered",
                false, false, null, false, false,
                false, false, "", false, false));

        var converted = ViewableToWdo.convertDomain(
                working.memberRoots(), working.groupRootBindings(), working);
        // A base-typed duplicate of the same logical entity must not replace the
        // most-specific direct class claim during snapshot normalization.
        List<WikidataDynamicObject> roots = new java.util.ArrayList<>(
                converted.memberRoots());
        roots.add(state("Alabama"));
        File file = new File(dir, "states.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(roots, List.of(), file, working);

        var loaded = store.loadAllWithFieldGraph(file);
        SnapshotDomain restored = new SnapshotDomain(
                loaded.objects(), loaded.fieldGraph());
        WikidataDynamicObject restoredAlabama = loaded.objects().stream()
                .filter(value -> "Alabama".equals(value.getIdentifier())).findFirst().orElseThrow();

        assertEquals("USState", restoredAlabama.typeName());
        assertEquals(java.util.Set.of("USState"),
                restoredAlabama.directClassNames());
        assertEquals("State", restored.baseType("USState"));
        assertEquals(2, restored.instancesOf("State").size());
        assertEquals(List.of("Alabama"), restored.instancesOf("USState").stream()
                .map(objectview.Viewable::getIdentifier).toList());
        assertNotNull(restored.fieldSchema("USState").field("admissionDate"));
        assertNull(restored.fieldSchema("State").field("admissionDate"),
                "the subtype's own field does not leak onto the base after reload");

        JsonNode json = new ObjectMapper().readTree(file);
        JsonNode alabamaJson = json.findParents("id").stream()
                .filter(entity -> "Alabama".equals(entity.path("id").asText()))
                .findFirst().orElseThrow();
        assertTrue(alabamaJson.path("classes").toString().contains("\"USState\""));
        assertTrue(json.findValues("baseType").stream()
                .anyMatch(value -> "State".equals(value.asText())));
    }

    @Test void rejectsAZeroMatchBaseAndReportsAssignedCount() {
        WikidataDynamicObject france = state("France");
        WikidataDynamicObject alabama = state("Alabama");
        WikidataDynamicObject texas = state("Texas");
        WorkingDomain working = new WorkingDomain(
                new SnapshotDomain(List.of(france, alabama, texas)));
        working.defineSubclass("USState", "State", List.of(alabama));

        // A base matching NONE of the selected members is rejected — no silent 0-member class.
        assertThrows(IllegalArgumentException.class,
                () -> working.defineSubclass("Weird", "USState", List.of(france)));
        assertFalse(working.types().contains("Weird"));

        // Only base-matching members are assigned, and the count is returned.
        int assigned = working.defineSubclass("USState", "State", List.of(alabama, texas));
        assertEquals(2, assigned);
    }

    @Test void rejectsAnEmptyDefinitionBeforeAddingTheClass() {
        WorkingDomain working = new WorkingDomain(
                new SnapshotDomain(List.of(state("France"))));

        assertThrows(IllegalArgumentException.class,
                () -> working.defineSubclass("Empty", "State", List.of()));
        assertFalse(working.types().contains("Empty"));
    }

    @Test void aFieldDefinitionOnASubtypePersistsWithoutLeakingToTheBase() throws Exception {
        WikidataDynamicObject france = state("France");
        WikidataDynamicObject alabama = state("Alabama");
        WorkingDomain working = new WorkingDomain(
                new SnapshotDomain(List.of(france, alabama)));
        working.defineSubclass("USState", "State", List.of(alabama));
        var definition = new wikidata.explore.model.FieldDefinition(
                "maps", wikidata.explore.model.FieldType.IMAGE, "",
                wikidata.explore.model.FieldCardinality.COLLECTION,
                wikidata.explore.model.FieldRenderMode.INLINE);
        assertTrue(working.addField("USState", FieldDefinitions.toFieldRef(definition)));

        var converted = ViewableToWdo.convertDomain(
                working.memberRoots(), working.groupRootBindings(), working);
        File file = new File(dir, "subtype-definition.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(converted.memberRoots(), List.of(), file, working);
        var loaded = store.loadAllWithFieldGraph(file);
        var restored = new SnapshotDomain(loaded.objects(), loaded.fieldGraph());

        assertNotNull(restored.fieldSchema("USState").field("maps"),
                "a field declared on a subtype survives snapshot + reload");
        assertNull(restored.fieldSchema("State").field("maps"),
                "the subtype's own declared field does not leak onto the base");
    }

    @Test void completeNewFieldDefinitionSurvivesSnapshot() throws Exception {
        WorkingDomain working = new WorkingDomain(
                new SnapshotDomain(List.of(state("Ashmore"))));
        var definition = new wikidata.explore.model.FieldDefinition(
                "maps", wikidata.explore.model.FieldType.IMAGE, "",
                wikidata.explore.model.FieldCardinality.COLLECTION,
                wikidata.explore.model.FieldRenderMode.INLINE);
        assertTrue(working.addField("State", FieldDefinitions.toFieldRef(definition)));

        var converted = ViewableToWdo.convertDomain(
                working.memberRoots(), working.groupRootBindings(), working);
        File file = new File(dir, "field-definition.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(converted.memberRoots(), List.of(), file, working);
        var loaded = store.loadAllWithFieldGraph(file);
        var restored = new SnapshotDomain(loaded.objects(), loaded.fieldGraph());

        objectview.field.FieldRef maps = restored.fieldSchema("State").field("maps");
        assertNotNull(maps);
        assertTrue(maps.collection());
        assertEquals(objectview.field.FieldKind.MEDIA, maps.valueKind());
        assertTrue(maps.inline());
    }

    private static WikidataDynamicObject state(String name) {
        WikidataDynamicObject state = new WikidataDynamicObject(name, name);
        state.type("State");
        state.put("name", name);
        return state;
    }
}
