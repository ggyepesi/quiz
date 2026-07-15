package wikidata.explore.transform;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.template.sparql.SparqlValues;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Non-lossy LOAD enrichment: for each entity in the pool of a configured type,
 * load a property's <i>statements</i> (with qualifiers) and attach one statement
 * object per statement under {@code entityType.statementField}. Runs one batched
 * SPARQL query over the entities' QIDs, in the spirit of {@code DBpediaEnrichment}
 * — it touches none of the WDQS-tuned RuleNode generation path.
 *
 * <p>A statement object is stamped {@code statementType}, named after its value,
 * keyed by the statement GUID (so it's unique and stable), and holds the main
 * value under {@code valueField} plus each qualifier under its field. A later
 * {@code reify(promote)} lifts these into top-level events (e.g. Nomination).
 */
public class QualifierLoader {

    /** Wikidata caps a VALUES batch well above this; 200 keeps each query small. */
    // Smaller than a plain VALUES batch: the statement+qualifier path with a
    // label SERVICE is heavy, so 200 overruns WDQS (HTTP 502). loadWithSplit
    // halves any batch that still fails.
    private static final int BATCH = 50;

    /** Value-anchored batch: each value (a category) has many statements, so keep
     *  fewer per query; loadWithSplit halves any that still overruns. */
    private static final int VALUE_BATCH = 8;

    /** Batches fetch concurrently on this many loader threads. Only the network
     *  round-trips overlap — statements are applied to the pool under one lock, so
     *  the actual in-flight request count is still bounded by the SPARQL client's
     *  own permit gate, not by this number. */
    private static final int LOADER_THREADS = 6;

    public List<WikidataDynamicObject> enrich(
            Collection<WikidataDynamicObject> pool,
            QualifierLoadConfig cfg,
            WikidataSparqlClient client,
            GenerationLog log) {

        List<WikidataDynamicObject> created = new ArrayList<>();
        if (pool == null || client == null || cfg == null || !cfg.valid()) {
            return created;
        }

        // Index the target entities by QID so a result row finds its entity.
        Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && cfg.entityType().equals(o.typeName())
                    && o.qid() != null && o.qid().matches("Q\\d+")) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }
        if (byQid.isEmpty()) {
            return created;
        }

        String valueField = cfg.valueField() == null || cfg.valueField().isBlank()
                ? "value" : cfg.valueField();
        String stmtType = cfg.statementType() == null || cfg.statementType().isBlank()
                ? cfg.statementField() : cfg.statementType();

        // With the EXPLICIT allowed values (the categories), drive from the ENTITY
        // pool and pin those categories in every query — a tight two-sided join
        // (VALUES ?e + VALUES ?value) that only loads pool entities' statements and
        // reliably completes. Otherwise anchor on the VALUE set via the broad P31
        // type (a handful of queries) rather than re-walking every nominee.
        boolean valueAnchored = cfg.hasValueType() && !cfg.hasValueQids();
        List<String> anchors = valueAnchored
                ? fetchValueQids(client, cfg.valueTypeQid(), log)
                : new ArrayList<>(byQid.keySet());
        int batchSize = valueAnchored ? VALUE_BATCH : BATCH;
        int total = (anchors.size() + batchSize - 1) / batchSize;

        // The pool is mutated (byQid entities, created) only while holding this;
        // network fetches happen outside it, so batches overlap on the wire but
        // apply one at a time — the guard that makes value-anchored (shared-entity)
        // loads safe to parallelize.
        Object applyLock = new Object();

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        // One collapsible group over all the statement+qualifier batches.
        try (GenerationLog.Group g = sink.group("Qualifier load " + cfg.entityType()
                + " " + cfg.propertyPid() + " (" + anchors.size() + " "
                + (valueAnchored ? "values" : "entities") + ")")) {
            int idx = 0;
            List<Runnable> tasks = new ArrayList<>();
            for (int from = 0; from < anchors.size(); from += batchSize) {
                List<String> batch = new ArrayList<>(
                        anchors.subList(from, Math.min(from + batchSize, anchors.size())));
                String label = (++idx) + "/" + total;
                // A heavy statement+qualifier query can overrun WDQS (HTTP 502 /
                // timeout); halve-and-retry so one fat batch doesn't sink the rest.
                tasks.add(() -> loadWithSplit(client, cfg, batch, byQid, valueField,
                        stmtType, created, g, label, valueAnchored, applyLock));
            }
            runInParallel(tasks, g);

            // Recovery: a pool entity is here because it has a truthy P<pid> to a
            // configured value — so ZERO loaded statements means its batch was a
            // transient miss (a WDQS 502/timeout/silent-partial), not a real absence.
            // Re-query the empty ones in small ENTITY-anchored batches — a per-entity
            // query reliably loads its statements even when the main pass was
            // VALUE-anchored (batched by category, the heavier path where a popular
            // category's fat query drops its tail entities — the Oscars 586-lost case).
            int recBatch = Math.max(1, BATCH / 5);
            for (int round = 1; round <= 3
                    && !Thread.currentThread().isInterrupted(); round++) {
                List<String> missing = new ArrayList<>();
                for (Map.Entry<String, WikidataDynamicObject> en : byQid.entrySet()) {
                    if (en.getValue().get(cfg.statementField()) == null) {
                        missing.add(en.getKey());
                    }
                }
                if (missing.isEmpty()) {
                    break;
                }
                g.message("Qualifier load recovery " + round + ": re-querying "
                        + missing.size() + " entities with no statement\n");
                List<Runnable> recovery = new ArrayList<>();
                for (int from = 0; from < missing.size(); from += recBatch) {
                    List<String> batch = new ArrayList<>(missing.subList(
                            from, Math.min(from + recBatch, missing.size())));
                    String label = "recovery" + round + "/" + from;
                    recovery.add(() -> loadWithSplit(client, cfg, batch, byQid,
                            valueField, stmtType, created, g, label, false, applyLock));
                }
                runInParallel(recovery, g);
            }

            g.message("Qualifier load " + cfg.entityType() + " "
                    + cfg.propertyPid() + " -> " + created.size() + " "
                    + stmtType + " statements\n");
        }
        return created;
    }

    /** Runs each batch's fetch on a small pool so the network round-trips overlap;
     *  the actual in-flight request count is bounded by the SPARQL client. Awaits
     *  all of them, and on interruption cancels the rest so a cancelled generation
     *  stops promptly. loadWithSplit swallows its own per-batch errors. */
    private void runInParallel(List<Runnable> tasks, GenerationLog log) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        if (tasks.size() == 1) {
            tasks.get(0).run();
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(LOADER_THREADS, tasks.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (log != null) {
                        log.message("Qualifier load cancelled.\n");
                    }
                    break;
                } catch (ExecutionException e) {
                    // loadWithSplit records its own failures; a leak here is a bug.
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void loadWithSplit(
            WikidataSparqlClient client, QualifierLoadConfig cfg, List<String> batch,
            Map<String, WikidataDynamicObject> byQid, String valueField,
            String stmtType, List<WikidataDynamicObject> created, GenerationLog log,
            String batchLabel, boolean valueAnchored, Object applyLock) {
        if (batch.isEmpty() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            attachBatch(client, cfg, batch, byQid, valueField, stmtType, created,
                    log, batchLabel, valueAnchored, applyLock);
        } catch (Exception e) {
            if (e instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;   // cancelled — stop, don't split-retry
            }
            if (batch.size() > 1) {
                int mid = batch.size() / 2;
                loadWithSplit(client, cfg,
                        new ArrayList<>(batch.subList(0, mid)),
                        byQid, valueField, stmtType, created, log, batchLabel + "a",
                        valueAnchored, applyLock);
                loadWithSplit(client, cfg,
                        new ArrayList<>(batch.subList(mid, batch.size())),
                        byQid, valueField, stmtType, created, log, batchLabel + "b",
                        valueAnchored, applyLock);
            }
            // A size-1 failure is already recorded as failed() by attachBatch —
            // which owns the started→done/failed lifecycle of every attempt.
        }
    }

    private void attachBatch(
            WikidataSparqlClient client, QualifierLoadConfig cfg, List<String> batch,
            Map<String, WikidataDynamicObject> byQid, String valueField,
            String stmtType, List<WikidataDynamicObject> created,
            GenerationLog log, String batchLabel, boolean valueAnchored,
            Object applyLock)
            throws Exception {
        String query = buildQuery(cfg, batch, valueAnchored);

        // Record the entry NOW, before the request is issued, so the log keeps
        // requests in creation order (not completion order) and shows each one
        // running — then update it on finish. Matters once batches are loaded
        // concurrently: interleaved started→done nodes exercise the log UI.
        String title = "Qualifier load " + batchLabel + " (" + cfg.propertyPid()
                + ", " + batch.size()
                + (valueAnchored ? " values)" : " entities)");
        GenerationLog.Running running =
                log == null ? null : log.subqueryStarted(title, query);

        try {
            // Fetch off the lock so batches overlap on the wire...
            List<WikidataBinding> rows = client.query(query);

            // ...but apply to the shared pool under it, so two batches touching the
            // same nominee (value-anchored, one entity across several categories)
            // can't race on its statement list or on `created`.
            int added;
            synchronized (applyLock) {
                int before = created.size();
                // One statement (?st) can span several result rows — a shared award
                // lists each co-nominee on its own row. Key the statement object by
                // its GUID so those rows fold into ONE object (with the repeated
                // qualifier collected as a list), not a separate object per row.
                Map<String, WikidataDynamicObject> stmtByGuid = new LinkedHashMap<>();
                for (WikidataBinding row : rows) {
                    WikidataDynamicObject entity = byQid.get(row.qid("e"));
                    if (entity == null) {
                        continue;
                    }
                    WikidataDynamicObject value = row.entity("value");
                    if (value == null) {
                        continue;
                    }
                    String stmtId = row.qid("st");
                    String qid = stmtId != null && !stmtId.isBlank()
                            ? stmtId
                            : entity.qid() + "__" + value.qid();

                    WikidataDynamicObject stmt = stmtByGuid.get(qid);
                    if (stmt == null) {
                        stmt = new WikidataDynamicObject(qid, value.getDisplayName());
                        stmt.type(stmtType);
                        stmt.put(valueField, value);
                        stmtByGuid.put(qid, stmt);
                        entity.merge(cfg.statementField(), stmt);
                        created.add(stmt);
                    }
                    applyQualifiers(stmt, row, cfg, byQid);
                }
                added = created.size() - before;
            }
            if (running != null) {
                running.done(added + " statements");
            }
        } catch (Exception e) {
            // Resolve the started node so it never dangles as "running", then
            // rethrow so loadWithSplit can halve-and-retry.
            if (running != null) {
                running.failed(e.getMessage());
            }
            throw e;
        }
    }

    private static void applyQualifiers(
            WikidataDynamicObject stmt, WikidataBinding row, QualifierLoadConfig cfg,
            Map<String, WikidataDynamicObject> byQid) {
        if (cfg.qualifiers() == null) {
            return;
        }
        for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
            if (q == null || q.pid() == null || !q.pid().matches("P\\d+")) {
                continue;
            }
            String var = qualVar(q);
            switch (q.kind() == null ? QualifierLoadConfig.Kind.STRING : q.kind()) {
                case ENTITY -> {
                    WikidataDynamicObject e = row.entity(var);
                    if (e != null) {
                        // Unify with the pool by qid: if this entity was ALSO
                        // generated (as a class, carrying its own fields — e.g. an
                        // Edition with its date), reference that instance instead of
                        // a fresh bare copy, so the reified record's qualifier is the
                        // field-bearing one. One instance per qid (mapper identity).
                        WikidataDynamicObject pooled =
                                e.qid() == null ? null : byQid.get(e.qid());
                        if (pooled != null) {
                            e = pooled;
                        }
                        // A multi qualifier (e.g. P2453 co-nominees) repeats across
                        // rows — merge into a list so a shared award keeps every
                        // nominee; a single one just overwrites.
                        if (q.multi()) {
                            stmt.merge(q.fieldName(), e);
                        } else {
                            stmt.put(q.fieldName(), e);
                        }
                    }
                }
                case YEAR -> {
                    String y = row.value(var);
                    Integer year = parseYear(y);
                    if (year != null) {
                        // A date, not a bare number — FlexibleDate keeps that
                        // distinction through save/sort/display.
                        stmt.put(q.fieldName(), new aux.FlexibleDate(year));
                    }
                }
                case STRING -> {
                    String v = row.value(var);
                    if (v != null && !v.isBlank()) {
                        stmt.put(q.fieldName(), v);
                    }
                }
            }
        }
    }

    // SPARQL already reduces a YEAR qualifier with BIND(YEAR(?point)); but a
    // STRING/ENTITY qualifier may also be a bare year — be lenient.
    private static Integer parseYear(String s) {
        if (s == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(-?\\d{1,4})").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String qualVar(QualifierLoadConfig.Qualifier q) {
        String base = q.fieldName() == null || q.fieldName().isBlank()
                ? q.pid() : q.fieldName();
        base = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank() || Character.isDigit(base.charAt(0))) {
            base = "q_" + base;
        }
        return base;
    }

    // Fetch the value set (the instances of the value type, e.g. the Oscar
    // categories) so the load can anchor on it instead of every entity.
    private static List<String> fetchValueQids(
            WikidataSparqlClient client, String valueTypeQid, GenerationLog log) {
        List<String> out = new ArrayList<>();
        String q = "SELECT DISTINCT ?value WHERE { ?value wdt:P31 wd:"
                + valueTypeQid + " }";
        try {
            for (WikidataBinding b : client.query(q)) {
                String qid = b.qid("value");
                if (qid != null && qid.matches("Q\\d+")) {
                    out.add(qid);
                }
            }
            if (log != null) {
                log.subquery("Qualifier-load value set (P31=" + valueTypeQid + ")",
                        q, out.size() + " values");
            }
        } catch (Exception e) {
            if (log != null) {
                log.message("Value-set fetch failed: " + e.getMessage() + "\n");
            }
        }
        return out;
    }

    static String buildQuery(QualifierLoadConfig cfg, List<String> qids,
                                     boolean valueAnchored) {
        String pid = cfg.propertyPid();
        String anchorVar = valueAnchored ? "value" : "e";

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ?e ?st ?value ?valueLabel");
        if (cfg.qualifiers() != null) {
            for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
                if (q == null || q.pid() == null || !q.pid().matches("P\\d+")) {
                    continue;
                }
                String var = qualVar(q);
                sb.append(" ?").append(var);
                if (q.kind() == QualifierLoadConfig.Kind.ENTITY) {
                    sb.append(" ?").append(var).append("Label");
                }
            }
        }
        sb.append(" WHERE {\n");
        sb.append("  ").append(SparqlValues.clause(anchorVar, qids)).append("\n");
        sb.append("  ?e p:").append(pid).append(" ?st .\n");
        sb.append("  ?st ps:").append(pid).append(" ?value .\n");
        // Pin the value to the allowed categories AFTER it is bound from the
        // entity's statement, as a FILTER — not a second VALUES driver. A
        // `VALUES ?value` here lets Blazegraph start from the categories and walk
        // every nominee of every Oscar category (tens of thousands) before joining
        // down to the 50-entity batch — the ~2-minute (timeout → split) loads. A
        // FILTER can't drive the join, so the selective entity batch does.
        if (cfg.hasValueQids()) {
            sb.append("  ").append(filterInQids("value", cfg.valueQids()))
              .append("\n");
        } else if (!valueAnchored && cfg.hasValueType()) {
            // Entity-anchored with only a broad P31 type (no explicit categories):
            // filter the value by type. Value-anchored already pins ?value.
            sb.append("  ?value wdt:P31 wd:").append(cfg.valueTypeQid()).append(" .\n");
        }
        if (cfg.qualifiers() != null) {
            for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
                if (q == null || q.pid() == null || !q.pid().matches("P\\d+")) {
                    continue;
                }
                String var = qualVar(q);
                if (q.kind() == QualifierLoadConfig.Kind.YEAR) {
                    sb.append("  OPTIONAL { ?st pq:").append(q.pid())
                      .append(" ?").append(var).append("_t .")
                      .append(" BIND(YEAR(?").append(var).append("_t) AS ?")
                      .append(var).append(") }\n");
                } else {
                    sb.append("  OPTIONAL { ?st pq:").append(q.pid())
                      .append(" ?").append(var).append(" . }\n");
                }
            }
        }
        // SERVICE labels ?valueLabel and any qualifier-entity ?xLabel in one go,
        // with the shared en,mul fallback (nominee/forWork with only a non-en label
        // would otherwise render as a bare QID).
        sb.append(wikidata.query.LabelService.service("en"));
        sb.append("}\n");
        return sb.toString();
    }

    /** {@code FILTER(?var IN (wd:Q…, wd:Q…))} — a post-bind value constraint that
     *  can't drive the join (so the entity batch stays the driver). */
    private static String filterInQids(String var, List<String> qids) {
        StringBuilder in = new StringBuilder();
        for (String q : qids) {
            if (in.length() > 0) {
                in.append(", ");
            }
            in.append("wd:").append(q);
        }
        return "FILTER(?" + var + " IN (" + in + "))";
    }
}
