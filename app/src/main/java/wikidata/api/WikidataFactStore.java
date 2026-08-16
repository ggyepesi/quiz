package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run-scoped cache of raw wbgetentities entity documents. Keeping the raw document is
 * important: one physical {@code props=labels|claims} response can later be parsed as
 * qualifier statements, entity-valued fields, kind evidence, disambiguation evidence,
 * and labels without downloading it again.
 */
public final class WikidataFactStore {
    /** A conservative cap for the serialized payload retained by one run. The tree
     * itself has overhead, but unlike an entry-count limit this remains meaningful
     * when one Wikidata entity has a much larger claims body than another. */
    public static final long DEFAULT_MAX_ESTIMATED_BYTES = 64L * 1024L * 1024L;

    private record Document(JsonNode json, boolean claims, long estimatedBytes) { }

    private final Map<String, Document> documents =
            new LinkedHashMap<>(256, 0.75f, true);
    private final long maxEstimatedBytes;
    private long estimatedBytes;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong fetched = new AtomicLong();

    public WikidataFactStore() { this(DEFAULT_MAX_ESTIMATED_BYTES); }

    public WikidataFactStore(long maxEstimatedBytes) {
        this.maxEstimatedBytes = Math.max(1, maxEstimatedBytes);
    }

    /** Pure lookup: metrics are changed only when a cached response is actually used. */
    public synchronized List<String> missing(
            Collection<String> qids, boolean requireClaims) {
        List<String> out = new ArrayList<>();
        if (qids == null) return out;
        for (String qid : qids) {
            Document document = documents.get(qid);
            if (document == null || requireClaims && !document.claims()) out.add(qid);
        }
        return out;
    }

    public synchronized void accept(JsonNode response, boolean claims) {
        if (response == null) return;
        response.path("entities").fields().forEachRemaining(entry -> {
            JsonNode copy = entry.getValue().deepCopy();
            fetched.incrementAndGet();
            long weight = estimate(copy);
            Document previous = documents.get(entry.getKey());
            if (previous != null && previous.claims() && !claims) return;
            if (previous != null) estimatedBytes -= previous.estimatedBytes();
            if (weight > maxEstimatedBytes) {
                documents.remove(entry.getKey());
                return; // usable by the caller now, but too large to retain safely
            }
            documents.put(entry.getKey(), new Document(copy, claims, weight));
            estimatedBytes += weight;
        });
        evictOldest();
    }

    public synchronized JsonNode response(
            Collection<String> qids, boolean requireClaims, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode entities = root.putObject("entities");
        if (qids == null) return root;
        for (String qid : qids) {
            Document document = documents.get(qid);
            if (document == null || requireClaims && !document.claims()) return null;
            entities.set(qid, document.json().deepCopy());
        }
        return root;
    }

    public long cacheHits() { return hits.get(); }
    public void recordHits(long count) { if (count > 0) hits.addAndGet(count); }
    public long fetchedDocuments() { return fetched.get(); }
    public synchronized int size() { return documents.size(); }
    public synchronized long estimatedBytes() { return estimatedBytes; }

    private void evictOldest() {
        Iterator<Map.Entry<String, Document>> entries = documents.entrySet().iterator();
        while (estimatedBytes > maxEstimatedBytes && entries.hasNext()) {
            Map.Entry<String, Document> eldest = entries.next();
            estimatedBytes -= eldest.getValue().estimatedBytes();
            entries.remove();
        }
    }

    private static long estimate(JsonNode json) {
        // Weigh the tree by walking it: two bytes per character of content plus a small
        // per-node allowance. Serializing the document to measure it would allocate a
        // full transient copy of every claims body — on a large domain, as much garbage
        // again as the cache itself holds — to learn something the walk already knows.
        return Math.max(256L, 256L + weigh(json));
    }

    private static long weigh(JsonNode node) {
        if (node == null || node.isNull()) return NODE_OVERHEAD;
        if (node.isTextual()) {
            return NODE_OVERHEAD + (long) node.textValue().length() * 2L;
        }
        if (node.isValueNode()) return NODE_OVERHEAD;
        long total = NODE_OVERHEAD;
        if (node.isArray()) {
            for (JsonNode child : node) total += weigh(child);
            return total;
        }
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            total += NODE_OVERHEAD + (long) field.getKey().length() * 2L
                    + weigh(field.getValue());
        }
        return total;
    }

    private static final long NODE_OVERHEAD = 32L;
}
