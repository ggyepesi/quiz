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
        working.addField("USState", new DomainField(
                "USState", "admissionDate", false, false,
                objectview.field.FieldKind.ORDERED));

        var converted = ViewableToWdo.convertDomain(
                working.memberRoots(), working.groupRootBindings(), working);
        // Simulate an older base-typed reference copy of the same logical entity.
        // Snapshot merging must retain only the most-specific direct claim.
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
                .filter(value -> "Alabama".equals(value.qid())).findFirst().orElseThrow();

        assertEquals("USState", restoredAlabama.typeName());
        assertEquals(java.util.Set.of("USState"),
                restoredAlabama.directClassNames());
        assertEquals("State", restored.baseType("USState"));
        assertEquals(2, restored.instancesOf("State").size());
        assertEquals(List.of("Alabama"), restored.instancesOf("USState").stream()
                .map(objectview.Viewable::getIdentifier).toList());
        assertNotNull(restored.fieldSchema("USState").field("admissionDate"));

        JsonNode json = new ObjectMapper().readTree(file);
        JsonNode alabamaJson = json.findParents("qid").stream()
                .filter(entity -> "Alabama".equals(entity.path("qid").asText()))
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

    private static WikidataDynamicObject state(String name) {
        WikidataDynamicObject state = new WikidataDynamicObject(name, name);
        state.type("State");
        state.put("name", name);
        return state;
    }
}
