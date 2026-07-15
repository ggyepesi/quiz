package wikidata.explore.transform;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Non-lossy LOAD enrichment: for each entity in the pool of a configured type,
 * load a property's <i>statements</i> (with qualifiers) and attach one statement
 * object per statement under {@code entityType.statementField}. A later
 * {@code reify(promote)} lifts these into top-level events (e.g. Nomination).
 *
 * <p>Statements + qualifiers are read from the {@code wbgetentities} action API
 * (a claim carries its mainsnak value AND its qualifiers), not from SPARQL: the
 * old {@code ?e p:Pxxx ?st . ?st ps:Pxxx ?value . OPTIONAL {?st pq:Pyyy ?q} +
 * SERVICE label} query soft-timed-out on WDQS and needed halve-retry + recovery
 * rounds. The action API doesn't drop tails, so all of that machinery is gone.
 *
 * <p>A statement object is stamped {@code statementType}, keyed by its statement
 * GUID (unique + stable), holds the main value under {@code valueField} plus each
 * qualifier under its field, and is named after its value. Value/qualifier entity
 * refs reuse an already-labelled pool object when one exists (by qid); any that
 * don't are named in one shared best-effort label pass.
 */
public class QualifierLoader {

    private WikidataApiClient api;

    /** Override the action-API client (share one / inject a stub for tests). */
    public QualifierLoader api(WikidataApiClient api) {
        this.api = api;
        return this;
    }

    private WikidataApiClient api() {
        if (api == null) {
            api = new WikidataApiClient("QuizProject/1.0 (ggyepesi@gmail.com)");
        }
        return api;
    }

