package quiz.transform.app;

import flag.State;
import language.Language;
import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSchema;
import org.junit.jupiter.api.Test;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The @DisplayField designation is a role, not a Java annotation the snapshot can keep,
 * so it must be PERSISTED. A reflection domain marks its display field DISPLAY; after a
 * save→load round-trip the loaded snapshot schema must report the same role, or a loaded
 * type shows both the synthetic "Display label" and its real name field (the duplication
 * this guards against). See SnapshotFieldGraph.FieldShape.display.
 */
class DisplayRolePersistenceTest {

    private static FieldRole roleOf(FieldSchema schema, String field) {
        return schema.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst().map(FieldRef::role).orElse(null);
    }

    @Test
    void displayRoleSurvivesSaveAndLoad() throws Exception {
        State france = new State("France");
        Language french = new Language("French");
        french.setNativeName("français");
        france.getLanguages().add(french);

        ReflectionDomain source = new ReflectionDomain(List.of(france));
        // Precondition: reflection reads @DisplayField as the DISPLAY role.
        assertEquals(FieldRole.DISPLAY, roleOf(source.fieldSchema("Language"), "name"),
                "reflection should mark @DisplayField name as DISPLAY");

        File snapshot = File.createTempFile("display-role", ".snapshot.json");
        snapshot.deleteOnExit();
        var converted = ViewableToWdo.convertDomain(source.memberRoots(), List.of(), source);
        new WikidataDynamicObjectJsonStore()
                .saveWithFieldGraph(converted.memberRoots(), snapshot, source);

        var loaded = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(snapshot);

        // The referenced (field-only) type keeps its DISPLAY designation across the round-trip.
        FieldSchema language = loaded.fieldGraph().fieldSchema("Language", Set.of());
        assertNotNull(language, "Language schema should load");
        assertEquals(FieldRole.DISPLAY, roleOf(language, "name"),
                "Language.name should load as DISPLAY, not NONE");

        // The member root's display field too.
        assertEquals(FieldRole.DISPLAY,
                roleOf(loaded.fieldGraph().fieldSchema("State", Set.of()), "name"),
                "State.name should load as DISPLAY");
    }
}
