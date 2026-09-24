package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import wikidata.explore.model.ClassKind;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configuration change and the instances it invalidates are settled together.
 *
 * <p>What went wrong: nothing compared a saved snapshot with the model that was supposed
 * to describe it. A class rename left the instances stamped with the old name and the
 * renamed class with none; a changed evidence test left a population chosen by a rule
 * that had been replaced. Both were silent, and a snapshot cannot be told afterwards
 * which of its parts still hold.
 *
 * <p>The two halves that must not drift: the prompt appears only when something would
 * really be lost, and only an explicit choice deletes.
 */
class SnapshotInvalidationGuardTest {

    private static SnapshotInvalidationGuard.State state(
            boolean generationDiffers, String... snapshots) {
        return new SnapshotInvalidationGuard.State("Historical Positions",
                java.util.Arrays.stream(snapshots).map(File::new).toList(),
                generationDiffers);
    }

    @Test void anUnchangedApplyNeverOffersToDeleteAnything() {
        // The class editors call their apply paths on EVERY Apply with unchanged values —
        // there is a comment in renameClass saying exactly that — so a guard keyed on the
        // gesture would offer to delete every snapshot each time a reader clicked out of
        // a field.
        assertFalse(state(false, "historicalpositions.snapshot.json").needsAttention(),
                "the model has not moved, so the instances still describe it");
    }

    @Test void aNewSourceClassCanBecomeGraphWithoutDiscardingExistingInstances() {
        GeneratedProjectModel baseline = modelWithGeneratedPosition();
        GeneratedProjectModel edited = baseline.copy();
        GeneratedClassModel replacementExpansion =
                new GeneratedClassModel("PositionReplacementExpansion");
        edited.addClass(replacementExpansion);

        replacementExpansion.classKind(ClassKind.GRAPH);

        assertFalse(SnapshotInvalidationGuard.generationDiffers(baseline, edited),
                "the new graph class owns no object in the existing snapshot");
        assertTrue(edited.classes().contains(replacementExpansion),
                "settling the kind must not make the declaration vanish");
    }

    @Test void addingAClassWhileAGraphClassIsSelectedDoesNotDiscardInstances() {
        GeneratedProjectModel baseline = modelWithGeneratedPosition();
        GeneratedProjectModel edited = baseline.copy();
        GeneratedClassModel graph = new GeneratedClassModel("PositionRelevance");
        graph.classKind(ClassKind.GRAPH);
        edited.addClass(graph);
        // This is the state after the graph row was selected and Add class was used.
        GeneratedClassModel added = new GeneratedClassModel("PositionReplacementExpansion");
        edited.addClass(added);

        assertFalse(SnapshotInvalidationGuard.generationDiffers(baseline, edited));
        assertTrue(edited.classes().containsAll(List.of(graph, added)));
    }

    @Test void changingAnExistingClassStillRequiresRegeneration() {
        GeneratedProjectModel baseline = modelWithGeneratedPosition();
        GeneratedProjectModel edited = baseline.copy();

        edited.rootClass().membership(EntityBound.relation(
                "P31", List.of("Q189290"), true));

        assertTrue(SnapshotInvalidationGuard.generationDiffers(baseline, edited));
    }

    private static GeneratedProjectModel modelWithGeneratedPosition() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation(
                "P31", List.of("Q4164871"), true));
        model.rootClass(position);
        return model;
    }

    @Test void aProjectThatHasNeverGeneratedIsNotWarnedAboutLosingNothing() {
        assertFalse(state(true).needsAttention());
    }

    @Test void aRealChangeWithInstancesOnDiskIsAnExplicitDecision() {
        SnapshotInvalidationGuard.State state =
                state(true, "historicalpositions.snapshot.json");
        assertTrue(state.needsAttention());
        assertEquals(SnapshotInvalidationGuard.Decision.DISCARD_SNAPSHOTS,
                SnapshotInvalidationGuard.decision(state, 1));
        assertEquals(SnapshotInvalidationGuard.Decision.ABANDON_CHANGE,
                SnapshotInvalidationGuard.decision(state, 0));
    }

    @Test void dismissingTheDialogKeepsTheInstances() {
        // JOptionPane reports -1 when the window is closed with its own button rather
        // than by choosing. Reading that as consent would delete a snapshot nobody agreed
        // to lose, and the deletion cannot be undone.
        assertEquals(SnapshotInvalidationGuard.Decision.ABANDON_CHANGE,
                SnapshotInvalidationGuard.decision(
                        state(true, "historicalpositions.snapshot.json"), -1));
    }

    @Test void theWarningNamesEveryFileItWouldDelete() {
        String message = SnapshotInvalidationGuard.message(state(true,
                "historicalpositions.snapshot.json",
                "positionfilter.graph.snapshot.json"));
        assertTrue(message.contains("historicalpositions.snapshot.json"), message);
        assertTrue(message.contains("positionfilter.graph.snapshot.json"), message);
        assertTrue(message.contains("Historical Positions"), message);
        assertTrue(message.contains("cannot be undone"), message);
    }

    @Test void thereIsNoThirdAnswer() {
        // Two outcomes only: the change stands without the instances, or the instances
        // stand without the change. A half-valid snapshot is the state this exists to
        // make unreachable.
        assertEquals(2, SnapshotInvalidationGuard.Decision.values().length,
                List.of(SnapshotInvalidationGuard.Decision.values()).toString());
    }
}