    public List<WikidataDynamicObject> enrich(
            Collection<WikidataDynamicObject> pool,
            QualifierLoadConfig cfg,
            WikidataSparqlClient client,
            GenerationLog log) {

        List<WikidataDynamicObject> created = new ArrayList<>();
        if (pool == null || cfg == null || !cfg.valid()) {
            return created;
        }

        // Index the entityType pool (whose statements we reify) and the WHOLE pool
        // (so a value/qualifier ref reuses an already-labelled pool object by qid).
        Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
        Map<String, WikidataDynamicObject> poolByQid = new LinkedHashMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o == null || o.qid() == null || !o.qid().matches("Q\\d+")) {
                continue;
            }
            poolByQid.putIfAbsent(o.qid(), o);
            if (cfg.entityType().equals(o.typeName())) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }
        if (byQid.isEmpty()) {
            return created;
        }

        String valueField = blankTo(cfg.valueField(), "value");
        String stmtType = blankTo(cfg.statementType(), cfg.statementField());

        // Allowed value set: explicit category QIDs, or the instances of the value
        // type (one SPARQL query), or null = accept every value.
        Set<String> allowedValues = null;
        if (cfg.hasValueQids()) {
            allowedValues = new HashSet<>(cfg.valueQids());
        } else if (cfg.hasValueType() && client != null) {
            allowedValues = new HashSet<>(
                    fetchValueQids(client, cfg.valueTypeQid(), log));
        }

        List<String> qualifierPids = new ArrayList<>();
        if (cfg.qualifiers() != null) {
            for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
                if (q != null && q.pid() != null && q.pid().matches("P\\d+")) {
                    qualifierPids.add(q.pid());
                }
            }
        }

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        Map<String, WikidataDynamicObject> refCache = new LinkedHashMap<>();

        try (GenerationLog.Group g = sink.group("Qualifier load " + cfg.entityType()
                + " " + cfg.propertyPid() + " (" + byQid.size() + " entities)")) {

            Map<String, List<WikidataApiClient.ApiStatement>> statements;
            try {
                statements = api().getStatements(new ArrayList<>(byQid.keySet()),
                        cfg.propertyPid(), qualifierPids, g::subquery);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return created;
                }
                g.message("WARNING: qualifier load via wbgetentities failed ("
                        + e.getMessage() + ") — no statements reified this run.\n");
                return created;
            }

            // One statement object per claim: its value + qualifiers.
            for (Map.Entry<String, WikidataDynamicObject> en : byQid.entrySet()) {
                WikidataDynamicObject entity = en.getValue();
                List<WikidataApiClient.ApiStatement> stmts = statements.get(en.getKey());
                if (stmts == null) {
                    continue;
                }
                for (WikidataApiClient.ApiStatement s : stmts) {
                    if (allowedValues != null && !allowedValues.contains(s.value())) {
                        continue;   // e.g. a non-Oscar award the winner also has
                    }
                    WikidataDynamicObject valueRef = ref(s.value(), poolByQid, refCache);
                    WikidataDynamicObject stmt = new WikidataDynamicObject(
                            s.id(), valueRef.getDisplayName());
                    stmt.type(stmtType);
                    stmt.put(valueField, valueRef);
                    applyQualifiers(stmt, s, cfg, poolByQid, refCache);
                    entity.merge(cfg.statementField(), stmt);
                    created.add(stmt);
                }
            }

            // Name the value/qualifier refs that weren't already pooled+labelled,
            // then let each statement's name follow its (now-labelled) value.
            resolveRefLabels(refCache, g);
            for (WikidataDynamicObject stmt : created) {
                if (stmt.get(valueField) instanceof WikidataDynamicObject v) {
                    stmt.name(v.getDisplayName());
                }
            }

            g.message("Qualifier load " + cfg.entityType() + " " + cfg.propertyPid()
                    + " -> " + created.size() + " " + stmtType + " statements\n");
        }
        return created;
    }

    /** Apply each configured qualifier from the statement onto the reified object,
     *  per its kind: ENTITY -> a (pool-unified) ref, YEAR -> a FlexibleDate parsed
     *  from the ISO time, STRING -> the raw literal. A {@code multi} qualifier keeps
     *  every value (e.g. co-nominees); a single one keeps the first. */
    private static void applyQualifiers(
            WikidataDynamicObject stmt, WikidataApiClient.ApiStatement s,
            QualifierLoadConfig cfg,
            Map<String, WikidataDynamicObject> poolByQid,
            Map<String, WikidataDynamicObject> refCache) {

        if (cfg.qualifiers() == null) {
            return;
        }
        for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
            if (q == null || q.pid() == null || !q.pid().matches("P\\d+")) {
                continue;
            }
            List<String> vals = s.qualifier(q.pid());
            if (vals.isEmpty()) {
                continue;
            }
            switch (q.kind() == null ? QualifierLoadConfig.Kind.STRING : q.kind()) {
                case ENTITY -> {
                    for (String vq : vals) {
                        if (!vq.matches("Q\\d+")) {
                            continue;
                        }
                        WikidataDynamicObject ref = ref(vq, poolByQid, refCache);
                        if (q.multi()) {
                            stmt.merge(q.fieldName(), ref);
                        } else {
                            stmt.put(q.fieldName(), ref);
                            break;
                        }
                    }
                }
                case YEAR -> {
                    Integer year = parseYear(vals.get(0));
                    if (year != null) {
                        // A date, not a bare number — FlexibleDate keeps that
                        // distinction through save/sort/display.
                        stmt.put(q.fieldName(), new aux.FlexibleDate(year));
                    }
                }
                case STRING -> {
                    String v = vals.get(0);
                    if (v != null && !v.isBlank()) {
                        stmt.put(q.fieldName(), v);
                    }
                }
            }
        }
    }

    /** Resolve a QID to an already-labelled pool object, or a fresh QID-named ref
     *  (cached so a category shared across statements is one object, and labelled
     *  once by {@link #resolveRefLabels}). */
    private static WikidataDynamicObject ref(
            String qid, Map<String, WikidataDynamicObject> poolByQid,
            Map<String, WikidataDynamicObject> refCache) {
        WikidataDynamicObject pooled = poolByQid.get(qid);
        if (pooled != null) {
            return pooled;
        }
        return refCache.computeIfAbsent(qid, k -> new WikidataDynamicObject(k, k));
    }

    /** One shared best-effort wbgetentities labels pass over the fresh refs (values +
     *  qualifier entities not already in the pool). A failure leaves the QIDs. */
    private void resolveRefLabels(
            Map<String, WikidataDynamicObject> refCache, GenerationLog log) {
        List<String> qids = new ArrayList<>();
        for (WikidataDynamicObject o : refCache.values()) {
            if (o.getDisplayName().equals(o.qid())) {
                qids.add(o.qid());
            }
        }
        if (qids.isEmpty()) {
            return;
        }
        try {
            Map<String, WikidataApiClient.ApiEntity> details =
                    api().getEntities(qids, List.of(),
                            log == null ? null : log::subquery);
            for (WikidataDynamicObject o : refCache.values()) {
                WikidataApiClient.ApiEntity e = details.get(o.qid());
                if (e != null && !e.label().isBlank()
                        && o.getDisplayName().equals(o.qid())) {
                    o.name(e.label());
                }
            }
        } catch (Exception e) {
            if (log != null) {
                log.message("Qualifier-load ref label resolution failed ("
                        + e.getMessage() + ") — some refs keep their QID.\n");
            }
        }
    }

    // Fetch the value set (the instances of the value type, e.g. the Oscar
    // categories) so the reify can keep only statements whose value is one of them.
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

    // A time qualifier's ISO string reduces to its 4-digit year; a STRING/ENTITY
    // qualifier that is a bare year is handled leniently by the same regex.
    private static Integer parseYear(String s) {
        if (s == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(-?\\d{1,4})").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
