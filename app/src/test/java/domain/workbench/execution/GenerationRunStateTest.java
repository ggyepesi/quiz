package domain.workbench.execution;

import domain.build.GenerationRun;
import domain.build.NameCollisions;
import domain.compile.codegen.GeneratedViewableRuntime;
import domain.config.ClassKind;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.datasource.EntityRef;
import domain.datasource.graph.GraphDiscoveryState;
import domain.datasource.graph.GraphExpansionCoverage;
import domain.datasource.graph.GraphExpansionPattern;
import domain.datasource.graph.GraphRelation;
import domain.datasource.graph.GraphTraversalDirection;
import domain.runtime.WikidataDynamicObject;
import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRunStateTest {
    @Test void loadingIsCleanButAcceptingAnotherRunNeedsAnExplicitSave() {
        var state = new GenerationRunState();
        var record = dynamic("Q1", "Character", "Odin");
        var loaded = new GenerationRun(null, 0, null, List.of(record), null, List.of(record));
        assertFalse(state.hasUnsavedInstances());
        state.accept(loaded, true);
        assertSame(loaded, state.current());
        assertFalse(state.hasUnsavedInstances());
        assertEquals(0, state.unsavedInstanceCount());

        var generated = new GenerationRun(null, 0, null, List.of(record), null, List.of(record));
        assertEquals(loaded, generated, "exact run ownership, not value equality, tracks a save");
        String loadedSubject = state.workflowSubject();
        state.accept(generated, false);
        assertSame(generated, state.current());
        assertNotEquals(loadedSubject, state.workflowSubject());
        assertTrue(state.hasUnsavedInstances());
        assertEquals(1, state.unsavedInstanceCount());

        String generatedSubject = state.workflowSubject();
        state.markSaved();
        assertFalse(state.hasUnsavedInstances());
        state.replace(generated);
        assertEquals(generatedSubject, state.workflowSubject(), "reusing the same run keeps its workflow");
        state.markUnsaved();
        assertTrue(state.hasUnsavedInstances(), "editing a saved run in place needs Save again");
        assertEquals(generatedSubject, state.workflowSubject());
    }

    @Test void forgettingADeclarationKeepsTheCarriedRuntimeButSwitchingProjectsReleasesIt() {
        var closes = new AtomicInteger();
        var loader = new URLClassLoader(new URL[0], null) {
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
            }
        };
        var runtime = new GeneratedViewableRuntime(null, "Character", "", null, loader, null);
        var state = new GenerationRunState();
        var loaded = new GenerationRun(null, 0, null, List.of(), runtime, List.of());
        var replacement = new GenerationRun(null, 0, null, List.of(), runtime, List.of());
        state.accept(loaded, true);
        state.replace(replacement);
        assertSame(replacement, state.current());
        assertTrue(state.hasUnsavedInstances());
        assertEquals(0, closes.get());

        String subject = state.workflowSubject();
        state.replaceProject(null);
        assertNull(state.current());
        assertFalse(state.hasUnsavedInstances());
        assertNotEquals(subject, state.workflowSubject(), "the old result window no longer describes this project");
        assertEquals(1, closes.get());
        state.replaceProject(null);
        assertEquals(1, closes.get(), "clearing an empty project closes nothing again");
    }

    @Test void aRestoredLedgerIsHiddenUntilLoadAndQueueingWorkMakesTheRunUnsaved() {
        var state = new GenerationRunState();
        var edge = new GraphExpansionPattern("held-by", "Position", "Character",
                new GraphRelation("wikidata", "P39"), "OfficeHolding", "position", "holder",
                GraphTraversalDirection.INCOMING);
        var prior = new GraphDiscoveryState(List.of(edge), List.of());
        var node = EntityRef.wikidata("Q1");
        state.replaceProject(prior);
        assertSame(prior, state.graphLedger());
        assertSame(GraphDiscoveryState.EMPTY, state.visibleGraphLedger());

        state.accept(new GenerationRun(null, 0, null, List.of(), null, List.of()), true);
        assertEquals(prior, state.visibleGraphLedger(), "loading preserves dormant expansion patterns");
        assertFalse(state.queueExpansion(null, node));
        assertFalse(state.hasUnsavedInstances());

        assertTrue(state.queueExpansion(edge, node));
        assertEquals(List.of(node), state.graphLedger().coverage(edge, GraphExpansionCoverage.State.QUEUED)
                .stream().map(GraphExpansionCoverage::node).toList());
        assertTrue(state.hasUnsavedInstances());
        state.markSaved();
        assertFalse(state.hasUnsavedInstances());
        state.replaceProject(null);
        assertSame(GraphDiscoveryState.EMPTY, state.graphLedger());
    }

    @Test void aContinuationExplainsOnlyTheNextGenerationPreview() {
        var state = new GenerationRunState();
        assertEquals("", state.takeGenerationReason());
        state.explainNextGeneration("Expand 3 graph-frontier node(s)");
        assertEquals("Expand 3 graph-frontier node(s)", state.takeGenerationReason());
        assertEquals("", state.takeGenerationReason());
    }

    @Test void collisionInspectionKeepsExactTypedInstancesAndDoesNotTreatRepeatedPartsAsAmbiguities() {
        var state = new GenerationRunState();
        var model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Character"));
        var partClass = new GeneratedClassModel("Discovery");
        partClass.classKind(ClassKind.OWNED);
        model.addClass(partClass);
        var one = dynamic("Q1", "Character", "Lycus");
        var two = dynamic("Q2", "Character", "Lycus");
        var part = dynamic("Q1", "Discovery", "Lycus");
        part.typeKey("Discovery@Character.discovery");
        var secondPart = dynamic("Q2", "Discovery", "Lycus");
        secondPart.typeKey("Discovery@Character.discovery");
        Viewable materialized = new ViewableAdapter() {
            @Override public String typeName() { return "Character"; }
            @Override public String getIdentifier() { return "Q1"; }
            @Override public String getDisplayName() { return "Lycus"; }
        };

        var participants = GenerationRunState.collisionParticipants(
                List.of(one, two, part), List.of(materialized));
        assertSame(materialized, participants.get(new NameCollisions.InstanceIdentity("Character", "Q1")));
        assertSame(part, participants.get(new NameCollisions.InstanceIdentity("Discovery@Character.discovery", "Q1")));

        state.replace(new GenerationRun(model, 0, null,
                List.of(one, two, part, secondPart), null, List.of(materialized)));
        var report = state.refreshNameCollisions();
        assertEquals(List.of(NameCollisions.Meaning.ENTITY_AMBIGUITY, NameCollisions.Meaning.OWNED_REPETITION),
                report.stream().map(NameCollisions.ClassCollisions::meaning).toList());
        assertEquals(1, state.nameCollisions().size());
        assertSame(materialized, state.nameCollisions().getFirst().entities().getFirst());
        assertSame(two, state.nameCollisions().getFirst().entities().getLast(),
                "an unmapped participant stays the real dynamic object");
        state.replaceProject(null);
        assertTrue(state.nameCollisions().isEmpty());
    }

    private static WikidataDynamicObject dynamic(String id, String type, String label) {
        var value = new WikidataDynamicObject(id, label);
        value.type(type);
        return value;
    }
}
