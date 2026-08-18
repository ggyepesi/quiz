package process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One configured process graph shared by plan, execution and results presentation.
 *
 * <p>Run-state, not presentation: it lives beside {@link ProcessStatus} so a headless
 * caller — a server, a test, a scripted run — can drive and read a pipeline without
 * reaching into a Swing package. Only its rendering belongs to the UI.
 * Stable phase IDs let the executor update the same nodes the user inspected before
 * pressing Execute; details are configuration, while state/summary are run-time data.
 */
public final class ProcessWorkflowPipeline {
    public enum Status { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

    public record Phase(
            String id, String title, String description, List<String> details) {
        public Phase {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("phase id");
            title = title == null || title.isBlank() ? id : title;
            description = description == null ? "" : description;
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    public record PhaseState(
            Phase phase, Status status, String summary,
            long startedAtNanos, long elapsedMillis) { }

    private final List<Phase> phases;
    private final Map<String, PhaseState> states = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String activeId;

    public ProcessWorkflowPipeline(List<Phase> phases) {
        this.phases = phases == null ? List.of() : List.copyOf(phases);
        for (Phase phase : this.phases) {
            if (states.put(phase.id(), pending(phase)) != null) {
                throw new IllegalArgumentException("Duplicate phase id: " + phase.id());
            }
        }
    }

    /** Recreates a finished/read-only pipeline from a durable snapshot. */
    public static ProcessWorkflowPipeline restored(List<PhaseState> snapshot) {
        List<PhaseState> safe = snapshot == null ? List.of() : List.copyOf(snapshot);
        ProcessWorkflowPipeline pipeline = new ProcessWorkflowPipeline(
                safe.stream().map(PhaseState::phase).toList());
        for (PhaseState state : safe) {
            // A non-zero marker makes elapsed time visible without treating the
            // restored phase as a live clock. RUNNING is frozen as CANCELLED: a
            // saved run cannot still be executing in this process.
            Status status = state.status() == Status.RUNNING
                    ? Status.CANCELLED : state.status();
            pipeline.states.put(state.phase().id(), new PhaseState(
                    state.phase(), status, state.summary(),
                    status == Status.PENDING ? 0 : 1,
                    Math.max(0, state.elapsedMillis())));
        }
        return pipeline;
    }

    public synchronized List<PhaseState> snapshot() {
        long now = System.nanoTime();
        return states.values().stream().map(state ->
                state.status() == Status.RUNNING && state.startedAtNanos() > 0
                        ? new PhaseState(state.phase(), state.status(), state.summary(),
                        state.startedAtNanos(), millis(state.startedAtNanos(), now))
                        : state).toList();
    }

    public void addListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public synchronized void reset() {
        states.replaceAll((id, state) -> pending(state.phase()));
        activeId = null;
        changed();
    }

    /** Starts a phase and completes the preceding active phase. */
    public synchronized void start(String id, String summary) {
        if (activeId != null && !activeId.equals(id)) {
            PhaseState previous = states.get(activeId);
            if (previous != null && previous.status() == Status.RUNNING) {
                states.put(activeId, new PhaseState(
                        previous.phase(), Status.COMPLETED, previous.summary(),
                        previous.startedAtNanos(), elapsed(previous)));
            }
        }
        PhaseState state = require(id);
        states.put(id, new PhaseState(state.phase(), Status.RUNNING, clean(summary),
                System.nanoTime(), 0));
        activeId = id;
        changed();
    }

    public synchronized void progress(String id, String summary) {
        PhaseState state = require(id);
        states.put(id, new PhaseState(state.phase(), state.status(), clean(summary),
                state.startedAtNanos(), state.elapsedMillis()));
        changed();
    }

    public synchronized void complete(String id, String summary) {
        update(id, Status.COMPLETED, summary);
        if (id.equals(activeId)) activeId = null;
        changed();
    }

    public synchronized void partial(String id, String summary) {
        update(id, Status.PARTIAL, summary);
        if (id.equals(activeId)) activeId = null;
        changed();
    }

    /**
     * Records the run's own outcome on the phase it stopped at.
     *
     * <p>Work does not always end inside a phase. A run can fail after the last one
     * completed — while saving, while assembling its result — and then nothing is
     * running to carry the verdict, so the graph would stay entirely green behind a
     * dialog reporting failure. A bad ending is therefore charged to the last phase that
     * ran, which is where the run got to.
     *
     * <p>A good ending is not propagated that way: every phase already carries its own
     * verdict by then, and overwriting the last one would erase a PARTIAL a phase had
     * earned. Only an ending that stopped the run — failed or cancelled — reaches back.
     */
    public synchronized void finish(ProcessStatus outcome, String summary) {
        Status status = switch (outcome) {
            case PENDING -> Status.PENDING;
            case RUNNING -> Status.RUNNING;
            case SUCCEEDED -> Status.COMPLETED;
            case PARTIAL -> Status.PARTIAL;
            case FAILED -> Status.FAILED;
            case CANCELLED -> Status.CANCELLED;
        };
        boolean stopped = status == Status.FAILED || status == Status.CANCELLED;
        String id = activeId != null ? activeId : stopped ? lastPhaseThatRan() : null;
        if (id != null) {
            update(id, status, summary);
        }
        activeId = null;
        changed();
    }

    /** The furthest phase the run reached, or null when it stopped before any began. */
    private String lastPhaseThatRan() {
        String last = null;
        for (Map.Entry<String, PhaseState> entry : states.entrySet()) {
            if (entry.getValue().status() != Status.PENDING) last = entry.getKey();
        }
        return last;
    }

    private void update(String id, Status status, String summary) {
        PhaseState state = require(id);
        // A phase that already stopped keeps the duration it took. Its verdict can still
        // arrive much later — a failed load is only known to be unrepaired once the run
        // assembles its quality summary — and recomputing from the start then would
        // charge that phase with everything the run did afterwards.
        long elapsed = state.status() == Status.RUNNING
                ? elapsed(state) : state.elapsedMillis();
        states.put(id, new PhaseState(state.phase(), status, clean(summary),
                state.startedAtNanos(), elapsed));
    }

    private PhaseState require(String id) {
        PhaseState state = states.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown phase: " + id);
        return state;
    }

    private void changed() {
        listeners.forEach(Runnable::run);
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private static PhaseState pending(Phase phase) {
        return new PhaseState(phase, Status.PENDING, "", 0, 0);
    }

    private static long elapsed(PhaseState state) {
        if (state.startedAtNanos() <= 0) return state.elapsedMillis();
        return millis(state.startedAtNanos(), System.nanoTime());
    }

    private static long millis(long start, long end) {
        return Math.max(0, (end - start) / 1_000_000L);
    }
}
