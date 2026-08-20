package wikidata.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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
    private record FactKey(String qid, String pid) { }
    private static final class FactMeasurement {
        Long bankedBytes;
        boolean demanded;
        boolean lateDemand;
        boolean retentionPlannedBeforeAcquisition;
        String retentionSource;
    }
    private static final class PropertyTotals {
        long bankedEntities, estimatedBytes, demandedEntities;
        long unusedEntities, unusedEstimatedBytes;
    }
    private static final class RetentionTotals {
        long bankedEntities, estimatedBytes, demandedEntities, unusedEstimatedBytes;
    }
    /** What an evicted document could actually have answered. A QID alone is too
     * coarse: dropping a P1411 slice must not make the first P136 request look like a
     * re-fetch, while dropping a whole claims body legitimately does. */
    private static final class EvictedCapabilities {
        boolean entityMetadata;
        boolean wholeClaims;
        final Set<String> claimPids = new LinkedHashSet<>();

        void add(Document document) {
            entityMetadata = true;
            if (!document.claims()) return;
            if (document.pids() == null) wholeClaims = true;
            else claimPids.addAll(document.pids());
        }

        boolean answered(boolean requireClaims, Collection<String> pids) {
            if (!entityMetadata) return false;
            if (!requireClaims) return true;
            if (wholeClaims) return true;
            return pids != null && claimPids.containsAll(pids);
        }
    }
    public record PropertyUsage(
            String propertyPid, long bankedEntities, long estimatedBytes,
            long demandedEntities, long unusedEntities, long unusedEstimatedBytes,
            long cacheHits, long evictedRefetches, long lateDemands) { }
    public record RetentionUsage(
            String source, String propertyPid, long bankedEntities,
            long estimatedBytes, long demandedEntities, long unusedEstimatedBytes) { }
    /**
     * The budget one run may hold, sized from the access pattern rather than guessed.
     *
     * <p>Replaying this project's own recorded requests gives the reuse-distance curve:
     * two thirds of all entity fetches are repeats, but the median reuse distance is
     * ~11,000 documents, so a cache is worth nothing until it holds most of the working
     * set — under 5,000 documents captures 0.3% of the repeats, 20,000 captures all of
     * them. It is a cliff, not a gradient, and the earlier 64 MB sat just below it,
     * holding ~4,000 documents and recording 10 hits in a run that made 67,837 fetches.
     *
     * <p>A slice is retained instead of the whole claims body — the same entities kept
     * whole would be about five gigabytes — and the budget is weighed by payload, not
     * by entry count, since Wikidata bodies differ by orders of magnitude.
     *
     * <p>384 MB was sized from a measured ~17 KB per slice, and three runs since then
     * say the slices are bigger than that: 383 MB held only ~11,000 documents (~35 KB
     * each) and 2,566 documents were fetched a second time after eviction. By then the
     * eviction ORDER had been exhausted as a lever — statement bodies nobody re-reads
     * already yield first (8,879 of 10,019 evictions), and what remains is planned
     * documents evicting planned documents, which no policy can fix. 768 MB holds the
     * measured working set at its real size rather than its estimated one.
     */
    public static final long DEFAULT_MAX_ESTIMATED_BYTES = 768L * 1024L * 1024L;

    /**
     * A retained document, and WHICH claim properties it kept. Keeping the whole claims
     * body is what a run cannot afford: measured on this project's own access pattern,
     * two thirds of all entity fetches are repeats, but the working set is ~18k
     * documents at ~260 KB retained each — five gigabytes to hold what pays off. The
     * declared slice of the same entity is a few hundred bytes.
     *
     * <p>The slice is what makes {@code pids} load-bearing rather than bookkeeping: a
     * document that kept only P31 must not answer a question about P569, because the
     * answer would be "no such statement" when the truth is "never fetched". Every
     * lookup therefore states the properties it needs.
     */
    private record Document(
            JsonNode json, boolean claims, Set<String> pids, long estimatedBytes) { }

    private final Map<String, Document> documents =
            new LinkedHashMap<>(256, 0.75f, true);
    private final long maxEstimatedBytes;
    private long estimatedBytes;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong fetched = new AtomicLong();
    // Capability-aware eviction history: a later request is a preventable re-fetch only
    // when the evicted document could have answered that exact request.
    private final Map<String, EvictedCapabilities> evictedCapabilities =
            new LinkedHashMap<>();
    private long evictions;
    private long oversized;
    private long evictedRefetches;
    // One bounded record per measured QID/PID. Previously three independent maps held
    // the same key forever and their invisible heap sat outside the cache budget.
    private final Map<FactKey, FactMeasurement> factMeasurements = new LinkedHashMap<>();
    private static final long ESTIMATED_MEASUREMENT_BYTES = 192L;
    private final long maxMeasurementEntries;
    private boolean measurementTruncated;
    private final Map<String, Long> cacheHitsByProperty = new LinkedHashMap<>();
    private final Map<String, Long> evictedRefetchesByProperty = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> demandsBySource = new LinkedHashMap<>();
    // QIDs with at least one bounded QID/PID retention plan. Exact properties live on
    // the corresponding FactMeasurement; this companion index makes asking whether a
    // whole-body document has ANY planned use O(1), without a second unbounded pair set.
    private final Set<String> plannedQids = new LinkedHashSet<>();
    private long unplannedEvictions;

    public WikidataFactStore() { this(DEFAULT_MAX_ESTIMATED_BYTES); }

    public WikidataFactStore(long maxEstimatedBytes) {
        this.maxEstimatedBytes = Math.max(1, maxEstimatedBytes);
        // Measurements may consume at most one eighth of the run budget. The estimate
        // is deliberately conservative and is reported when the cap truncates detail.
        this.maxMeasurementEntries = Math.max(0,
                this.maxEstimatedBytes / 8 / ESTIMATED_MEASUREMENT_BYTES);
    }

    /** Configured retained-payload ceiling for this run. */
    public long maxEstimatedBytes() { return maxEstimatedBytes; }

    /**
     * Pure lookup: metrics are changed only when a cached response is actually used —
     * except for one count that can only be taken here. A document this store HELD and
     * then evicted, now asked for again, is a fetch a larger budget would have saved:
     * the number that says whether a poor hit rate means the consumers do not overlap
     * (nothing to gain) or that the budget is simply too small for them to meet.
     */
    public synchronized List<String> missing(
            Collection<String> qids, boolean requireClaims) {
        return missing(qids, requireClaims, null);
    }

    public synchronized List<String> missing(
            Collection<String> qids, boolean requireClaims, Collection<String> pids) {
        List<String> out = new ArrayList<>();
        if (qids == null) return out;
        for (String qid : qids) {
            Document document = documents.get(qid);
            if (!answers(document, requireClaims, pids)) {
                out.add(qid);
                EvictedCapabilities evicted = evictedCapabilities.get(qid);
                if (evicted != null && evicted.answered(requireClaims, pids)) {
                    evictedRefetches++;
                    if (pids != null) pids.forEach(pid ->
                            evictedRefetchesByProperty.merge(pid, 1L, Long::sum));
                }
            }
        }
        return out;
    }

    /** Whether a held document can answer this question — it must have claims when
     *  claims were asked for, and must have RETAINED every property being asked about.
     *  A slice that kept P31 knows nothing about P569, and saying so is the difference
     *  between a cache miss and a false "this entity has no date of birth". */
    private static boolean answers(
            Document document, boolean requireClaims, Collection<String> pids) {
        if (document == null) return false;
        if (!requireClaims) return true;
        if (!document.claims()) return false;
        if (pids == null) return document.pids() == null;   // the whole body was wanted
        return document.pids() == null || document.pids().containsAll(pids);
    }

    public synchronized void accept(JsonNode response, boolean claims) {
        accept(response, claims, null);
    }

    /**
     * Retains each entity, keeping only the claim properties this fetch was made for.
     * A later question about another property finds the slice does not answer it and
     * fetches again — one request, against holding every property of every entity for
     * the whole run.
     */
    public synchronized void accept(
            JsonNode response, boolean claims, Collection<String> pids) {
        if (response == null) return;
        Set<String> retain = pids == null ? null : Set.copyOf(pids);
        response.path("entities").fields().forEachRemaining(entry -> {
            JsonNode copy = entry.getValue().deepCopy();
            fetched.incrementAndGet();
            Document previous = documents.get(entry.getKey());
            if (previous != null && previous.claims() && !claims) return;
            // A complete claims document already answers every sliced question. A
            // concurrent or compatibility-path slice arriving later must not replace
            // it with less information merely because that request finished last.
            if (previous != null && previous.claims() && previous.pids() == null
                    && claims && retain != null) return;
            Set<String> kept = retain;
            if (claims && retain != null) {
                // Merge with what this entity already had: two fetches for different
                // declarations leave one document answering both.
                kept = new LinkedHashSet<>(retain);
                if (previous != null && previous.claims() && previous.pids() != null) {
                    kept.addAll(previous.pids());
                    mergeClaims(copy, previous.json(), previous.pids());
                }
                sliceClaims(copy, kept);
                kept = Set.copyOf(kept);
            }
            long weight = estimate(copy);
            if (weight > maxEstimatedBytes) {
                // The fetched response is still returned to its caller, but it cannot
                // be retained. Keep an older, smaller slice if one exists: failing to
                // cache the augmentation is not a reason to discard useful facts.
                oversized++;
                return; // usable by the caller now, but too large to retain safely
            }
            if (claims && kept != null) {
                recordBankedProperties(entry.getKey(), copy, kept);
            }
            if (previous != null) estimatedBytes -= previous.estimatedBytes();
            documents.put(entry.getKey(), new Document(copy, claims, kept, weight));
            estimatedBytes += weight;
        });
        evictOldest();
    }

    public synchronized JsonNode response(
            Collection<String> qids, boolean requireClaims, ObjectMapper mapper) {
        return response(qids, requireClaims, null, mapper);
    }

    public synchronized JsonNode response(
            Collection<String> qids, boolean requireClaims,
            Collection<String> pids, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode entities = root.putObject("entities");
        if (qids == null) return root;
        for (String qid : qids) {
            Document document = documents.get(qid);
            if (!answers(document, requireClaims, pids)) return null;
            entities.set(qid, document.json().deepCopy());
        }
        return root;
    }

    /**
     * The fetched response, completed with what is already held for the qids it does
     * not carry. A fetch only asks for what was missing, so returning it alone drops
     * every entity the store answered — which was invisible while everything fetched
     * was also retained, and is not once retention follows a plan.
     */
    public synchronized JsonNode merged(
            JsonNode fetched, Collection<String> qids, boolean requireClaims,
            Collection<String> pids, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode entities = root.putObject("entities");
        JsonNode answered = fetched == null ? null : fetched.path("entities");
        if (answered != null && answered.isObject()) {
            answered.fields().forEachRemaining(
                    entry -> entities.set(entry.getKey(), entry.getValue()));
        }
        if (qids != null) {
            for (String qid : qids) {
                if (entities.has(qid)) continue;
                Document document = documents.get(qid);
                if (answers(document, requireClaims, pids)) {
                    entities.set(qid, document.json().deepCopy());
                }
            }
        }
        return root;
    }

    /** Drops every claim property outside the slice. Labels, id and the rest of the
     *  entity document are untouched — they cost little and every consumer wants them. */
    private static void sliceClaims(JsonNode entity, Set<String> keep) {
        JsonNode claims = entity.path("claims");
        if (!claims.isObject()) return;
        List<String> drop = new ArrayList<>();
        claims.fieldNames().forEachRemaining(pid -> {
            if (!keep.contains(pid)) drop.add(pid);
        });
        ((ObjectNode) claims).remove(drop);
    }

    /** Carries the properties an earlier slice held into the document replacing it. */
    private static void mergeClaims(JsonNode into, JsonNode from, Set<String> pids) {
        JsonNode target = into.path("claims");
        JsonNode source = from.path("claims");
        if (!target.isObject() || !source.isObject()) return;
        for (String pid : pids) {
            if (!target.has(pid) && source.has(pid)) {
                ((ObjectNode) target).set(pid, source.get(pid).deepCopy());
            }
        }
    }

    public long cacheHits() { return hits.get(); }
    /** Documents dropped to stay inside the budget. */
    public synchronized long evictions() { return evictions; }
    /** Evictions that fell on a slice no producer had planned to re-read — the budget
     *  reclaimed from facts nobody was coming back for. */
    public synchronized long unplannedEvictions() { return unplannedEvictions; }

    /** Documents too large to retain at all — the budget cannot hold even one. */
    public synchronized long oversized() { return oversized; }
    /** Lookups for a document this store had evicted: what a larger budget would have
     *  saved, as opposed to consumers that simply never ask about the same entity. */
    public synchronized long evictedRefetches() { return evictedRefetches; }
    public void recordHits(long count) { if (count > 0) hits.addAndGet(count); }
    public synchronized void recordHits(long count, Collection<String> pids) {
        recordHits(count);
        if (count > 0 && pids != null) {
            pids.forEach(pid -> cacheHitsByProperty.merge(pid, count, Long::sum));
        }
    }

    /** Records the consumer's real need, separately from the wider slice retained in
     * anticipation of later consumers. Unique QID/PID pairs make repeated convergence
     * passes harmless; source totals explain which phase/class created the demand. */
    public synchronized void recordDemand(
            String source, Collection<String> qids, Collection<String> pids) {
        if (qids == null || pids == null) return;
        String owner = source == null || source.isBlank() ? "unspecified" : source;
        Map<String, Long> byPid = demandsBySource.computeIfAbsent(
                owner, ignored -> new LinkedHashMap<>());
        for (String qid : qids) {
            if (qid == null) continue;
            for (String pid : pids) {
                if (pid == null) continue;
                FactMeasurement measurement = measurement(new FactKey(qid, pid));
                if (measurement != null && !measurement.demanded) {
                    measurement.demanded = true;
                    boolean entityAlreadyFetched = documents.containsKey(qid)
                            || evictedCapabilities.containsKey(qid);
                    measurement.lateDemand = entityAlreadyFetched
                            && !measurement.retentionPlannedBeforeAcquisition;
                    byPid.merge(pid, 1L, Long::sum);
                }
            }
        }
    }

    /** Attributes the wider anticipated slice to the producer that caused it to be
     * banked. First producer wins: overlapping populations must not double-count the
     * same retained QID/PID bytes. */
    public synchronized void recordRetentionPlan(
            String source, Collection<String> qids, Collection<String> pids) {
        if (qids == null || pids == null) return;
        String owner = source == null || source.isBlank() ? "unspecified" : source;
        for (String qid : qids) for (String pid : pids) {
            if (qid != null && pid != null) {
                // The entity's plan is recorded whatever the measurement cap does: it is
                // one entry per ENTITY, not per pair, and it is what eviction falls back
                // on. Recording it only alongside a measurement made a truncated
                // measurement silently invert the eviction order — ranking planned
                // documents unplanned exactly when the budget is tight enough to matter.
                plannedQids.add(qid);
                FactMeasurement measurement = measurement(new FactKey(qid, pid));
                if (measurement != null && measurement.retentionSource == null) {
                    measurement.retentionSource = owner;
                    measurement.retentionPlannedBeforeAcquisition =
                            !documents.containsKey(qid)
                                    && !evictedCapabilities.containsKey(qid);
                }
            }
        }
    }

    public synchronized List<PropertyUsage> propertyUsage() {
        Map<String, PropertyTotals> totals = new LinkedHashMap<>();
        factMeasurements.forEach((key, measurement) -> {
            if (measurement.bankedBytes == null) return;
            PropertyTotals values = totals.computeIfAbsent(
                    key.pid(), ignored -> new PropertyTotals());
            values.bankedEntities++; values.estimatedBytes += measurement.bankedBytes;
            if (measurement.demanded) values.demandedEntities++;
            else {
                values.unusedEntities++;
                values.unusedEstimatedBytes += measurement.bankedBytes;
            }
        });
        return totals.entrySet().stream().map(e -> new PropertyUsage(
                        e.getKey(), e.getValue().bankedEntities,
                        e.getValue().estimatedBytes, e.getValue().demandedEntities,
                        e.getValue().unusedEntities, e.getValue().unusedEstimatedBytes,
                        cacheHitsByProperty.getOrDefault(e.getKey(), 0L),
                        evictedRefetchesByProperty.getOrDefault(e.getKey(), 0L),
                        lateDemands(e.getKey())))
                .sorted(java.util.Comparator.comparingLong(PropertyUsage::estimatedBytes)
                        .reversed().thenComparing(PropertyUsage::propertyPid))
                .toList();
    }

    private long lateDemands(String pid) {
        return factMeasurements.entrySet().stream()
                .filter(e -> e.getKey().pid().equals(pid) && e.getValue().lateDemand)
                .count();
    }

    /** Unique measured QID/property needs discovered after the entity was fetched. */
    public synchronized long lateDemandPairs() {
        return factMeasurements.values().stream().filter(m -> m.lateDemand).count();
    }

    /** Unique measured needs known before the entity's first acquisition boundary. */
    public synchronized long preplannedDemandPairs() {
        return factMeasurements.values().stream()
                .filter(m -> m.demanded && !m.lateDemand).count();
    }

    public synchronized Map<String, Map<String, Long>> demandsBySource() {
        Map<String, Map<String, Long>> copy = new LinkedHashMap<>();
        demandsBySource.forEach((source, pids) -> copy.put(source,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pids))));
        return java.util.Collections.unmodifiableMap(copy);
    }

    public synchronized List<RetentionUsage> retentionUsage() {
        record SourcePid(String source, String pid) { }
        Map<SourcePid, RetentionTotals> totals = new LinkedHashMap<>();
        factMeasurements.forEach((key, measurement) -> {
            if (measurement.bankedBytes == null) return;
            SourcePid group = new SourcePid(
                    measurement.retentionSource == null
                            ? "unattributed" : measurement.retentionSource,
                    key.pid());
            RetentionTotals values = totals.computeIfAbsent(
                    group, ignored -> new RetentionTotals());
            values.bankedEntities++; values.estimatedBytes += measurement.bankedBytes;
            if (measurement.demanded) values.demandedEntities++;
            else values.unusedEstimatedBytes += measurement.bankedBytes;
        });
        return totals.entrySet().stream().map(e -> new RetentionUsage(
                        e.getKey().source(), e.getKey().pid(),
                        e.getValue().bankedEntities, e.getValue().estimatedBytes,
                        e.getValue().demandedEntities, e.getValue().unusedEstimatedBytes))
                .sorted(java.util.Comparator.comparingLong(RetentionUsage::estimatedBytes)
                        .reversed().thenComparing(RetentionUsage::source)
                        .thenComparing(RetentionUsage::propertyPid))
                .toList();
    }
    public long fetchedDocuments() { return fetched.get(); }
    public synchronized int size() { return documents.size(); }
    public synchronized long estimatedBytes() {
        return estimatedBytes + measurementEstimatedBytes();
    }
    public synchronized long measurementEstimatedBytes() {
        return factMeasurements.size() * ESTIMATED_MEASUREMENT_BYTES;
    }
    public synchronized boolean measurementTruncated() { return measurementTruncated; }

    private void evictOldest() {
        // A slice nobody planned to come back for goes first, however recently it
        // arrived. One run held 194 MB of statement bodies that were parsed once and
        // never read again, and paid for them by evicting the properties every later
        // pass did read — 3,018 documents fetched a second time. Recency is the right
        // order among equals; it is the wrong order between a fact that is wanted again
        // and one that is not.
        if (overBudget()) evict((qid, document) -> !planned(qid, document));
        if (overBudget()) evict((qid, document) -> true);
    }

    private boolean overBudget() {
        return estimatedBytes + measurementEstimatedBytes() > maxEstimatedBytes;
    }

    private boolean planned(String qid, Document document) {
        // A whole body can answer anything, but capability is not demand: it earns
        // priority only when this entity has at least one declared future use.
        if (document.pids() == null) return plannedQids.contains(qid);
        boolean unmeasured = false;
        for (String pid : document.pids()) {
            FactMeasurement measurement = factMeasurements.get(new FactKey(qid, pid));
            if (measurement == null) unmeasured = true;
            else if (measurement.retentionSource != null) return true;
        }
        // Past the cap a pair has no record to consult, and "no record" is not "not
        // wanted". Fall back to the entity's own plan so a truncated measurement makes
        // the order coarser rather than backwards.
        return unmeasured && measurementTruncated && plannedQids.contains(qid);
    }

    private void evict(java.util.function.BiPredicate<String, Document> evictable) {
        Iterator<Map.Entry<String, Document>> entries = documents.entrySet().iterator();
        while (overBudget() && entries.hasNext()) {
            Map.Entry<String, Document> eldest = entries.next();
            if (!evictable.test(eldest.getKey(), eldest.getValue())) continue;
            estimatedBytes -= eldest.getValue().estimatedBytes();
            evictedCapabilities.computeIfAbsent(
                    eldest.getKey(), ignored -> new EvictedCapabilities())
                    .add(eldest.getValue());
            evictions++;
            if (!planned(eldest.getKey(), eldest.getValue())) unplannedEvictions++;
            entries.remove();
        }
    }

    private void recordBankedProperties(String qid, JsonNode entity, Set<String> pids) {
        JsonNode claims = entity.path("claims");
        for (String pid : pids) {
            JsonNode value = claims.isObject() ? claims.get(pid) : null;
            FactMeasurement measurement = measurement(new FactKey(qid, pid));
            if (measurement != null) {
                measurement.bankedBytes = value == null ? 0L : weigh(value);
            }
        }
    }

    private FactMeasurement measurement(FactKey key) {
        FactMeasurement existing = factMeasurements.get(key);
        if (existing != null) return existing;
        if (factMeasurements.size() >= maxMeasurementEntries) {
            measurementTruncated = true;
            return null;
        }
        FactMeasurement created = new FactMeasurement();
        factMeasurements.put(key, created);
        return created;
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
