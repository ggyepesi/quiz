package datasource.persistence;

import datasource.EntityRef;
import datasource.LiteralValue;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.store.GraphAdjacencyCoverage;
import datasource.graph.store.GraphAdjacencyDemand;
import datasource.graph.store.GraphAdjacencyResult;
import datasource.graph.store.GraphEdge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A completed source batch remains a usable graph answer after process restart. */
class PersistentGraphStoreTest {
    @TempDir Path directory;

    @Test void aCompletedBatchRestoresItsEdgesAndEmptyAnswers() {
        GraphRelation relation = new GraphRelation("wikidata", "P1001");
        EntityRef first = EntityRef.wikidata("Q1");
        EntityRef empty = EntityRef.wikidata("Q2");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(first, empty),
                relation, GraphTraversalDirection.OUTGOING);

        try (PersistentGraphStore store = new PersistentGraphStore(directory)) {
            store.commitAdjacency(demand, List.of(new GraphEdge(first, relation,
                            EntityRef.wikidata("Q3"), "Q1$P1001")),
                    GraphAdjacencyCoverage.COMPLETE);
        }

        try (PersistentGraphStore restored = new PersistentGraphStore(directory)) {
            GraphAdjacencyResult answer = restored.adjacent(demand);
            assertEquals(List.of(EntityRef.wikidata("Q3")), answer.edges().stream()
                    .map(edge -> (EntityRef) edge.target()).toList());
            assertTrue(answer.missingNodes().isEmpty(),
                    "an answered QID with no statement must also remain covered");
            assertEquals(2, restored.statistics().reusedAnswers());
        }
    }

    @Test void literalEdgesKeepTheirExactStoredRepresentation() {
        GraphRelation relation = new GraphRelation("wikidata", "P576");
        EntityRef polity = EntityRef.wikidata("Q171150");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(polity), relation,
                GraphTraversalDirection.OUTGOING);
        LiteralValue dissolved = new LiteralValue("time", "+1946-02-01T00:00:00Z");

        try (PersistentGraphStore store = new PersistentGraphStore(directory)) {
            store.commitAdjacency(demand,
                    List.of(new GraphEdge(polity, relation, dissolved, "Q171150$P576")),
                    GraphAdjacencyCoverage.COMPLETE);
        }

        try (PersistentGraphStore restored = new PersistentGraphStore(directory)) {
            assertEquals(dissolved, restored.adjacent(demand).edges().getFirst().target());
        }
    }

    @Test void anInterruptedTrailingFrameDoesNotLoseEarlierBatches() throws Exception {
        GraphRelation relation = new GraphRelation("wikidata", "P31");
        EntityRef position = EntityRef.wikidata("Q4164871");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(position), relation,
                GraphTraversalDirection.OUTGOING);
        try (PersistentGraphStore store = new PersistentGraphStore(directory)) {
            store.commitAdjacency(demand, List.of(), GraphAdjacencyCoverage.COMPLETE);
        }
        Path journal;
        try (var files = Files.list(directory)) { journal = files.findFirst().orElseThrow(); }
        long completeLength = Files.size(journal);
        Files.write(journal, new byte[]{0x47, 0x52, 0x41}, StandardOpenOption.APPEND);

        try (PersistentGraphStore restored = new PersistentGraphStore(directory)) {
            assertTrue(restored.adjacent(demand).missingNodes().isEmpty());
        }
        assertEquals(completeLength, Files.size(journal));
    }

    @Test void aJournalFromAnOlderFormatIsRebuiltRatherThanRefused() throws Exception {
        // Failing the read instead would leave the journal throwing on every access
        // until someone deleted the file by hand — and the first change to the frame
        // format would do that to every journal already on disk. A cache of downloaded
        // facts has a cheaper answer: drop what cannot be read and fetch it again.
        GraphRelation relation = new GraphRelation("wikidata", "P17");
        EntityRef node = EntityRef.wikidata("Q7");
        GraphAdjacencyDemand demand = new GraphAdjacencyDemand(List.of(node),
                relation, GraphTraversalDirection.OUTGOING);
        try (PersistentGraphStore store = new PersistentGraphStore(directory)) {
            store.commitAdjacency(demand, List.of(new GraphEdge(node, relation,
                    EntityRef.wikidata("Q8"), "Q7$P17")), GraphAdjacencyCoverage.COMPLETE);
        }
        Path journal = onlyJournal();
        byte[] frame = Files.readAllBytes(journal);
        // Keep the frame intact and make its CONTENT unreadable, which is what a format
        // change looks like: the header still parses, the payload no longer applies.
        String payload = new String(frame, 12, frame.length - 12,
                java.nio.charset.StandardCharsets.UTF_8);
        byte[] replaced = payload.replace("\"protocolVersion\":1", "\"protocolVersion\":99")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer rewritten =
                java.nio.ByteBuffer.allocate(12 + replaced.length);
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(replaced, 0, replaced.length);
        rewritten.putInt(0x47524150).putInt(replaced.length).putInt((int) crc.getValue());
        rewritten.put(replaced);
        Files.write(journal, rewritten.array(), StandardOpenOption.TRUNCATE_EXISTING);

        try (PersistentGraphStore reopened = new PersistentGraphStore(directory)) {
            GraphAdjacencyResult answer = reopened.adjacent(demand);

            assertEquals(List.of(node), answer.missingNodes(),
                    "the unreadable answer must simply be unknown again");
            assertEquals(1, reopened.statistics().discardedJournals(),
                    "and the run must be able to say the cache was rebuilt");
        }
        assertEquals(0, Files.size(onlyJournal()),
                "an unusable journal is truncated, not left to fail every later read");
    }

    @Test void aCorruptedTailKeepsTheCompletedFramesBeforeIt() throws Exception {
        GraphRelation relation = new GraphRelation("wikidata", "P31");
        EntityRef kept = EntityRef.wikidata("Q10");
        GraphAdjacencyDemand first = new GraphAdjacencyDemand(List.of(kept),
                relation, GraphTraversalDirection.OUTGOING);
        try (PersistentGraphStore store = new PersistentGraphStore(directory)) {
            store.commitAdjacency(first, List.of(new GraphEdge(kept, relation,
                    EntityRef.wikidata("Q11"), "Q10$P31")), GraphAdjacencyCoverage.COMPLETE);
        }
        Path journal = onlyJournal();
        long good = Files.size(journal);
        byte[] garbage = new byte[64];
        java.util.Arrays.fill(garbage, (byte) 0x5A);
        Files.write(journal, garbage, StandardOpenOption.APPEND);

        try (PersistentGraphStore reopened = new PersistentGraphStore(directory)) {
            assertTrue(reopened.adjacent(first).missingNodes().isEmpty(),
                    "the completed frame before the damage is still an answer");
            assertEquals(1, reopened.statistics().discardedJournals());
        }
        assertEquals(good, Files.size(onlyJournal()),
                "the journal is truncated back to its last usable frame");
    }

    private Path onlyJournal() throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".adjacency.journal"))
                    .findFirst().orElseThrow();
        }
    }
}
