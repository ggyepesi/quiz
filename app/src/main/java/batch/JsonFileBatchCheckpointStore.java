package batch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Framed append-only checkpoint journal with JSON event payloads.
 *
 * <p>START is written through an atomic replacement. SPLIT, COMPLETE and FINISH append one
 * length-and-checksum framed record and force it to disk. Recovery removes an incomplete
 * trailing frame (a process stopped during append), so subsequent events remain readable,
 * but rejects corruption in a complete frame. Normal checkpoint output is therefore linear
 * in the number of work units.
 */
public final class JsonFileBatchCheckpointStore implements BatchCheckpointStore {
    private static final int FRAME_MAGIC = 0x42434A31; // "BCJ1"
    private static final int FRAME_HEADER_BYTES = Integer.BYTES * 3;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final int PROTOCOL_VERSION = 1;

    private final Path directory;
    private final ObjectMapper mapper;

    public JsonFileBatchCheckpointStore(Path directory) {
        this(directory, new ObjectMapper());
    }

    public JsonFileBatchCheckpointStore(Path directory, ObjectMapper mapper) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public synchronized Optional<BatchCheckpoint> recover(String runKey) throws IOException {
        requireText(runKey, "runKey");
        migrateLegacyCheckpoint(runKey);
        Path journal = journalFile(runKey);
        if (!Files.exists(journal)) {
            return Optional.empty();
        }

        ReplayState state = replay(journal, runKey);
        if (state.finished) {
            return Optional.empty();
        }
        return Optional.of(state.checkpoint(runKey));
    }

    @Override
    public synchronized void start(
            String runKey,
            List<BatchCheckpoint.PendingWork> roots) throws IOException {
        start(runKey, roots, Set.of());
    }

    @Override
    public synchronized void split(
            String runKey,
            String parentKey,
            List<BatchCheckpoint.PendingWork> children) throws IOException {
        requireText(runKey, "runKey");
        requireText(parentKey, "parentKey");
        List<BatchCheckpoint.PendingWork> checked = checkedWork(children, "children", false);
        append(journalFile(runKey), JournalRecord.split(runKey, parentKey, checked));
    }

    @Override
    public synchronized void complete(String runKey, String workKey) throws IOException {
        requireText(runKey, "runKey");
        requireText(workKey, "workKey");
        append(journalFile(runKey), JournalRecord.complete(runKey, workKey));
    }

    @Override
    public synchronized void finish(String runKey) throws IOException {
        requireText(runKey, "runKey");
        append(journalFile(runKey), JournalRecord.finish(runKey));
    }

