package datasource.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.EntityRef;
import datasource.GraphValue;
import datasource.LiteralValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import datasource.graph.store.InMemoryGraphStore;
import datasource.graph.store.LocalGraphStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Append-only durable adjacency cache. One journal is loaded lazily per provider,
 * relation and direction, so experimenting with one graph does not read unrelated
 * downloaded relations into memory.
 *
 * <p>Each frame is one acquired batch: its edges and per-node coverage are persisted
 * together before they are published in memory. An interrupted final append is removed
 * on replay; a completed frame is therefore sufficient to skip that batch after restart.
 */
public final class PersistentGraphStore implements LocalGraphStore {
    private static final int FRAME_MAGIC = 0x47524150; // GRAP
    private static final int FRAME_HEADER_BYTES = 12;
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    private record StoreKey(
            String providerId, String relationId, GraphTraversalDirection direction) { }
    private record CoverageKey(
            EntityRef node, GraphRelation relation, GraphTraversalDirection direction) { }
    private record EntityData(String namespace, String id) { }
    private record ValueData(
            String kind, String namespace, String id, String datatype, String lexicalForm) { }
    private record EdgeData(EntityData source, ValueData target, String provenanceId) { }
    private record BatchRecord(
            int protocolVersion,
            String providerId,
            String relationId,
            GraphTraversalDirection direction,
            List<EntityData> nodes,
            GraphAdjacencyCoverage coverage,
            List<EdgeData> edges) { }

    /** @param discardedJournals journals whose unusable tail was dropped on replay, so
     *  a silently rebuilt cache is still something the run can report. */
    public record Statistics(
            int reusedAnswers, int savedAnswers, int discardedJournals) { }

    private final Path directory;
    private final ObjectMapper mapper;
    private final InMemoryGraphStore memory = new InMemoryGraphStore();
    private final Set<StoreKey> loaded = new LinkedHashSet<>();
    // One set, not one copy of the whole graph per statistic. It prevents the
    // evaluate/acquire/evaluate sequence from counting the same answer twice.
    private final Set<CoverageKey> counted = new LinkedHashSet<>();
    private int reusedAnswers;
    private int savedAnswers;
    private int discardedFrames;

    public PersistentGraphStore(Path directory) {
        this(directory, new ObjectMapper());
    }

