package domain.enrichment;

import domain.runtime.WikidataDynamicObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matching only: a story names a loaded character by its label or a Wikidata alias, and a
 * name nominates only when exactly one loaded character carries it (directive 9). The Prose
 * Edda itself has the case that must not be guessed: Vili is Odin's brother at the
 * creation and Loki's son at the binding.
 */
class LoadedNameMatchesTest {

    private static WikidataDynamicObject character(String qid, String label, String... aliases) {
        var character = new WikidataDynamicObject(qid, label);
        character.type("NorseCharacter");
        character.aliases(List.of(aliases));
        return character;
    }

    private final WikidataDynamicObject odin = character("Q43610", "Odin", "Allfather", "Wotan");
    private final WikidataDynamicObject loki = character("Q133147", "Loki");
    private final WikidataDynamicObject brother = character("Q1153419", "Vili");
    private final WikidataDynamicObject son = character("Q3554720", "Váli", "Vili");
    private final LoadedNameMatches characters = LoadedNameMatches.of(
            List.of(odin, loki, brother, son), LoadedNameMatches::labelAndAliases);

    @Test void anAliasNamesItsCharacter() {
        var result = characters.find("Thither came Allfather and craved one drink of the well.");

        assertEquals(List.of(odin), result.hits().stream().map(LoadedNameMatches.Hit::candidate).toList());
        assertEquals("Allfather", result.hits().getFirst().phrase());
    }

    @Test void aSharedNameIsReportedAsAmbiguousAndNominatesNoOne() {
        var result = characters.find("Then were taken Loki's sons, Vili and Nari.");

        assertEquals(List.of(loki), result.hits().stream().map(LoadedNameMatches.Hit::candidate).toList());
        assertEquals(1, result.ambiguous().size());
        var vili = result.ambiguous().getFirst();
        assertEquals("Vili", vili.phrase());
        assertEquals(List.of(brother, son), vili.candidates());
    }

    @Test void namesMatchWholeWordsOnlyAndEachCharacterOnce() {
        var result = characters.find("Odinsday is no name. Odin rode; then Odin spoke.");

        assertEquals(1, result.hits().size());
        assertEquals("Odin", result.hits().getFirst().phrase());
        assertEquals("Odinsday is no name. ".length(), result.hits().getFirst().offset());
    }

    @Test void skippedRangesAreLeftToWhatAnswersThem() {
        String text = "Loki and Odin";
        var result = characters.find(text, List.of(new LoadedNameMatches.Skip(0, 4)));

        assertEquals(List.of(odin), result.hits().stream().map(LoadedNameMatches.Hit::candidate).toList());
        assertTrue(characters.names("Allfather"));
    }
}
