package wikidata.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every id a configuration editor shows is a link reading "label (Q5)" (#404). */
class WikidataIdTextTest {

    @BeforeEach @AfterEach void fresh() {
        WikidataLabels.reset();
    }

    @Test void anIdReadsAsItsLabelOnceKnownAndAsItselfUntilThen() {
        WikidataIdText status = new WikidataIdText();
        status.setPlainText("property P31 points to Q5");
        assertEquals("property P31 points to Q5", status.shownText());
        assertTrue(status.getText().contains("href=\"https://www.wikidata.org/wiki/Q5\""),
                "an unlabelled id is still a link: " + status.getText());

        WikidataLabels.seed(Map.of("P31", "instance of", "Q5", "human"));
        assertEquals("property instance of (P31) points to human (Q5)", status.shownText());
    }

    @Test void labelsAreFetchedOnceInBatchesTheLookupCanTake() {
        List<List<String>> calls = new ArrayList<>();
        WikidataLabels.resolveWith((ids, found) -> {
            calls.add(ids);
            found.accept(Map.of(ids.getFirst(), "first"));
        });
        List<String> ids = IntStream.rangeClosed(1, 120).mapToObj(i -> "Q" + i).toList();
        WikidataLabels.request(ids);
        WikidataLabels.request(List.of("Q1", "Q2", "not an id"));
        WikidataLabels.flushPending();

        assertEquals(List.of(50, 50, 20), calls.stream().map(List::size).toList());
        assertEquals(120, calls.stream().mapToLong(List::size).sum(), "each id once");
        WikidataLabels.request(ids);
        WikidataLabels.flushPending();
        assertEquals(3, calls.size(), "an id is asked for at most once per session");
        assertEquals("first", WikidataLabels.label(calls.getFirst().getFirst()));
    }

    @Test void aSeededIdNeedsNoRequest() {
        List<List<String>> calls = new ArrayList<>();
        WikidataLabels.resolveWith((ids, found) -> calls.add(ids));
        WikidataLabels.seed(Map.of("P31", "instance of"));
        WikidataLabels.request(List.of("P31"));
        WikidataLabels.flushPending();
        assertTrue(calls.isEmpty(), calls.toString());
    }

}
