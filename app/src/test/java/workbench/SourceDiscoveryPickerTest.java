package workbench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One decoder serves every provider, so one test covers what a discovery row means —
 *  a category, an infobox property and (later) a QID answer the same three questions. */
class SourceDiscoveryPickerTest {

    @Test
    void providerNeutralDiscoveryUsesTheSameCardsAsLegacyRows() {
        var result = new datasource.api.discovery.SourceDiscoveryResult(List.of(
                new datasource.api.discovery.DiscoveredSourceValue(
                        "Films set in Sierra Leone", 2, "Blood Diamond, Amistad")), 3);

        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(result);

        assertEquals(1, cards.size());
        assertEquals("Films set in Sierra Leone", cards.getFirst().value());
        assertEquals(2, cards.getFirst().have());
        assertEquals("Blood Diamond, Amistad", cards.getFirst().examples());
    }

    @Test void discoveryRowsBecomeSearchableViewableCards() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Films set in Sierra Leone", 2, "Blood Diamond, Amistad"),
                List.of("location", 3, "Sierra Leone")));

        assertEquals(2, cards.size());
        assertEquals("Films set in Sierra Leone", cards.getFirst().getDisplayName());
        assertEquals("Films set in Sierra Leone", cards.getFirst().value());
        assertEquals(2, cards.getFirst().fields().read("have"));
        assertEquals("Sierra Leone", cards.get(1).examples());
    }

    @Test void aRowWithoutAValueIsNotACandidate() {
        assertTrue(SourceDiscoveryPicker.rows(List.of(List.of("", 1, "ignored"))).isEmpty());
    }

    /** The category editor is reached THROUGH discovery, so a discovery that cannot run
     *  must say so: silently returning turns the button that opens it into a dead control
     *  and leaves an already-configured rule with no way to be edited. */
    @Test void aDiscoveryThatCannotEvenRunTellsTheCallerInsteadOfDoingNothing() {
        AtomicBoolean dismissed = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();

        SourceDiscoveryPicker.run(null, null, null,
                new SourceDiscoveryPicker.Spec<Object>("Observed Wikipedia categories",
                        "explanation", "nothing found", "Use selected category",
                        result -> List.of()),
                choice -> accepted.set(true), () -> dismissed.set(true));

        assertTrue(dismissed.get(), "the caller must hear that no choice is coming");
        assertFalse(accepted.get());
    }

    @Test void anUncountableCoverageCellDoesNotLoseTheCandidate() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(
                List.of(List.of("location", "n/a", "Sierra Leone")));

        assertEquals(1, cards.size());
        assertEquals(0, cards.getFirst().fields().read("have"));
    }

    @Test void oneRepeatedSourceDoesNotBecomeTwoFieldsOnEveryCard() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Films set in Sierra Leone", 1, "Blood Diamond"),
                List.of("Films set in 1999", 1, "Blood Diamond")));

        assertEquals(java.util.Set.of("have", "examples"),
                SourceDiscoveryPicker.redundantMetadata(cards));
        assertEquals(java.util.Set.of("value", "have", "examples", "sourceStructure"),
                SourceDiscoveryPicker.hiddenFields(cards));
        assertEquals("Films set in Sierra Leone",
                cards.getFirst().fields().read("discoveredValue"));
    }

    @Test void metadataRemainsWhenItDistinguishesCandidates() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Drama films", 2, "Blood Diamond, Amistad"),
                List.of("Films set in Sierra Leone", 1, "Blood Diamond")));

        assertTrue(SourceDiscoveryPicker.redundantMetadata(cards).isEmpty());
        assertEquals(java.util.Set.of("value", "sourceStructure", "discoveredValue"),
                SourceDiscoveryPicker.hiddenFields(cards));
    }

    @Test void theTwinOfTheTitleIsDroppedAsSoonAsAnythingElseRenders() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Drama films", 1, "Blood Diamond"),
                List.of("Films set in Sierra Leone", 1, "Amistad")));

        // have is uniform and hidden; examples still differ, so the body is not empty
        // and the field repeating the card's own header has no reason to be there.
        assertEquals(java.util.Set.of("have"),
                SourceDiscoveryPicker.redundantMetadata(cards));
        assertTrue(SourceDiscoveryPicker.hiddenFields(cards).contains("discoveredValue"));
    }

    @Test void aFieldEveryCandidateLeavesEmptyIsAsRedundantAsOneTheyAllShare() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(List.of(
                List.of("Drama films", 2, ""),
                List.of("Films set in Sierra Leone", 1, "")));

        assertEquals(java.util.Set.of("examples"),
                SourceDiscoveryPicker.redundantMetadata(cards),
                "an empty row on every card is the noise this exists to remove");
    }

    @Test void nothingIsRedundantAmongASingleCandidate() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.rows(
                List.of(List.of("Films set in Sierra Leone", 1, "Blood Diamond")));

        assertTrue(SourceDiscoveryPicker.redundantMetadata(cards).isEmpty(),
                "'they all agree' is vacuous for one card and would strip it bare");
        assertEquals("Blood Diamond", cards.getFirst().fields().read("examples"));
    }

    @Test void oneArticleInfoboxHidesCoverageButKeepsDifferentExampleValues() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.infoboxRows(List.of(
                List.of("Infobox film.country", 1, "United States"),
                List.of("Infobox film.runtime", 1, "143 minutes")));

        assertEquals(java.util.Set.of("have"),
                SourceDiscoveryPicker.redundantMetadata(cards));
        assertEquals(java.util.Set.of("value", "have"),
                SourceDiscoveryPicker.hiddenFields(cards));
        assertEquals("country", cards.getFirst().fields().read("discoveredValue"));
        assertEquals("United States", cards.getFirst().fields().read("examples"));
    }

    @Test void infoboxCardsShowTemplateAndParameterButReturnTheCombinedKey() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.infoboxRows(List.of(
                List.of("Infobox film.country", 1, "United States")));

        DiscoveredValueView card = cards.getFirst();
        assertEquals("Infobox film.country", card.value());
        assertEquals("country", card.fields().read("discoveredValue"));
        assertEquals("Infobox film", card.fields().read("sourceStructure"));
        assertFalse(SourceDiscoveryPicker.hiddenFields(cards).contains("sourceStructure"));
        assertFalse(SourceDiscoveryPicker.hiddenFields(cards).contains("discoveredValue"),
                "the parameter is not the header, so it is not a repeat of it");
    }

    @Test void aDottedParameterIsSplitTheWayItsReadersSplitIt() {
        List<DiscoveredValueView> cards = SourceDiscoveryPicker.infoboxRows(
                List.of(List.of("Infobox film.module.runtime", 1, "143 minutes")));

        DiscoveredValueView card = cards.getFirst();
        assertEquals("Infobox film", card.fields().read("sourceStructure"));
        assertEquals("module.runtime", card.fields().read("discoveredValue"));
        var key = datasource.evidence.InfoboxParameters.Key.parse(card.value());
        assertEquals(card.fields().read("sourceStructure"), key.template());
        assertEquals(card.fields().read("discoveredValue"), key.parameter());
    }

    @Test void aRowThatDoesNotNameAParameterIsNotOfferedAsOne() {
        assertTrue(SourceDiscoveryPicker.infoboxRows(List.of(
                List.of("Infobox film", 1, ""),
                List.of(".country", 1, ""),
                List.of("Infobox film.", 1, ""))).isEmpty());
    }
}
