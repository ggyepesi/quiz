package wikidata.explore.generation;

import datasource.EntityRef;
import datasource.graph.constraint.GraphEvidenceCondition;
import datasource.graph.constraint.GraphEvidenceConditionResult;
import org.junit.jupiter.api.Test;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.extract.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a graph result is one operation for the desktop and a headless build, and it
 * waits for the reviewer when its graph says so.
 *
 * <p>An undecidable (Review) entry used to be included or excluded by the graph's stored
 * disposition the moment the result was applied. A headless build therefore had no way to
 * stop for a person: it would apply whatever the default said. AWAIT_DECISION is now the
 * default disposition, and applying a result that still holds such an entry without a
 * manual decision changes nothing and names the entry — the AWAITING_DECISION state.
 */
class GraphApplicationTest {

    @Test void anUndecidedReviewEntryStopsTheApplicationAndIsNamed() throws Exception {
        GeneratedProjectModel model = model();
        GenerationRun current = run(model, generated("Q1"), generated("Q2"));
        GraphDiscoveryResultStore.Artifact result = result(
                annotation("Q1", "Accepted", null),
                annotation("Q2", "Review", GraphDiscoveryResultStore.AWAIT_DECISION));

        GraphApplication.Outcome outcome = GraphApplication.apply(result, model, current);

        GraphApplication.AwaitingDecision waiting =
                assertInstanceOf(GraphApplication.AwaitingDecision.class, outcome);
        assertEquals(List.of("Q2"),
                waiting.undecided().stream().map(WikidataDynamicObject::getIdentifier).toList());
        assertTrue(waiting.message().contains("Position Q2 (Q2)"), waiting.message());
        assertFalse(result.applied(), "nothing applied is not marked as applied");
        current.runtime().close();
    }

    @Test void aManualDecisionLetsTheResultApply() throws Exception {
        GeneratedProjectModel model = model();
        GenerationRun current = run(model, generated("Q1"), generated("Q2"), generated("Q3"));
        WikidataDynamicObject review =
                annotation("Q2", "Review", GraphDiscoveryResultStore.AWAIT_DECISION);
        GraphDiscoveryResultStore.Artifact result = result(
                annotation("Q1", "Accepted", null), review);
        GraphDiscoveryResultStore.manualDecision(review, "Rejected");

        GraphApplication.Applied applied = assertInstanceOf(GraphApplication.Applied.class,
                GraphApplication.apply(result, model, current));

        assertEquals(1, applied.resultingMembers(), "narrowed to the one accepted Position");
        assertEquals(current.generatedFrom("graph-producer").generatedFromSignature(),
                applied.run().producedLike(current.generatedFrom("graph-producer"))
                        .generatedFromSignature());
        assertEquals(current.generatedFromSignature(),
                applied.run().generatedFromSignature(),
                "applying a graph regenerates nothing, so the producer carries over");
        assertTrue(applied.result().applied(), "the applied result says so, for the next Generate");
        current.runtime().close();
        applied.run().runtime().close();
    }

    /** The other side: a graph that stored Include still decides for the reviewer, which is
     *  what Historical Positions saved before the default changed. */
    @Test void aStoredIncludeDispositionAppliesReviewWithoutWaiting() throws Exception {
        GeneratedProjectModel model = model();
        GenerationRun current = run(model, generated("Q1"), generated("Q2"));
        GraphDiscoveryResultStore.Artifact result = result(
                annotation("Q1", "Accepted", null), annotation("Q2", "Review", "Include"));

        GraphApplication.Applied applied = assertInstanceOf(GraphApplication.Applied.class,
                GraphApplication.apply(result, model, current));

        assertEquals(2, applied.resultingMembers());
        current.runtime().close();
        applied.run().runtime().close();
    }

