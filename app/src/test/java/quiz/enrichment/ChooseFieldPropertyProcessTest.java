package quiz.enrichment;

import org.junit.jupiter.api.Test;
import process.CancellationToken;
import process.ProcessInputHandler;
import process.ProcessInputRequest;
import process.ProcessOutcome;
import process.ProcessRunner;
import process.ProcessStatus;
import wikidata.explore.query.core.QueryContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChooseFieldPropertyProcessTest {

    @Test
    void listsRealPropertiesWithLabelsAndReturnsTheChosenOne() throws Exception {
        String entity = """
                {"entities": {"Q782": {"claims": {
                  "P1082": [{"rank": "normal", "mainsnak":
                    {"datavalue": {"value": {"amount": "+1440000"}}}}],
                  "P571": [{"rank": "normal", "mainsnak":
                    {"datavalue": {"value": {"time": "+1959-08-21T00:00:00Z"}}}}]
                }}}}
                """;
        String labels = """
                {"entities": {
                  "P1082": {"labels": {"en": {"value": "population"}}},
                  "P571":  {"labels": {"en": {"value": "inception"}}}
                }}
                """;
        // byQid vs labels are told apart by the props= in the request URL.
        WikimediaEntityLookup.JsonFetcher fetcher = uri ->
                uri.toString().contains("props=labels") ? labels : entity;

        boolean[] sawOptions = {false};
        ProcessInputHandler pickPopulation = new ProcessInputHandler() {
            @Override public <T> T request(
                    ProcessInputRequest<T> req, CancellationToken cancellation) {
                PropertySelectionRequest selection = (PropertySelectionRequest) req;
                // the options are the entity's REAL properties, labelled, with examples
                sawOptions[0] = selection.options().stream().anyMatch(option ->
                        option.pid().equals("P1082")
                                && option.label().equals("population")
                                && option.example().equals("+1440000"));
                assertTrue(selection.options().stream()
                        .anyMatch(option -> option.pid().equals("P571")));
                return req.responseType().cast(new ChosenProperty("P1082", "population"));
            }
        };

        ProcessOutcome<ChosenProperty> outcome = new ProcessRunner(
                new QueryContext(null, null), null, pickPopulation)
                .run(new ChooseFieldPropertyProcess("Q782", "population", null, fetcher),
                        new CancellationToken());

        assertEquals(ProcessStatus.SUCCEEDED, outcome.status());
        assertTrue(sawOptions[0]);
        assertEquals("P1082", outcome.result().pid());
        assertEquals("population", outcome.result().label());
    }

    @Test
    void cancelledWhenNoPropertyChosen() throws Exception {
        WikimediaEntityLookup.JsonFetcher fetcher = uri ->
                uri.toString().contains("props=labels")
                        ? "{\"entities\":{}}"
                        : "{\"entities\":{\"Q782\":{\"claims\":{\"P1082\":[{\"rank\":\"normal\","
                                + "\"mainsnak\":{\"datavalue\":{\"value\":{\"amount\":\"+1\"}}}}]}}}}";
        ProcessInputHandler declines = new ProcessInputHandler() {
            @Override public <T> T request(
                    ProcessInputRequest<T> req, CancellationToken cancellation) {
                return req.responseType().cast(new ChosenProperty("", ""));
            }
        };

        ProcessOutcome<ChosenProperty> outcome = new ProcessRunner(
                new QueryContext(null, null), null, declines)
                .run(new ChooseFieldPropertyProcess("Q782", "population", null, fetcher),
                        new CancellationToken());

        assertEquals(ProcessStatus.CANCELLED, outcome.status());
    }
}