    private void start(
            String runKey,
            List<BatchCheckpoint.PendingWork> roots,
            Set<String> completedKeys) throws IOException {
        requireText(runKey, "runKey");
        List<BatchCheckpoint.PendingWork> checkedRoots = checkedWork(roots, "roots", true);
        Set<String> checkedCompleted = checkedKeys(completedKeys);
        for (BatchCheckpoint.PendingWork root : checkedRoots) {
            if (checkedCompleted.contains(root.descriptor().key())) {
                throw new IllegalArgumentException(
                        "A root is already completed: " + root.descriptor().key());
            }
        }

        Files.createDirectories(directory);
        Path target = journalFile(runKey);
        Path temporary = Files.createTempFile(
                directory, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            writeFresh(temporary, JournalRecord.start(
                    runKey, checkedRoots, checkedCompleted));
            moveReplacing(temporary, target);
            moved = true;
            Files.deleteIfExists(legacyFile(runKey));
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void append(Path journal, JournalRecord record) throws IOException {
        if (!Files.exists(journal)) {
            throw new IOException("Checkpoint journal has not been started: " + journal);
        }
        ByteBuffer frame = frame(record);
        try (FileChannel channel = FileChannel.open(
                journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            writeFully(channel, frame);
            channel.force(false);
        }
    }

    private void writeFresh(Path file, JournalRecord record) throws IOException {
        ByteBuffer frame = frame(record);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, frame);
            channel.force(false);
        }
    }

    private ByteBuffer frame(JournalRecord record) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(record);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Checkpoint record is too large: " + payload.length);
        }
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
        frame.putInt(FRAME_MAGIC);
        frame.putInt(payload.length);
        frame.putInt((int) crc.getValue());
        frame.put(payload);
        frame.flip();
        return frame;
    }

    private ReplayState replay(Path journal, String expectedRunKey) throws IOException {
        ReplayState state = new ReplayState();
        long validBytes = 0;
        long size;
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.READ)) {
            size = channel.size();
            long position = 0;
            while (size - position >= FRAME_HEADER_BYTES) {
                ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES);
                if (!readFully(channel, header, position)) {
                    break;
                }
                header.flip();
                int magic = header.getInt();
                int payloadLength = header.getInt();
                int expectedCrc = header.getInt();
                if (magic != FRAME_MAGIC) {
                    throw new IOException("Invalid checkpoint frame at byte " + position);
                }
                if (payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES) {
                    throw new IOException("Invalid checkpoint payload length "
                            + payloadLength + " at byte " + position);
                }

                long payloadPosition = position + FRAME_HEADER_BYTES;
                if (size - payloadPosition < payloadLength) {
                    break; // incomplete trailing append: the preceding records are durable
                }
                ByteBuffer payload = ByteBuffer.allocate(payloadLength);
                if (!readFully(channel, payload, payloadPosition)) {
                    break;
                }
                byte[] bytes = payload.array();
                CRC32C crc = new CRC32C();
                crc.update(bytes, 0, bytes.length);
                if ((int) crc.getValue() != expectedCrc) {
                    throw new IOException("Checkpoint checksum mismatch at byte " + position);
                }

                JournalRecord record = mapper.readValue(bytes, JournalRecord.class);
                if (record.protocolVersion() != PROTOCOL_VERSION) {
                    throw new IOException("Unsupported checkpoint journal version: "
                            + record.protocolVersion());
                }
                if (!expectedRunKey.equals(record.runKey())) {
                    throw new IOException("Checkpoint run key does not match requested run: "
                            + expectedRunKey);
                }
                state.apply(record);
                position = payloadPosition + payloadLength;
                validBytes = position;
            }
        }
        if (!state.started) {
            throw new IOException("Checkpoint journal contains no complete START record");
        }
        if (validBytes < size) {
            try (FileChannel channel = FileChannel.open(
                    journal, StandardOpenOption.WRITE)) {
                channel.truncate(validBytes);
                channel.force(false);
            }
        }
        return state;
    }

    private void migrateLegacyCheckpoint(String runKey) throws IOException {
        Path journal = journalFile(runKey);
        Path legacy = legacyFile(runKey);
        if (Files.exists(journal) || !Files.exists(legacy)) {
            return;
        }
        BatchCheckpoint checkpoint = mapper.readValue(legacy.toFile(), BatchCheckpoint.class);
        if (!runKey.equals(checkpoint.runKey())) {
            throw new IOException("Legacy checkpoint run key does not match requested run: "
                    + runKey);
        }
        start(runKey, checkpoint.pending(), checkpoint.completedKeys());
    }

    private Path journalFile(String runKey) {
        return directory.resolve(sha256(runKey) + ".batch.journal");
    }

    private Path legacyFile(String runKey) {
        return directory.resolve(sha256(runKey) + ".batch.json");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static boolean readFully(
            FileChannel channel,
            ByteBuffer buffer,
            long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                return false;
            }
            if (read == 0) {
                continue;
            }
        }
        return true;
    }

    private static List<BatchCheckpoint.PendingWork> checkedWork(
            List<BatchCheckpoint.PendingWork> work,
            String name,
            boolean emptyAllowed) {
        if (work == null || (!emptyAllowed && work.isEmpty())) {
            throw new IllegalArgumentException(name + " must "
                    + (emptyAllowed ? "not be null" : "not be empty"));
        }
        List<BatchCheckpoint.PendingWork> copy = new ArrayList<>(work);
        Set<String> keys = new LinkedHashSet<>();
        for (BatchCheckpoint.PendingWork pending : copy) {
            if (pending == null) {
                throw new IllegalArgumentException(name + " contains null");
            }
            String key = pending.descriptor().key();
            if (!keys.add(key)) {
                throw new IllegalArgumentException(name + " contains duplicate key: " + key);
            }
        }
        return List.copyOf(copy);
    }

    private static Set<String> checkedKeys(Set<String> keys) {
        if (keys == null) {
            return Set.of();
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String key : keys) {
            copy.add(requireText(key, "completed key"));
        }
        return Set.copyOf(copy);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private enum EventType { START, SPLIT, COMPLETE, FINISH }

    private record JournalRecord(
            int protocolVersion,
            EventType type,
            String runKey,
            List<BatchCheckpoint.PendingWork> work,
            String parentKey,
            String workKey,
            Set<String> completedKeys) {

        static JournalRecord start(
                String runKey,
                List<BatchCheckpoint.PendingWork> roots,
                Set<String> completedKeys) {
            return new JournalRecord(
                    PROTOCOL_VERSION, EventType.START, runKey,
                    roots, null, null, completedKeys);
        }

        static JournalRecord split(
                String runKey,
                String parentKey,
                List<BatchCheckpoint.PendingWork> children) {
            return new JournalRecord(
                    PROTOCOL_VERSION, EventType.SPLIT, runKey,
                    children, parentKey, null, Set.of());
        }

        static JournalRecord complete(String runKey, String workKey) {
            return new JournalRecord(
                    PROTOCOL_VERSION, EventType.COMPLETE, runKey,
                    List.of(), null, workKey, Set.of());
        }

        static JournalRecord finish(String runKey) {
            return new JournalRecord(
                    PROTOCOL_VERSION, EventType.FINISH, runKey,
                    List.of(), null, null, Set.of());
        }
    }

    private static final class ReplayState {
        private final List<String> roots = new ArrayList<>();
        private final Map<String, BatchCheckpoint.PendingWork> nodes = new LinkedHashMap<>();
        private final Map<String, List<String>> children = new HashMap<>();
        private final Set<String> openLeaves = new LinkedHashSet<>();
        private final Set<String> completed = new LinkedHashSet<>();
        private boolean started;
        private boolean finished;

        void apply(JournalRecord record) throws IOException {
            if (finished) {
                throw new IOException("Checkpoint record follows FINISH");
            }
            switch (record.type()) {
                case START -> applyStart(record);
                case SPLIT -> applySplit(record);
                case COMPLETE -> applyComplete(record);
                case FINISH -> applyFinish();
            }
        }

        private void applyStart(JournalRecord record) throws IOException {
            if (started) {
                throw new IOException("Checkpoint journal contains more than one START");
            }
            started = true;
            if (record.completedKeys() != null) {
                completed.addAll(record.completedKeys());
            }
            for (BatchCheckpoint.PendingWork root : nonNullWork(record)) {
                addNode(root, "START");
                if (completed.contains(root.descriptor().key())) {
                    throw new IOException("START root is already complete: "
                            + root.descriptor().key());
                }
                roots.add(root.descriptor().key());
                openLeaves.add(root.descriptor().key());
            }
        }

        private void applySplit(JournalRecord record) throws IOException {
            requireStarted();
            String parent = record.parentKey();
            if (parent == null || !openLeaves.remove(parent)) {
                throw new IOException("SPLIT parent is not a pending leaf: " + parent);
            }
            List<String> childKeys = new ArrayList<>();
            for (BatchCheckpoint.PendingWork child : nonNullWork(record)) {
                addNode(child, "SPLIT");
                String childKey = child.descriptor().key();
                childKeys.add(childKey);
                openLeaves.add(childKey);
            }
            if (childKeys.isEmpty()) {
                throw new IOException("SPLIT has no children: " + parent);
            }
            children.put(parent, List.copyOf(childKeys));
        }

        private void applyComplete(JournalRecord record) throws IOException {
            requireStarted();
            String key = record.workKey();
            if (key == null || !openLeaves.remove(key)) {
                throw new IOException("COMPLETE target is not a pending leaf: " + key);
            }
            completed.add(key);
        }

        private void applyFinish() throws IOException {
            requireStarted();
            if (!openLeaves.isEmpty()) {
                throw new IOException("FINISH encountered with " + openLeaves.size()
                        + " pending unit(s)");
            }
            finished = true;
        }

        private void addNode(
                BatchCheckpoint.PendingWork work,
                String event) throws IOException {
            if (work == null || work.descriptor() == null) {
                throw new IOException(event + " contains null work");
            }
            String key = work.descriptor().key();
            if (nodes.putIfAbsent(key, work) != null || completed.contains(key)) {
                throw new IOException(event + " reuses work key: " + key);
            }
        }

        private void requireStarted() throws IOException {
            if (!started) {
                throw new IOException("Checkpoint event precedes START");
            }
        }

        private static List<BatchCheckpoint.PendingWork> nonNullWork(
                JournalRecord record) throws IOException {
            if (record.work() == null) {
                throw new IOException(record.type() + " contains no work list");
            }
            return record.work();
        }

        BatchCheckpoint checkpoint(String runKey) throws IOException {
            List<BatchCheckpoint.PendingWork> pending = new ArrayList<>();
            for (String root : roots) {
                collectPending(root, pending);
            }
            if (pending.size() != openLeaves.size()) {
                throw new IOException("Checkpoint leaf topology is inconsistent");
            }
            Set<String> knownKeys = new LinkedHashSet<>(nodes.keySet());
            knownKeys.addAll(completed);
            return new BatchCheckpoint(runKey, pending, completed, knownKeys);
        }

        private void collectPending(
                String key,
                List<BatchCheckpoint.PendingWork> pending) throws IOException {
            if (completed.contains(key)) {
                return;
            }
            List<String> childKeys = children.get(key);
            if (childKeys != null) {
                for (String child : childKeys) {
                    collectPending(child, pending);
                }
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
