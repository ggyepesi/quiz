package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceExecutionPlan;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Set;

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

    @Test void aNewFamilyRunsByRegistrationWithoutChangingTheOrchestrator() throws Exception {
        ExternalSourceFamily custom = new ExternalSourceFamily() {
            @Override public String id() { return "test-family"; }
            @Override public String displayName() { return "Test family"; }
            @Override public boolean configured(SourceExecutionPlan plan) { return true; }
            @Override public Outcome empty() {
                return new Outcome(id(), 0, "0 test value(s)", 1);
            }
            @Override public Outcome acquire(Context context) {
                return new Outcome(id(), 7, "7 test value(s)", 1);
            }
        };
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Result result = ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan, null, null,
                GenerationLog.NOOP, new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT,
                new ExternalSourceFamilyRegistry(List.of(custom)), Set.of(custom.id()));

        assertEquals(7, result.values());
        assertEquals("7 test value(s)", result.summary());
        assertEquals("7 test value(s)", result.acquiredSummary());
        assertEquals(7, result.outcome("test-family").values());
        assertThrows(IllegalArgumentException.class, () -> ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan, null, null,
                GenerationLog.NOOP, new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT,
                new ExternalSourceFamilyRegistry(List.of(custom)), Set.of("typo")));
    }

    @Test void acquiredSummaryIsCumulativeOrderedAndOmitsEmptyFamilies() {
        ExternalSourceAcquisition.Result result = new ExternalSourceAcquisition.Result(List.of(
                new ExternalSourceFamily.Outcome("last", 3, "3 last value(s)", 30),
                new ExternalSourceFamily.Outcome("empty", 0, "0 empty value(s)", 10),
                new ExternalSourceFamily.Outcome("first", 2, "2 first value(s)", 20)));

        assertEquals("2 first value(s), 3 last value(s)", result.acquiredSummary());
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
