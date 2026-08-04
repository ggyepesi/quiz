package flag;

import language.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.transform.app.ViewableToWdo;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StateLanguagesTest {

    @Test
    void statesUseTheCanonicalLanguageObjectsFromTheLanguageDomain()
            throws Exception {
        States states = new States();
        states.getStates().put("France", new State("France"));
        states.getStates().put(
                "United States", new State("United States"));

        states.readLanguages();

        assertEquals(List.of(
                        "French", "Corsican", "Basque", "Breton", "Occitan"),
                names(states.getStates().get("France")));
        assertEquals(List.of("English"),
                names(states.getStates().get("United States")));
        assertFalse(states.getStates().get("France").getLanguages()
                        .get(0).getLeafFamilies().isEmpty(),
                "State references must retain the rich Language object");
        assertEquals(2, states.getStates().size(),
                "language data must not manufacture extra State roots");
    }

    @Test
    void stateLanguageReferencesSurviveSnapshotRoundTrip(
            @TempDir Path directory) throws Exception {
        States states = new States();
        State france = new State("France");
        states.getStates().put(france.getName(), france);
        states.readLanguages();

        ReflectionDomain live =
                new ReflectionDomain(List.of(france));
        List<WikidataDynamicObject> roots =
                ViewableToWdo.pool(List.of(france), live);
        assertEquals(List.of(
                        "French", "Corsican", "Basque", "Breton", "Occitan"),
                dynamicNames(roots.get(0)));

        File snapshot = directory.resolve(
                "state-languages.snapshot.json").toFile();
        WikidataDynamicObjectJsonStore store =
                new WikidataDynamicObjectJsonStore();
        store.saveWithFieldGraph(roots, snapshot, live);

        WikidataDynamicObject loadedFrance =
                store.loadAllWithFieldGraph(snapshot).objects().stream()
                        .filter(value -> "State".equals(value.typeName())
                                && "France".equals(value.getIdentifier()))
                        .findFirst().orElseThrow();
        assertEquals(List.of(
                        "French", "Corsican", "Basque", "Breton", "Occitan"),
                dynamicNames(loadedFrance));

        WikidataDynamicObject loadedFrench =
                dynamicLanguages(loadedFrance).stream()
                        .filter(value -> "French".equals(
                                value.getDisplayName()))
                        .findFirst().orElseThrow();
        assertEquals("français", loadedFrench.get("nativeName"));
        assertEquals("L1: 74.170080 million",
                loadedFrench.get("speakers"));
        assertFalse(((List<?>) loadedFrench.get("countries")).isEmpty());
        assertFalse(((List<?>) loadedFrench.get("leafFamilies")).isEmpty());
        assertFalse(((List<?>) loadedFrench.get("scripts")).isEmpty(),
                "minor fields must remain in the snapshot data even when "
                        + "the default card hides them");
    }

    private static List<String> names(State state) {
        return state.getLanguages().stream()
                .map(Language::getName)
                .toList();
    }

    private static List<String> dynamicNames(
            WikidataDynamicObject state) {
        return dynamicLanguages(state).stream()
                .map(WikidataDynamicObject::getDisplayName)
                .toList();
    }

    private static List<WikidataDynamicObject> dynamicLanguages(
            WikidataDynamicObject state) {
        return ((List<?>) state.get("languages")).stream()
                .map(WikidataDynamicObject.class::cast)
                .toList();
    }
}
