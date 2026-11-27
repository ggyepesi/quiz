package domain.workbench.execution;

import domain.build.GenerationRun;
import domain.build.GenerationRuns;
import domain.build.NameCollisions;
import domain.datasource.EntityRef;
import domain.datasource.graph.GraphDiscoveryState;
import domain.datasource.graph.GraphEdgeDefinition;
import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One open project's accepted run and the execution history that goes with it.
 * Hosts own the controls and windows; this state does not read or write project files. */
public final class GenerationRunState {
    private GenerationRun current;
    /** The exact accepted run known to match the saved snapshot. */
    private GenerationRun saved;
    /** Changes only when an open run is replaced; invalidates prepared run windows. */
    private long revision;
    private GraphDiscoveryState graphLedger = GraphDiscoveryState.EMPTY;
    private String generationReason = "";
    private List<NameCollision> nameCollisions = List.of();

    public GenerationRun current() { return current; }
    public String workflowSubject() { return "generation-run:" + revision; }
    public boolean hasUnsavedInstances() { return current != null && current != saved; }
    public int unsavedInstanceCount() { return hasUnsavedInstances() ? current.size() : 0; }

    public void markSaved() { saved = current; }
    public void markUnsaved() { saved = null; }

    /** Release the superseded runtime unless the incoming run still uses it. */
    public void replace(GenerationRun next) {
        GenerationRun previous = current;
        current = GenerationRuns.handOver(current, next);
        if (previous != current) revision++;
        if (current == null) markUnsaved();
    }

    /** Accept the run a workflow produced, or the already saved run a loader read. */
    public void accept(GenerationRun run, boolean alreadySaved) {
        replace(run);
        if (alreadySaved) markSaved();
        else markUnsaved();
        if (run != null) graphLedger = GenerationRuns.ledgerAfter(graphLedger, run);
    }

    public void replaceProject(GraphDiscoveryState ledger) {
        replace(null);
        restoreGraphLedger(ledger);
        clearNameCollisions();
    }

    /** Execution metadata can exist before an instance snapshot is loaded. */
    public GraphDiscoveryState graphLedger() { return graphLedger; }

    public GraphDiscoveryState visibleGraphLedger() {
        return current == null ? GraphDiscoveryState.EMPTY : graphLedger;
    }

    public void restoreGraphLedger(GraphDiscoveryState ledger) {
        graphLedger = ledger == null ? GraphDiscoveryState.EMPTY : ledger;
    }

    public boolean queueExpansion(GraphEdgeDefinition edge, EntityRef node) {
        GraphDiscoveryState next = graphLedger.queue(edge, node);
        if (next == graphLedger) return false;
        graphLedger = next;
        markUnsaved();
        return true;
    }

    /** A continuation may explain the next preview; an ordinary preview starts blank. */
    public void explainNextGeneration(String reason) { generationReason = reason; }

    public String takeGenerationReason() {
        String reason = generationReason;
        generationReason = "";
        return reason;
    }

    public List<NameCollision> nameCollisions() { return nameCollisions; }
    private void clearNameCollisions() { nameCollisions = List.of(); }

    /** Return the full classified report while retaining only ambiguous entities for
     * the Name collisions browser. Repeated derived labels remain in the report. */
    public List<NameCollisions.ClassCollisions> refreshNameCollisions() {
        clearNameCollisions();
        if (current == null || current.dynamicObjects() == null) return List.of();
        var report = NameCollisions.detect(current.dynamicObjects(), current.modelSnapshot());
        if (report.isEmpty()) return report;
        var entities = NameCollisions.classes(report, NameCollisions.Meaning.ENTITY_AMBIGUITY);
        var participants = collisionParticipants(current.dynamicObjects(), current.instances());
        var groups = new ArrayList<NameCollision>();
        for (var clazz : entities) {
            for (var collision : clazz.collisions()) {
                List<Viewable> instances = collision.identities().stream().map(participants::get)
                        .filter(java.util.Objects::nonNull).toList();
                if (!instances.isEmpty()) {
                    groups.add(new NameCollision(clazz.className(), collision.name(), instances));
                }
            }
        }
        nameCollisions = List.copyOf(groups);
        return report;
    }

    /** The materialized instance replaces only its exact dynamic carrier. */
    static Map<NameCollisions.InstanceIdentity, Viewable> collisionParticipants(
            Collection<? extends Viewable> dynamicObjects,
            Collection<? extends Viewable> instances) {
        Map<NameCollisions.InstanceIdentity, Viewable> found = new LinkedHashMap<>();
        if (dynamicObjects != null) dynamicObjects.forEach(value -> putParticipant(found, value));
        if (instances != null) instances.forEach(value -> putParticipant(found, value));
        return Collections.unmodifiableMap(found);
    }

    private static void putParticipant(Map<NameCollisions.InstanceIdentity, Viewable> into,
            Viewable value) {
        if (value == null || value.getIdentifier() == null || value.getIdentifier().isBlank()) return;
        into.put(new NameCollisions.InstanceIdentity(value.identityTypeName(), value.getIdentifier()), value);
    }
}
