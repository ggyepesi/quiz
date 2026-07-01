package wikidata.explore.transform;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // Anchor on the VALUE set (e.g. the ~58 Oscar categories) when there's a
        // value-type filter — a handful of queries — instead of the ENTITY set
        // (every nominee), which re-walks the same P1411 edges over hundreds of
        // batches. The statements are the same either way; we just drive from the
        // small end. Rows still carry ?e, so they attach to the pooled entities.
        boolean valueAnchored = cfg.hasValueType();
        List<String> anchors = valueAnchored
                ? fetchValueQids(client, cfg.valueTypeQid(), log)
                : new ArrayList<>(byQid.keySet());
        int batchSize = valueAnchored ? VALUE_BATCH : BATCH;
        int total = (anchors.size() + batchSize - 1) / batchSize;
        int idx = 0;
        for (int from = 0; from < anchors.size(); from += batchSize) {
            if (Thread.currentThread().isInterrupted()) {
                if (log != null) {
                    log.message("Qualifier load cancelled.\n");
                }
                break;
            }
            List<String> batch = new ArrayList<>(
                    anchors.subList(from, Math.min(from + batchSize, anchors.size())));
            // A heavy statement+qualifier query can overrun WDQS (HTTP 502 /
            // timeout); halve-and-retry so one fat batch doesn't sink the rest.
            loadWithSplit(client, cfg, batch, byQid, valueField, stmtType, created,
                    log, (++idx) + "/" + total, valueAnchored);
        }
        if (log != null) {
            log.message("Qualifier load " + cfg.entityType() + " "
                    + cfg.propertyPid() + " -> " + created.size() + " "
                    + stmtType + " statements\n");
        }
        return created;
    }

    private void loadWithSplit(
            WikidataSparqlClient client, QualifierLoadConfig cfg, List<String> batch,
            Map<String, WikidataDynamicObject> byQid, String valueField,
            String stmtType, List<WikidataDynamicObject> created, GenerationLog log,
            String batchLabel, boolean valueAnchored) {
        if (batch.isEmpty() || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            attachBatch(client, cfg, batch, byQid, valueField, stmtType, created,
                    log, batchLabel, valueAnchored);
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
                        valueAnchored);
                loadWithSplit(client, cfg,
                        new ArrayList<>(batch.subList(mid, batch.size())),
                        byQid, valueField, stmtType, created, log, batchLabel + "b",
                        valueAnchored);
            } else if (log != null) {
                log.subqueryFailed("Qualifier load " + batchLabel + " ("
                        + cfg.propertyPid() + ", " + batch.get(0) + ")",
                        buildQuery(cfg, batch, valueAnchored), e.getMessage());
            }
        }
    }

    private void attachBatch(
            WikidataSparqlClient client, QualifierLoadConfig cfg, List<String> batch,
            Map<String, WikidataDynamicObject> byQid, String valueField,
            String stmtType, List<WikidataDynamicObject> created,
            GenerationLog log, String batchLabel, boolean valueAnchored)
            throws Exception {
        String query = buildQuery(cfg, batch, valueAnchored);
        int before = created.size();
        // One statement (?st) can span several result rows — a shared award lists
        // each co-nominee on its own row. Key the statement object by its GUID so
        // those rows fold into ONE object (with the repeated qualifier collected as
        // a list), instead of a separate object per row.
        Map<String, WikidataDynamicObject> stmtByGuid = new LinkedHashMap<>();
        for (WikidataBinding row : client.query(query)) {
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
            applyQualifiers(stmt, row, cfg);
        }
        if (log != null) {
            log.subquery("Qualifier load " + batchLabel + " (" + cfg.propertyPid()
                    + ", " + batch.size() + " entities)", query,
                    (created.size() - before) + " statements");
        }
    }

    private static void applyQualifiers(
            WikidataDynamicObject stmt, WikidataBinding row, QualifierLoadConfig cfg) {
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
                        stmt.put(q.fieldName(), year);
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

    private static String buildQuery(QualifierLoadConfig cfg, List<String> qids,
                                     boolean valueAnchored) {
        String pid = cfg.propertyPid();
        String anchorVar = valueAnchored ? "value" : "e";
        StringBuilder values = new StringBuilder();
        for (String q : qids) {
            values.append("wd:").append(q).append(' ');
        }

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
        sb.append("  VALUES ?").append(anchorVar).append(" { ")
          .append(values.toString().trim()).append(" }\n");
        sb.append("  ?e p:").append(pid).append(" ?st .\n");
        sb.append("  ?st ps:").append(pid).append(" ?value .\n");
        // Anchored on the entity: filter the value by type. Anchored on the value:
        // the VALUES already pins the categories, so no type filter is needed.
        if (!valueAnchored && cfg.hasValueType()) {
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
        // SERVICE labels ?valueLabel and any qualifier-entity ?xLabel in one go.
        sb.append("  SERVICE wikibase:label { ")
          .append("bd:serviceParam wikibase:language \"en\". }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