    PersistentGraphStore(Path directory, ObjectMapper mapper) {
        if (directory == null) throw new IllegalArgumentException(
                "A graph cache directory is required");
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override public synchronized void addEdges(Collection<GraphEdge> edges) {
        memory.addEdges(edges);
    }

    @Override public synchronized void markCoverage(
            GraphAdjacencyDemand demand, GraphAdjacencyCoverage coverage) {
        memory.markCoverage(demand, coverage);
    }

    @Override public synchronized void commitAdjacency(
            GraphAdjacencyDemand demand,
            Collection<GraphEdge> edges,
            GraphAdjacencyCoverage coverage) {
        if (demand == null || coverage == null) return;
        try {
            ensureLoaded(key(demand));
        } catch (IOException error) {
            throw new GraphStoreReadException(
                    "Could not read downloaded graph facts", error);
        }
        try {
            append(file(key(demand)), record(demand, edges, coverage));
        } catch (IOException error) {
            throw new GraphStoreWriteException(
                    "Could not save downloaded graph facts", error);
        }
        memory.commitAdjacency(demand, edges, coverage);
        for (EntityRef node : demand.nodes()) {
            CoverageKey answer = coverageKey(node, demand);
            if (counted.add(answer)) savedAnswers++;
        }
    }

    @Override public synchronized GraphAdjacencyCoverage adjacencyKnowledge(
            EntityRef node, GraphAdjacencyDemand demand) {
        requireLoaded(demand);
        noteReuse(node, demand);
        return memory.adjacencyKnowledge(node, demand);
    }

    @Override public synchronized GraphAdjacencyResult adjacent(GraphAdjacencyDemand demand) {
        requireLoaded(demand);
        if (demand != null) demand.nodes().forEach(node -> noteReuse(node, demand));
        return memory.adjacent(demand);
    }

    public synchronized Statistics statistics() {
        return new Statistics(reusedAnswers, savedAnswers, discardedFrames);
    }

    public Path directory() { return directory; }

    private void noteReuse(EntityRef node, GraphAdjacencyDemand demand) {
        if (node == null || demand == null) return;
        CoverageKey key = coverageKey(node, demand);
        if (memory.adjacencyKnowledge(node, demand) != GraphAdjacencyCoverage.UNKNOWN
                && counted.add(key)) {
            reusedAnswers++;
        }
    }

    private void requireLoaded(GraphAdjacencyDemand demand) {
        if (demand == null) return;
        try {
            ensureLoaded(key(demand));
        } catch (IOException error) {
            throw new GraphStoreReadException("Could not read downloaded graph facts", error);
        }
    }

    private void ensureLoaded(StoreKey key) throws IOException {
        if (loaded.contains(key)) return;
        Path journal = file(key);
        if (Files.exists(journal)) replay(journal, key);
        loaded.add(key);
    }

    private void replay(Path journal, StoreKey expected) throws IOException {
        long validBytes = 0;
        long size;
        boolean unusable = false;
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.READ)) {
            size = channel.size();
            long position = 0;
            while (size - position >= FRAME_HEADER_BYTES) {
                throwIfInterrupted();
                ByteBuffer header = ByteBuffer.allocate(FRAME_HEADER_BYTES);
                if (!readFully(channel, header, position)) break;
                header.flip();
                int magic = header.getInt();
                int payloadLength = header.getInt();
                int expectedCrc = header.getInt();
                // A frame this store cannot use ENDS the replay instead of failing the
                // read. The journal holds downloaded facts and nothing else, so anything
                // past the last usable frame is dropped and fetched again — which makes
                // a half-written frame, a corrupted one and a frame from an older format
                // one case with one answer. Failing instead would leave a journal that
                // throws on every read until someone deleted the file by hand, and the
                // first change to PROTOCOL_VERSION would do that to every existing one.
                if (magic != FRAME_MAGIC
                        || payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES) {
                    unusable = true;
                    break;
                }
                long payloadPosition = position + FRAME_HEADER_BYTES;
                if (size - payloadPosition < payloadLength) break;
                ByteBuffer payload = ByteBuffer.allocate(payloadLength);
                if (!readFully(channel, payload, payloadPosition)) break;
                byte[] bytes = payload.array();
                CRC32C crc = new CRC32C();
                crc.update(bytes, 0, bytes.length);
                if ((int) crc.getValue() != expectedCrc || !apply(bytes, expected)) {
                    unusable = true;
                    break;
                }
                position = payloadPosition + payloadLength;
                validBytes = position;
            }
        }
        if (validBytes < size) {
            if (unusable) discardedFrames++;
            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE)) {
                channel.truncate(validBytes);
                channel.force(false);
            }
        }
    }

    private static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException(
                    "Graph-cache load cancelled");
        }
    }

    /**
     * Applies one frame to the in-memory mirror.
     *
     * @return false when the frame is not something this store can apply — unreadable
     * JSON, a protocol version it does not know, or a record belonging to another
     * relation — so the caller stops replaying and truncates to what was usable.
     */
    private boolean apply(byte[] payload, StoreKey expected) {
        BatchRecord record;
        try {
            record = mapper.readValue(payload, BatchRecord.class);
        } catch (IOException unreadable) {
            return false;
        }
        if (record.protocolVersion() != PROTOCOL_VERSION) return false;
        StoreKey actual = new StoreKey(record.providerId(), record.relationId(),
                record.direction());
        if (!expected.equals(actual)) return false;
        GraphRelation relation = new GraphRelation(actual.providerId(), actual.relationId());
        List<EntityRef> nodes = record.nodes().stream().map(PersistentGraphStore::entity).toList();
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(
                nodes, relation, actual.direction());
        List<GraphEdge> edges = record.edges().stream()
                .map(value -> edge(value, relation)).toList();
        memory.commitAdjacency(demand, edges, record.coverage());
        return true;
    }

    private BatchRecord record(
            GraphAdjacencyDemand demand,
            Collection<GraphEdge> edges,
            GraphAdjacencyCoverage coverage) {
        GraphRelation relation = demand.relation();
        List<EdgeData> encoded = edges == null ? List.of() : edges.stream()
                .map(PersistentGraphStore::data).toList();
        return new BatchRecord(PROTOCOL_VERSION, relation.providerId(),
                relation.relationId(), demand.direction(),
                demand.nodes().stream().map(PersistentGraphStore::data).toList(),
                coverage, encoded);
    }

    private void append(Path journal, BatchRecord record) throws IOException {
        Files.createDirectories(directory);
        byte[] payload = mapper.writeValueAsBytes(record);
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IOException(
                "Graph-cache record is too large: " + payload.length);
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
        frame.putInt(FRAME_MAGIC).putInt(payload.length).putInt((int) crc.getValue());
        frame.put(payload).flip();
        try (FileChannel channel = FileChannel.open(journal,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            writeFully(channel, frame);
            channel.force(false);
        }
    }

    private Path file(StoreKey key) {
        String identity = key.providerId() + '\n' + key.relationId() + '\n'
                + key.direction().name();
        return directory.resolve(sha256(identity) + ".adjacency.journal");
    }

    private static StoreKey key(GraphAdjacencyDemand demand) {
        return new StoreKey(demand.relation().providerId(),
                demand.relation().relationId(), demand.direction());
    }

    private static CoverageKey coverageKey(EntityRef node, GraphAdjacencyDemand demand) {
        return new CoverageKey(node, demand.relation(), demand.direction());
    }

    private static EntityData data(EntityRef entity) {
        return new EntityData(entity.namespace(), entity.id());
    }

    private static EntityRef entity(EntityData value) {
        return new EntityRef(value.namespace(), value.id());
    }

    private static EdgeData data(GraphEdge edge) {
        GraphValue target = edge.target();
        ValueData encoded = target instanceof EntityRef entity
                ? new ValueData("entity", entity.namespace(), entity.id(), "", "")
                : new ValueData("literal", "", "",
                        ((LiteralValue) target).datatype(),
                        ((LiteralValue) target).lexicalForm());
        return new EdgeData(data(edge.source()), encoded, edge.provenanceId());
    }

    private static GraphEdge edge(EdgeData value, GraphRelation relation) {
        ValueData target = value.target();
        GraphValue decoded = "entity".equals(target.kind())
                ? new EntityRef(target.namespace(), target.id())
                : new LiteralValue(target.datatype(), target.lexicalForm());
        return new GraphEdge(entity(value.source()), relation, decoded,
                value.provenanceId());
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static boolean readFully(
            FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) return false;
            if (read == 0) continue;
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public static final class GraphStoreReadException extends RuntimeException {
        GraphStoreReadException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GraphStoreWriteException extends RuntimeException {
        GraphStoreWriteException(String message, Throwable cause) { super(message, cause); }
    }
}
