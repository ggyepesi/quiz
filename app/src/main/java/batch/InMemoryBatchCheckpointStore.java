package batch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Append-only checkpoint journal kept entirely in memory.
 *
 * <p>This is useful for demonstrations and tests which should exercise the same
 * START/SPLIT/COMPLETE/FINISH protocol as durable execution without creating files.
 * It can resume a run while this store instance remains alive, but deliberately provides
 * no protection across a JVM restart.
 */
public final class InMemoryBatchCheckpointStore implements BatchCheckpointStore {

    public enum EventType { START, SPLIT, COMPLETE, FINISH }

    /** One event in the order in which the executor appended it. */
    public record Event(
            EventType type,
            String runKey,
            String workKey,
            List<BatchCheckpoint.PendingWork> work) {
        public Event {
            if (type == null) throw new IllegalArgumentException("type must not be null");
            if (runKey == null || runKey.isBlank()) {
                throw new IllegalArgumentException("runKey must not be blank");
            }
            work = work == null ? List.of() : List.copyOf(work);
        }
    }

    private final Map<String, List<Event>> journals = new LinkedHashMap<>();

    @Override
    public synchronized Optional<BatchCheckpoint> recover(String runKey) throws IOException {
        requireText(runKey, "runKey");
        List<Event> events = journals.get(runKey);
        if (events == null) return Optional.empty();
        Replay replay = new Replay(runKey);
        for (Event event : events) replay.apply(event);
        return replay.finished ? Optional.empty() : Optional.of(replay.checkpoint());
    }

    @Override
    public synchronized void start(
            String runKey,
            List<BatchCheckpoint.PendingWork> roots) throws IOException {
        requireText(runKey, "runKey");
        List<BatchCheckpoint.PendingWork> checked = checkedWork(roots, true);
        journals.put(runKey, new ArrayList<>(List.of(
                new Event(EventType.START, runKey, null, checked))));
    }

    @Override
    public synchronized void split(
            String runKey,
            String parentKey,
            List<BatchCheckpoint.PendingWork> children) throws IOException {
        requireText(parentKey, "parentKey");
        append(runKey, new Event(
                EventType.SPLIT, runKey, parentKey, checkedWork(children, false)));
    }

    @Override
    public synchronized void complete(String runKey, String workKey) throws IOException {
        requireText(workKey, "workKey");
        append(runKey, new Event(EventType.COMPLETE, runKey, workKey, List.of()));
    }

    @Override
    public synchronized void finish(String runKey) throws IOException {
        append(runKey, new Event(EventType.FINISH, runKey, null, List.of()));
    }

    /** All events across runs, in run creation order and then append order. */
    public synchronized List<Event> events() {
        return journals.values().stream().flatMap(List::stream).toList();
    }

    /** Events for one run, or an empty list when that run has not started. */
    public synchronized List<Event> events(String runKey) {
        List<Event> events = journals.get(runKey);
        return events == null ? List.of() : List.copyOf(events);
    }

    private void append(String runKey, Event event) throws IOException {
        requireText(runKey, "runKey");
        List<Event> events = journals.get(runKey);
        if (events == null) {
            throw new IOException("Checkpoint journal has not been started: " + runKey);
        }
        // Validate the transition before retaining it, so a bad event cannot poison
        // a later recovery attempt.
        Replay replay = new Replay(runKey);
        for (Event existing : events) replay.apply(existing);
        replay.apply(event);
        events.add(event);
    }

    private static List<BatchCheckpoint.PendingWork> checkedWork(
            List<BatchCheckpoint.PendingWork> work,
            boolean emptyAllowed) {
        if (work == null || (!emptyAllowed && work.isEmpty())) {
            throw new IllegalArgumentException(emptyAllowed
                    ? "work must not be null" : "work must not be empty");
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (BatchCheckpoint.PendingWork pending : work) {
            if (pending == null || pending.descriptor() == null) {
                throw new IllegalArgumentException("work contains null");
            }
            if (!keys.add(pending.descriptor().key())) {
                throw new IllegalArgumentException(
                        "work contains duplicate key: " + pending.descriptor().key());
            }
        }
        return List.copyOf(work);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class Replay {
        private final String runKey;
        private final List<String> roots = new ArrayList<>();
        private final Map<String, BatchCheckpoint.PendingWork> nodes = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new LinkedHashMap<>();
        private final Set<String> openLeaves = new LinkedHashSet<>();
        private final Set<String> completed = new LinkedHashSet<>();
        private boolean started;
        private boolean finished;

        private Replay(String runKey) {
            this.runKey = runKey;
        }

        void apply(Event event) throws IOException {
            if (!runKey.equals(event.runKey())) {
                throw new IOException("Checkpoint event belongs to another run");
            }
            if (finished) throw new IOException("Checkpoint event follows FINISH");
            switch (event.type()) {
                case START -> start(event);
                case SPLIT -> split(event);
                case COMPLETE -> complete(event);
                case FINISH -> finish();
            }
        }

        private void start(Event event) throws IOException {
            if (started) throw new IOException("Checkpoint contains more than one START");
            started = true;
            for (BatchCheckpoint.PendingWork root : event.work()) {
                add(root, "START");
                String key = root.descriptor().key();
                roots.add(key);
                openLeaves.add(key);
            }
        }

        private void split(Event event) throws IOException {
            requireStarted();
            String parent = event.workKey();
            if (!openLeaves.remove(parent)) {
                throw new IOException("SPLIT parent is not a pending leaf: " + parent);
            }
            if (event.work().isEmpty()) throw new IOException("SPLIT has no children");
            List<String> childKeys = new ArrayList<>();
            for (BatchCheckpoint.PendingWork child : event.work()) {
                add(child, "SPLIT");
                childKeys.add(child.descriptor().key());
                openLeaves.add(child.descriptor().key());
            }
            children.put(parent, List.copyOf(childKeys));
        }

        private void complete(Event event) throws IOException {
            requireStarted();
            if (!openLeaves.remove(event.workKey())) {
                throw new IOException(
                        "COMPLETE target is not a pending leaf: " + event.workKey());
            }
            completed.add(event.workKey());
        }

        private void finish() throws IOException {
            requireStarted();
            if (!openLeaves.isEmpty()) {
                throw new IOException("FINISH encountered with pending work");
            }
            finished = true;
        }

        private void add(BatchCheckpoint.PendingWork work, String event) throws IOException {
            String key = work.descriptor().key();
            if (nodes.putIfAbsent(key, work) != null || completed.contains(key)) {
                throw new IOException(event + " reuses work key: " + key);
            }
        }

        private void requireStarted() throws IOException {
            if (!started) throw new IOException("Checkpoint event precedes START");
        }

        BatchCheckpoint checkpoint() throws IOException {
            if (!started) throw new IOException("Checkpoint contains no START");
            List<BatchCheckpoint.PendingWork> pending = new ArrayList<>();
            for (String root : roots) collect(root, pending);
            if (pending.size() != openLeaves.size()) {
                throw new IOException("Checkpoint leaf topology is inconsistent");
            }
            return new BatchCheckpoint(runKey, pending, completed, nodes.keySet());
        }

        private void collect(
                String key,
                List<BatchCheckpoint.PendingWork> pending) throws IOException {
            if (completed.contains(key)) return;
            List<String> descendants = children.get(key);
            if (descendants != null) {
                for (String child : descendants) collect(child, pending);
                return;
            }
            BatchCheckpoint.PendingWork work = nodes.get(key);
            if (work == null || !openLeaves.contains(key)) {
                throw new IOException("Checkpoint has no state for leaf: " + key);
            }
            pending.add(work);
        }
    }
}
