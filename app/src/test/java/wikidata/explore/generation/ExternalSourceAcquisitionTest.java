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
                datasource.api.SourceRuntimeServices.empty(), GenerationLog.NOOP,
                new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT);

        assertEquals(0, result.values());
        assertEquals("0 relation-route value(s), 0 Wikidata computed value(s), "
                        + "0 category membership(s), "
                        + "0 article text value(s), 0 infobox value(s), "
                        + "0 DBpedia value(s), 0 pageview value(s)",
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
                new GeneratedProjectModel(), List.of(), plan,
                datasource.api.SourceRuntimeServices.empty(),
                GenerationLog.NOOP, new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT,
                new ExternalSourceFamilyRegistry(List.of(custom)), Set.of(custom.id()));

        assertEquals(7, result.values());
        assertEquals("7 test value(s)", result.summary());
        assertEquals("7 test value(s)", result.acquiredSummary());
        assertEquals(7, result.outcome("test-family").values());
        assertThrows(IllegalArgumentException.class, () -> ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan,
                datasource.api.SourceRuntimeServices.empty(),
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

    @Test void continuedFamilyFailureRemainsVisibleToRunCompleteness() throws Exception {
        ExternalSourceFamily broken = new ExternalSourceFamily() {
            @Override public String id() { return "broken"; }
            @Override public String displayName() { return "Broken source"; }
            @Override public boolean configured(SourceExecutionPlan plan) { return true; }
            @Override public Outcome empty() {
                return new Outcome(id(), 0, "0 broken values", 1);
            }
            @Override public Outcome acquire(Context context) throws Exception {
                throw new java.io.IOException("endpoint unavailable");
            }
        };
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Result result = ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan,
                datasource.api.SourceRuntimeServices.empty(), GenerationLog.NOOP,
                new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.CONTINUE_OPTIONAL,
                new ExternalSourceFamilyRegistry(List.of(broken)), Set.of("broken"));

        assertTrue(!result.complete());
        assertEquals(List.of("Broken source acquisition failed: endpoint unavailable"),
                result.failures());
        var quality = GenerationPipeline.previewQuality(result);
        assertTrue(!quality.complete(),
                "a class preview reports the same continued acquisition failure as Enrich");
        assertEquals(result.failures(), quality.warnings());
    }

    @Test void retainedPartialFamilyResultRemainsVisibleToRunCompleteness()
            throws Exception {
        ExternalSourceFamily partial = new ExternalSourceFamily() {
            @Override public String id() { return "partial"; }
            @Override public String displayName() { return "Partial source"; }
            @Override public boolean configured(SourceExecutionPlan plan) { return true; }
            @Override public Outcome empty() {
                return new Outcome(id(), 0, "0 partial values", 1);
            }
            @Override public Outcome acquire(Context context) {
                return new Outcome(id(), 3, "3 partial values", 1,
                        List.of("One adjacency page was incomplete."));
            }
        };
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Result result = ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan,
                datasource.api.SourceRuntimeServices.empty(), GenerationLog.NOOP,
                new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT,
                new ExternalSourceFamilyRegistry(List.of(partial)), Set.of("partial"));

        assertEquals(3, result.values());
        assertTrue(!result.complete());
        assertEquals(List.of("One adjacency page was incomplete."), result.failures());
    }

    /** The replay used to name the relation-route family. Any family declaring that it
     *  produces entity references now runs first and is settled before the others. */
    @Test void aFamilyThatProducesEntityReferencesIsSettledBeforeTheOthers()
            throws Exception {
        List<String> order = new java.util.ArrayList<>();
        ExternalSourceFamily references = family("references", true, order);
        ExternalSourceFamily text = family("text", false, order);
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Applied applied =
                ExternalSourceAcquisition.applyWithSemanticReplay(
                        new GeneratedProjectModel(), new java.util.ArrayList<>(), plan,
                        datasource.api.SourceRuntimeServices.empty(), GenerationLog.NOOP,
                        new work.CancellationToken(),
                        ExternalSourceAcquisition.FailurePolicy.STRICT, List.of(), null,
                        new ExternalSourceFamilyRegistry(List.of(text, references)),
                        Set.of("text", "references"));

        assertEquals(List.of("references", "references", "text"), order,
                "the reference family must explicitly report its zero-value fixed point");
        assertTrue(applied.semantic() != null,
                "the references it added are settled through semantic convergence");
        assertEquals(2, applied.sources().values());
    }

    private static ExternalSourceFamily family(String id, boolean producesReferences,
            List<String> order) {
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        return new ExternalSourceFamily() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public boolean producesEntityReferences() {
                return producesReferences;
            }
            @Override public boolean configured(SourceExecutionPlan plan) { return true; }
            @Override public Outcome empty() { return new Outcome(id, 0, "", 1); }
            @Override public Outcome acquire(Context context) {
                order.add(id);
                int values = !producesReferences || calls.getAndIncrement() == 0 ? 1 : 0;
                return new Outcome(id, values, values + " " + id, 1);
            }
        };
    }

    private static GenerationLog log(java.util.List<String> messages) {
        return new GenerationLog() {
            @Override public void message(String text) { messages.add(text); }
            @Override public void subquery(String title, String request, String summary) { }
        };
    }
}
