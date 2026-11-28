package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.build.DomainSave;
import domain.build.GenerateDomainPipeline;
import domain.build.GenerateDomainProcess;
import domain.build.GenerationOperationProcess;
import domain.build.GenerationRecoveryStore;
import domain.build.GenerationRun;
import domain.build.GraphCheckpoint;
import domain.compile.transform.SelfReferenceLedger;
import domain.config.EntityBound;
import domain.config.FieldCardinality;
import domain.config.GeneratedProjectModel;
import domain.datasource.graph.GraphDiscoveryState;
import domain.datasource.schema.FieldType;
import domain.runtime.WikidataDynamicObject;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.ProcessOutcome;
import process.ProcessStatus;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GenerationWorkflowsTest {
    @TempDir Path directory;

    @Test void allFourActionsKeepTheirPlansAndExplicitAcceptance() throws Exception {
        var model = model();
        var run = previous(model);
        var events = new ArrayList<String>();
        var accepted = new ArrayList<GenerationRun>();
        var workflows = new GenerationWorkflows(value -> {
            events.add("accept");
            accepted.add(value);
        }, () -> events.add("show"));

        var generateClass = workflows.generateClass(model, 3, run);
        var generateDomain = workflows.generateDomain(model, GraphDiscoveryState.EMPTY,
                Map.of("Position", List.of("Q2")), DomainStorage.in(directory.toFile()),
                "Expand 1 graph-frontier node(s)", run, file -> fail("Unexpected recovery prompt"));
        var remap = workflows.remap(model, run, GraphDiscoveryState.EMPTY);
        var enrich = workflows.enrich(model, run, GraphDiscoveryState.EMPTY);

        assertEquals("generate-class", generateClass.id());
        assertEquals("Generate class — Position", generateClass.plan().title());
        assertEquals("Scope", generateClass.plan().tabs().getFirst().title());
        assertEquals("Position", scopeValue(generateClass, "Selected class"));
        assertEquals(3, scopeValue(generateClass, "Depth"));
        assertEquals(List.of("generate-class"), phases(generateClass));

        assertEquals("generate-domain", generateDomain.id());
        assertEquals("Generate domain — Expand 1 graph-frontier node(s)", generateDomain.plan().title());
        assertEquals("Generate domain — Positions", generateDomain.logTitle());
        assertEquals("Classes", generateDomain.plan().tabs().getFirst().title());
        assertEquals(1, scopeValue(generateDomain, "Pending QIDs from applied graph results"));
        assertInstanceOf(GenerateDomainProcess.class, generateDomain.process());

        assertEquals("remap", remap.id());
        assertEquals("Remap domain", remap.plan().title());
        assertEquals("Will not run — this run has no pre-reification pool to replay from",
                scopeValue(remap, "Self-reference rule"));
        assertFalse(remap.process().plan().parameters().containsKey("cacheMb"),
                "local remap does not acquire data");

        assertEquals("enrich", enrich.id());
        assertEquals("Enrich domain", enrich.plan().title());
        assertEquals("Will not run — Enrich adds values without reifying statements",
                scopeValue(enrich, "Self-reference rule"));
        assertTrue(enrich.pipeline().snapshot().stream().anyMatch(state ->
                state.phase().details().contains("Position — P18")));
        assertInstanceOf(GenerationOperationProcess.class, enrich.process());

        var actions = List.of(generateClass, generateDomain, remap, enrich);
        for (var action : actions) {
            var results = action.results(ProcessOutcome.succeeded(run, "Done"));
            assertEquals("Accept", results.applyVerb());
            assertEquals("Summary", results.tabs().getFirst().title());
            assertSame(run, results.tabs().getFirst().cards().getFirst().decision().get());
            assertEquals("All objects (1)", results.tabs().getLast().title());
            assertNull(results.tabs().getLast().cards().getFirst().decision().get(),
                    "browsing a result instance cannot accept the run");
        }
        for (var action : actions) {
            for (var tab : action.plan().tabs()) {
                assertDeclaresWhatItWrites(tab.cards().getFirst());
            }
            assertDeclaresWhatItWrites(action.results(ProcessOutcome.succeeded(run, "Done"))
                    .tabs().getFirst().cards().getFirst().view());
        }
        assertTrue(events.isEmpty(), "planning or displaying a result does not install or reveal it");
        for (var action : actions) {
            action.apply(List.of(run));
            assertEquals("accept", events.getLast());
            action.afterApply();
            assertEquals("show", events.getLast());
        }
        assertEquals(List.of(run, run, run, run), accepted);
        assertEquals(List.of("accept", "show", "accept", "show", "accept", "show", "accept", "show"), events);
    }

    @Test void thePlanProcessAndApplyBoundaryAgreeOnTheSavedCompletenessSetting() throws Exception {
        var action = new GenerationWorkflows(ignored -> { }, () -> { })
                .generateClass(model(), 2, null);
        String executionValue = action.process().plan().parameters().get("requireComplete");
        SwingUtilities.invokeAndWait(() -> {
            var complete = Arrays.stream(action.executionSettings().getComponents())
                    .filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                    .filter(box -> "Require a complete result".equals(box.getText()))
                    .findFirst().orElseThrow();
            // Inspect the user's saved preference without changing it. The separate
            // settings-panel tests exercise edits against a non-persistent profile.
            assertEquals(Boolean.toString(complete.isSelected()), executionValue);
            assertEquals(!complete.isSelected(), action.applyAllowed(ProcessStatus.PARTIAL));
        });
        assertTrue(action.applyAllowed(ProcessStatus.SUCCEEDED));
    }

    @Test void recoveryChoiceUsesTheExactFileAndCannotOmitPendingGraphInputs() throws Exception {
        var model = model();
        var storage = DomainStorage.in(directory.toFile());
        var run = previous(model);
        var recovery = GenerationRecoveryStore.in(storage, model.name());
        var checkpoint = GraphCheckpoint.finalGraph(run.dynamicObjects(), List.of(),
                GraphDiscoveryState.EMPTY, run.quality(), DomainSave.signature(model));
        recovery.save(model, checkpoint, SelfReferenceLedger.EMPTY);
        var workflows = new GenerationWorkflows(ignored -> { }, () -> { });

        var resumed = workflows.generateDomain(model, GraphDiscoveryState.EMPTY, Map.of(), storage,
                "", null, file -> {
                    assertEquals(recovery.snapshotFile(), file);
                    return GenerationWorkflows.RecoveryChoice.RESUME;
                });
        assertEquals("Resume materialization", resumed.plan().title());
        assertEquals(List.of(GenerateDomainPipeline.MATERIALIZE), phases(resumed));
        assertEquals(recovery.snapshotFile().getPath(), scopeValue(resumed, "Recovery file"));
        assertFalse(resumed.process().plan().parameters().containsKey("cacheMb"));
        assertNull(workflows.generateDomain(model, GraphDiscoveryState.EMPTY, Map.of(), storage,
                "", null, file -> GenerationWorkflows.RecoveryChoice.CANCEL));

        var requested = workflows.generateDomain(model, GraphDiscoveryState.EMPTY, Map.of(), storage,
                "", null, file -> GenerationWorkflows.RecoveryChoice.GENERATE);
        assertInstanceOf(GenerateDomainProcess.class, requested.process());

        var pending = workflows.generateDomain(model, GraphDiscoveryState.EMPTY,
                Map.of("Position", List.of("Q2")), storage, "", null,
                file -> fail("A recovery graph must not be offered over new population inputs"));
        assertInstanceOf(GenerateDomainProcess.class, pending.process());
    }

    /** A runtime card shows only its declared fields (#363), so every written field is declared. */
    private static void assertDeclaresWhatItWrites(objectview.Viewable card) {
        var record = assertInstanceOf(domain.transform.DynamicViewable.class, card);
        var schema = record.dynamicFieldSchema();
        assertNotNull(schema, record.typeName() + " declares its record type");
        var declared = schema.fields().stream().map(objectview.field.FieldRef::name).toList();
        for (String written : record.dynamicFieldValues().keySet()) {
            assertTrue(declared.contains(written), record.typeName() + " declares " + written);
        }
    }

    private static GeneratedProjectModel model() {
        var model = new GeneratedProjectModel();
        model.name("Positions");
        var position = model.rootClass();
        position.className("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), false));
        position.addField("image", FieldType.IMAGE, FieldCardinality.SINGLE).mapping().propertyPid("P18");
        return model;
    }

    private static GenerationRun previous(GeneratedProjectModel model) {
        var position = new WikidataDynamicObject("Q1", "One");
        position.type("Position");
        return new GenerationRun(model, 1, null, List.of(position), null, List.of(position));
    }

    private static Object scopeValue(GenerationWorkflows.Action action, String field) {
        return FieldSet.of(action.plan().tabs().getFirst().cards().getFirst()).read(field);
    }

    private static List<String> phases(GenerationWorkflows.Action action) {
        return action.pipeline().snapshot().stream().map(state -> state.phase().id()).toList();
    }
}
