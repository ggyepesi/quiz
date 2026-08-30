package wikidata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A monolingual text states its language on the literal. Losing it made every wording
 * of one fact indistinguishable — the Nobel award rationale is stated in about thirteen
 * languages, so a field loading P6208 received two of them and could not choose.
 */
class MonolingualTextCodecTest {

    @Test void aValueCarriesTheLanguageItStates() {
        String wire = MonolingualTextCodec.encode(
                "for the optical tweezers", "en");

        assertEquals("for the optical tweezers", MonolingualTextCodec.text(wire));
        assertEquals("en", MonolingualTextCodec.language(wire));
    }

    @Test void aValueStatingNoLanguageIsNotGivenOne() {
        assertEquals("plain", MonolingualTextCodec.encode("plain", null));
        assertEquals("plain", MonolingualTextCodec.encode("plain", "  "));
        assertEquals("plain text", MonolingualTextCodec.text("plain text"));
        assertEquals("", MonolingualTextCodec.language("plain text"),
                "absence stays absence rather than defaulting to English");
    }

    @Test void textContainingTheSeparatorIsNotMistakenForALanguage() {
        assertEquals("write to a@b.example", MonolingualTextCodec.text("write to a@b.example"));
        assertEquals("", MonolingualTextCodec.language("write to a@b.example"),
                "a dotted host is not a language tag");
        assertEquals("ends with @", MonolingualTextCodec.text("ends with @"));
        assertEquals("meet me @home", MonolingualTextCodec.text("meet me @home"));
        assertEquals("", MonolingualTextCodec.language("meet me @home"));
    }

    @Test void aRegionalTagSurvivesWhole() {
        String wire = MonolingualTextCodec.encode("texto", "pt-br");

        assertEquals("pt-br", MonolingualTextCodec.language(wire));
        assertEquals("texto", MonolingualTextCodec.text(wire));
    }

    @Test void exactLanguageWinsAndUntaggedIsOnlyAFallback() {
        assertEquals(java.util.List.of("English"), MonolingualTextCodec.select(
                java.util.List.of("untagged",
                        MonolingualTextCodec.encode("English", "EN"),
                        MonolingualTextCodec.encode("Swedish", "sv")), "en"));
        assertEquals(java.util.List.of("untagged"), MonolingualTextCodec.select(
                java.util.List.of("untagged",
                        MonolingualTextCodec.encode("Swedish", "sv")), "en"));
    }

    @Test void askingForNoLanguageKeepsEveryWording() {
        assertEquals(java.util.List.of("Swedish", "Nynorsk"),
                MonolingualTextCodec.select(java.util.List.of(
                        MonolingualTextCodec.encode("Swedish", "sv"),
                        MonolingualTextCodec.encode("Nynorsk", "nn")), ""));
    }

    @Test void internalMarkersInPlainTextRoundTrip() {
        String raw = "\u001eMLT:2:enordinary text";
        assertEquals(raw, MonolingualTextCodec.text(MonolingualTextCodec.plain(raw)));
    }
}
