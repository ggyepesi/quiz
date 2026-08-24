package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceExecutionPlan;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSourceAcquisitionTest {

    @Test void anEmptyPlanIsOneSharedNoOpForGenerateAndEnrich() throws Exception {
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Result result = ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan,
                null, null, GenerationLog.NOOP, new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT);

        assertEquals(0, result.values());
        assertEquals("0 category membership(s), 0 infobox value(s), 0 DBpedia value(s)",
                result.summary());
    }

    @Test void continuePolicyCoversEveryFamilyButNeverCancellation() throws Exception {
        java.util.List<String> messages = new java.util.ArrayList<>();
        GenerationLog log = log(messages);

        assertEquals("fallback", ExternalSourceAcquisition.run("Wikipedia category",
                ExternalSourceAcquisition.FailurePolicy.CONTINUE_OPTIONAL, log,
                "fallback", () -> { throw new java.io.IOException("offline"); }));
        assertTrue(messages.getFirst().contains("Wikipedia category acquisition failed"));
        assertThrows(java.io.IOException.class, () -> ExternalSourceAcquisition.run(
                "Wikipedia category", ExternalSourceAcquisition.FailurePolicy.STRICT,
                log, "fallback", () -> { throw new java.io.IOException("offline"); }));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ExternalSourceAcquisition.run("Wikipedia category",
                        ExternalSourceAcquisition.FailurePolicy.CONTINUE_OPTIONAL,
                        log, "fallback", () -> {
                            throw new java.util.concurrent.CancellationException();
                        }));
    }

    private static GenerationLog log(java.util.List<String> messages) {
        return new GenerationLog() {
            @Override public void message(String text) { messages.add(text); }
            @Override public void subquery(String title, String request, String summary) { }
        };
    }
}