    @Test void aNewEvidenceConditionWaitsForTheReviewerByDefault() {
        GraphEvidenceCondition condition = new GraphEvidenceCondition("Evidence",
                List.of(datasource.graph.constraint.GraphPath.direct(
                        new datasource.graph.GraphRelation("wikidata", "P17"),
                        datasource.graph.GraphTraversalDirection.OUTGOING)),
                List.of(new datasource.graph.constraint.GraphRelationAbsent(
                        new datasource.graph.GraphRelation("wikidata", "P576"),
                        datasource.graph.GraphTraversalDirection.OUTGOING)), null);

        assertEquals(GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION,
                condition.reviewDisposition());
    }

    /** The walk goes through an entry awaiting a decision, so the reviewer sees everything
     *  it reaches; only an excluding disposition stops it there. */
    @Test void theWalkContinuesThroughAnEntryAwaitingADecision() {
        assertTrue(review(GraphEvidenceCondition.ReviewDisposition.AWAIT_DECISION)
                .continuesTraversal());
        assertTrue(review(GraphEvidenceCondition.ReviewDisposition.INCLUDE_AND_REPORT)
                .continuesTraversal());
        assertFalse(review(GraphEvidenceCondition.ReviewDisposition.EXCLUDE_AND_REPORT)
                .continuesTraversal());
    }

    private static GraphEvidenceConditionResult review(
            GraphEvidenceCondition.ReviewDisposition disposition) {
        return new GraphEvidenceConditionResult(GraphEvidenceConditionResult.Decision.REVIEW,
                EntityRef.wikidata("Q2"), "Evidence", disposition,
                List.of(), List.of(), List.of(), List.of(), "incomplete");
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Offices");
        model.rootClass().className("Position");
        return model;
    }

    private static WikidataDynamicObject generated(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "Position " + qid);
        value.type("Position");
        return value;
    }

    private static WikidataDynamicObject annotation(String qid, String decision,
                                                    String disposition) {
        WikidataDynamicObject candidate = new WikidataDynamicObject(qid, "Position " + qid);
        candidate.type("Position");
        WikidataDynamicObject annotation = new WikidataDynamicObject(qid, "Position " + qid);
        annotation.type("PositionGraph");
        annotation.put(GraphDiscoveryResultStore.GRAPH_DECISION, decision);
        if (disposition != null) {
            annotation.put(GraphDiscoveryResultStore.REVIEW_DISPOSITION, disposition);
        }
        annotation.put(GraphDiscoveryResultStore.ANNOTATED_INSTANCE, candidate);
        return annotation;
    }

    private static GraphDiscoveryResultStore.Artifact result(WikidataDynamicObject... records) {
        List<WikidataDynamicObject> annotations = List.of(records);
        List<WikidataDynamicObject> candidates = new ArrayList<>();
        for (WikidataDynamicObject value : annotations) {
            candidates.add((WikidataDynamicObject)
                    value.get(GraphDiscoveryResultStore.ANNOTATED_INSTANCE));
        }
        return new GraphDiscoveryResultStore.Artifact("Offices", "PositionGraph", "Position",
                annotations, candidates, new SnapshotDomain(annotations,
                        GraphDiscoveryResultStore.fieldGraph("PositionGraph", "Position",
                                annotations)));
    }

    private static GenerationRun run(GeneratedProjectModel model,
                                     WikidataDynamicObject... pool) throws Exception {
        GeneratedProjectModel snapshot = model.copy();
        GenerationPipeline pipeline = new GenerationPipeline();
        GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
        return new GenerationRun(snapshot, 0,
                wikidata.explore.rule.RuleTreeCompiler.compileProject(snapshot),
                List.of(pool), runtime, pipeline.materialize(runtime, List.of(pool)), null,
                List.of(), GenerationRun.Quality.completeQuality(), List.of(),
                GenerationRun.SelfReferenceAudit.notRun(),
                GenerationRun.OwnedCompositionAudit.notRun(),
                GenerationRun.KindClassificationAudit.notRun(),
                GenerationRun.ProjectionAudit.notRun());
    }
}
