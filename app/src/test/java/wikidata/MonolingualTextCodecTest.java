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
        String wire = "for the optical tweezers"
                + MonolingualTextCodec.mark("en");

        assertEquals("for the optical tweezers", MonolingualTextCodec.text(wire));
        assertEquals("en", MonolingualTextCodec.language(wire));
    }

    @Test void aValueStatingNoLanguageIsNotGivenOne() {
        assertEquals("", MonolingualTextCodec.mark(null));
        assertEquals("", MonolingualTextCodec.mark("  "));
        assertEquals("plain text", MonolingualTextCodec.text("plain text"));
        assertEquals("", MonolingualTextCodec.language("plain text"),
                "absence stays absence rather than defaulting to English");
    }

    @Test void textContainingTheSeparatorIsNotMistakenForALanguage() {
        assertEquals("write to a@b.example", MonolingualTextCodec.text("write to a@b.example"));
        assertEquals("", MonolingualTextCodec.language("write to a@b.example"),
                "a dotted host is not a language tag");
        assertEquals("ends with @", MonolingualTextCodec.text("ends with @"));
    }

    @Test void aRegionalTagSurvivesWhole() {
        String wire = "texto" + MonolingualTextCodec.mark("pt-br");

        assertEquals("pt-br", MonolingualTextCodec.language(wire));
        assertEquals("texto", MonolingualTextCodec.text(wire));
    }

    @Test void anUntaggedValueIsAdmittedByAnyRequest() {
        assertTrue(MonolingualTextCodec.isIn("plain", "en"),
                "a value stating no language contradicts nothing that was asked for");
        assertTrue(MonolingualTextCodec.isIn("text" + MonolingualTextCodec.mark("EN"), "en"),
                "a language tag is not case sensitive");
        assertFalse(MonolingualTextCodec.isIn("text" + MonolingualTextCodec.mark("sv"), "en"));
    }

    @Test void askingForNoLanguageKeepsEveryWording() {
        assertTrue(MonolingualTextCodec.isIn("text" + MonolingualTextCodec.mark("sv"), ""));
        assertTrue(MonolingualTextCodec.isIn("text" + MonolingualTextCodec.mark("nn"), null));
    }
}
