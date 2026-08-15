package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.LoadedDeclaration;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fetch record is what keeps Enrich cheap — a field already loaded is skipped even
 * where Wikidata had no answer — so refreshing a field's VALUES has to be an explicit
 * act. Dropping one declaration is how the editor's per-field "re-fetch" works.
 */
class ForgetDeclarationTest {

    @Test void droppingOneDeclarationLeavesTheOthers() {
        GenerationRun run = new GenerationRun(
                new GeneratedProjectModel(), 1, null,
                List.<WikidataDynamicObject>of(), null, List.of(), null,
                List.of(new LoadedDeclaration("BirthName", "familyName", "P734", 6863),
                        new LoadedDeclaration("ForWork", "genre", "P136", 4869)));

        List<LoadedDeclaration> kept = run.loadedDeclarations().stream()
                .filter(d -> !"BirthName.familyName:P734".equals(d.key()))
                .toList();
        GenerationRun after = new GenerationRun(
                run.modelSnapshot(), run.depth(), run.plan(), run.dynamicObjects(),
                run.runtime(), run.instances(), run.remapState(), kept);

        assertEquals(1, after.loadedDeclarations().size());
        assertEquals("ForWork.genre:P136", after.loadedDeclarations().getFirst().key(),
                "only the field asked for is re-fetched; the rest stay recorded");
    }

    @Test void aKeyIdentifiesTheDeclarationWithoutItsCoverage() {
        assertEquals("BirthName.familyName:P734",
                new LoadedDeclaration("BirthName", "familyName", "P734", 1).key());
        assertEquals(LoadedDeclaration.key("BirthName", "familyName", "P734"),
                new LoadedDeclaration(" BirthName ", " familyName ", " P734 ", 9).key());
        assertTrue(new LoadedDeclaration("A", "b", "P1", -5).covered() == 0);
    }
}
