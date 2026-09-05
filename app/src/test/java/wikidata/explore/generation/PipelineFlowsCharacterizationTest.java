package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 0 of {@code docs/parameterized-generation-pipeline.md}: what the five flows
 * do today, recorded before anything moves.
 *
 * <p>Generate domain, Generate class preview, Sample, Enrich and Remap each author their
 * own ordering of the same phases. The design makes them parameterizations of one
 * pipeline; this pins the starting point, so a milestone that changes an order changes
 * it deliberately and says so here.
 *
 * <p>The order is read from the source because that is where it is written. These flows
 * need a network and a model to run, and the thing under test is which phase follows
 * which — a fact of the code, not of a run.
 *
 * <p>Some differences below are intentional (Remap may not acquire; Sample is bounded).
 * The rest are the discrepancies the design says must disappear, and each is named at
 * the assertion that holds it.
 */
class PipelineFlowsCharacterizationTest {

    /**
     * The design's phase vocabulary, mapped to what today's code calls each phase.
     *
     * <p>Ordered longest-first where one marker is a prefix of another, so the more
     * specific name wins.
     */
    private static final Map<String, String> PHASES = new LinkedHashMap<>();

    static {
        // Compiling through the owner is still compiling. A flow that reads
        // CompiledPipelineRun.model() has not stopped having a compile phase; it has
        // stopped having a compile of its OWN, which is what OneCompilePerRunTest holds.
        PHASES.put("CompiledPipelineRun.compile", "compile");
        PHASES.put("ProjectModelCompiler.compile", "compile");
        PHASES.put("pipeline.plan(", "plan");
        PHASES.put("extractResult(", "extract");
        PHASES.put("extract(client", "extract");
        PHASES.put("RuleTreeExtractor", "extract");
        PHASES.put(".enrichWithReport(", "acquire-statements");
        PHASES.put("new QualifierLoader", "acquire-statements");
        PHASES.put("StatementTransforms.applyIdempotent", "refresh-derived");
        // apply() is two operations under one call: first-time statement-record
        // construction and the same replayable derived-value refresh that Enrich calls
        // separately. Keep that distinction visible rather than manufacturing an
        // inversion between unlike operations.
        PHASES.put("StatementTransforms.apply(", "construct-records+refresh-derived");
        PHASES.put("applyReify", "construct-records");
        PHASES.put("SemanticConvergence.apply", "semantic");
        // The worklist's steps, named individually, because a flow that ran some of
        // them by hand looks like a flow that ran none unless the record can see them.
        // Reading a gap as evidence of absence is exactly the mistake this omission
        // caused: Remap was recorded as composing parts without settling kinds, and it
        // had been settling them all along.
        PHASES.put("SnapshotEntityKindClassifier.apply", "semantic-classify-only");
        PHASES.put("OwnedComponents.apply", "semantic-owned-only");
        PHASES.put("ExternalSourceAcquisition.apply", "external-evidence");
        PHASES.put("Canonicalization.apply", "canonicalize");
        PHASES.put("ModelAggregates.apply", "aggregate");
        // A routed phase is recognised by the STEP it registers, not by the call it used
        // to make in place. This is the characterization being replaced piece by piece:
        // as each phase moves behind the executor, what the source shows is the step,
        // and eventually the decisions themselves are the record.
        PHASES.put("ConstructRecordsStep.acquiring(",
                "construct-records+refresh-derived");
        PHASES.put("SemanticWorklistStep(", "semantic");
        PHASES.put("FinalizeStep()", "finalize");
        PHASES.put("DomainFinalization.apply", "finalize");
        PHASES.put("MaterializeStep()", "materialize");
        PHASES.put("buildRuntime(", "materialize");
    }

    /** Generate domain — the order that produced every snapshot we compare counts to. */
    @Test void generateDomainAcquiresStatementsBeforeConstructingTheirRecords()
            throws IOException {
        List<String> order = phasesOf(source("query/logical/GenerateDomainQuery.java"));

        assertEquals(List.of("compile", "plan", "extract", "acquire-statements",
                        "construct-records+refresh-derived", "semantic",
                        "external-evidence",
                        "finalize", "materialize"),
                order);
    }

    /**
     * Enrich does not construct records. Its input is an already-constructed saved
     * graph, so it replays only the idempotent derived-value transforms after loading
     * newly declared and external values.
     *
     * <p>The remaining discrepancy is narrower: Generate performs that same refresh as
     * part of {@code StatementTransforms.apply}, before semantic and external
     * acquisition, and does not replay it afterwards. That is an artifact-dependency
     * issue, not a legitimate per-flow ordering parameter.
     */
    @Test void enrichRefreshesDerivedValuesAfterAcquisition() throws IOException {
        List<String> order = phasesIn("GenerationPipeline.java", "public GenerationRun enrich(",
                "static GenerationRun.Quality reconcileQuality");

        assertTrue(order.indexOf("semantic") < order.indexOf("refresh-derived"), order.toString());
        assertTrue(order.indexOf("external-evidence") < order.indexOf("refresh-derived"),
                order.toString());
        assertFalse(order.contains("construct-records"), order.toString());
    }

    /**
     * Remap acquires nothing — the design's one hard invariant, already held.
     *
     * <p>"Network work is impossible when acquisition is NONE."
     */
    @Test void remapReachesNoNetwork() throws IOException {
        List<String> order = phasesIn("GenerationPipeline.java", "public GenerationRun remap(",
                "public GenerationRun enrich(");

        assertFalse(order.contains("extract"), order.toString());
        assertFalse(order.contains("acquire-statements"), order.toString());
        assertFalse(order.contains("external-evidence"), order.toString());
    }

    /**
     * Remap runs the semantic worklist, rather than three of its steps by hand.
     *
     * <p>Milestone 0 recorded this as "composes parts without settling kinds", and that
     * was wrong: Remap classified kinds from stored evidence before composing, and the
     * code said so. The record could not see the call, because the marker set had no
     * entry for the classifier — a gap read as evidence of absence.
     *
     * <p>What Remap actually lacked was smaller and still real: it stamped roles only on
     * the components it had just made, never on the pool before classifying, and it did
     * ONE pass where the worklist runs to a fixed point. Composition can unlock
     * composition, so one pass is a different answer rather than a cheaper one.
     */
    @Test void remapRunsTheWholeSemanticWorklist() throws IOException {
        List<String> order = phasesIn("GenerationPipeline.java", "public GenerationRun remap(",
                "public GenerationRun enrich(");

        assertTrue(order.contains("semantic"), order.toString());
        assertFalse(order.contains("semantic-owned-only"),
                "no step of the worklist is run beside the worklist: " + order);
        assertFalse(order.contains("semantic-classify-only"), order.toString());
    }

    /**
     * The single-class preview omits the semantic worklist entirely.
     *
     * <p>Its own comment says so and explains why — parts would be empty here. The
     * design's answer is that a preview differs by scope and limits, never by dropping a
     * phase, and that this method disappears into a bounded request.
     */
    @Test void theSingleClassPreviewOmitsTheSemanticWorklist() throws IOException {
        List<String> order = phasesIn("GenerationPipeline.java", "public GenerationRun fullRun(\n            GeneratedProjectModel snapshot,\n            int depth,\n            WikidataSparqlClient client,\n            GenerationLog log,\n            wikidata.api.WikidataApiClient entityApi,\n            work.CancellationToken cancellation,\n            datasource.api.SourceExecutionPlan sourcePlan,\n            WikidataSparqlClient dbpedia)",
                "public GenerationRun remap(");

        assertFalse(order.contains("semantic"), order.toString());
        assertFalse(order.contains("finalize"), order.toString());
    }

    /**
     * Sample runs acquisition, construction, the semantic worklist and materialization —
     * and no finalization.
     *
     * <p>It is spread over several classes because sampling is three routes sharing one
     * production step; the phases are what matters here. Finalization is the one the
     * design says should also run: "included instances have the same semantics".
     */
    @Test void sampleRunsEveryPhaseButFinalization() throws IOException {
        List<String> order = new ArrayList<>();
        order.addAll(phasesOf(source("query/logical/SampledClassProduction.java")));
        order.addAll(phasesOf(source("query/logical/SampledDerivation.java")));
        order.addAll(phasesOf(source("query/logical/ClassSampleResults.java")));

        assertTrue(order.contains("extract"), order.toString());
        assertTrue(order.contains("acquire-statements"), order.toString());
        assertTrue(order.contains("construct-records"), order.toString());
        assertTrue(order.contains("semantic"),
                "a sampled instance is only what a generated one is if this runs");
        assertTrue(order.contains("materialize"), order.toString());
        assertFalse(order.contains("finalize"),
                "the phase a sample still skips: " + order);
    }

    /** Five flows, five orderings — the count the design exists to reduce to one. */
    @Test void everyFlowAuthorsItsOwnOrder() throws IOException {
        List<List<String>> orders = List.of(
                phasesOf(source("query/logical/GenerateDomainQuery.java")),
                phasesIn("GenerationPipeline.java", "public GenerationRun enrich(",
                        "static GenerationRun.Quality reconcileQuality"),
                phasesIn("GenerationPipeline.java", "public GenerationRun remap(",
                        "public GenerationRun enrich("),
                phasesIn("GenerationPipeline.java", "public GenerationRun fullRun(\n            GeneratedProjectModel snapshot,\n            int depth,\n            WikidataSparqlClient client,\n            GenerationLog log,\n            wikidata.api.WikidataApiClient entityApi,\n            work.CancellationToken cancellation,\n            datasource.api.SourceExecutionPlan sourcePlan,\n            WikidataSparqlClient dbpedia)",
                        "public GenerationRun remap("),
                sampleOrder());

        assertEquals(orders.size(), orders.stream().distinct().count(),
                "no two flows describe the same sequence, which is the finding: "
                        + orders);
    }

    private static List<String> phasesIn(String file, String from, String to)
            throws IOException {
        String text = source("generation/" + file);
        int start = text.indexOf(from);
        assertTrue(start >= 0, "method moved or was renamed: " + from);
        int end = text.indexOf(to, start + from.length());
        return phasesOf(end < 0 ? text.substring(start) : text.substring(start, end));
    }

    /** The phases this text performs, in order, each recorded once per consecutive run. */
    private static List<String> phasesOf(String text) {
        text = withoutComments(text);
        Pattern markers = Pattern.compile(PHASES.keySet().stream()
                .map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow());
        List<String> found = new ArrayList<>();
        Matcher matcher = markers.matcher(text);
        while (matcher.find()) {
            String phase = PHASES.get(matcher.group());
            if (found.isEmpty() || !found.getLast().equals(phase)) found.add(phase);
        }
        return found;
    }

    private static List<String> sampleOrder() throws IOException {
        List<String> order = new ArrayList<>();
        order.addAll(phasesOf(source("query/logical/SampledClassProduction.java")));
        order.addAll(phasesOf(source("query/logical/SampledDerivation.java")));
        order.addAll(phasesOf(source("query/logical/ClassSampleResults.java")));
        return order;
    }

    /** Comments explain neighboring phases and must never masquerade as calls. */
    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of("src/main/java/wikidata/explore/" + path));
    }
}
