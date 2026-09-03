package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.api.FactDemand;
import wikidata.api.FactDemandBinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private boolean deferLabels;
    private int discoveryLimit;
    private StatementFactDemands factDemands = StatementFactDemands.EMPTY;

    /** Override the action-API client (share one / inject a stub for tests). */
    public QualifierLoader api(WikidataApiClient api) {
        this.api = api;
        return this;
    }

    public QualifierLoader deferLabels(boolean defer) {
        deferLabels = defer;
        return this;
    }

    /** Bounds only reverse subject discovery for inspection. Zero means production's
     * complete population, preserving generation semantics. */
    public QualifierLoader discoveryLimit(int limit) {
        discoveryLimit = Math.max(0, limit);
        return this;
    }

    public QualifierLoader factDemands(StatementFactDemands demands) {
        factDemands = demands == null ? StatementFactDemands.EMPTY : demands;
        return this;
    }

    private WikidataApiClient api() {
        if (api == null) {
            api = new WikidataApiClient(WikidataApiClient.DEFAULT_USER_AGENT);
        }
        return api;
    }

    public List<WikidataDynamicObject> enrich(
            Collection<WikidataDynamicObject> pool,
            QualifierLoadConfig cfg,
            WikidataSparqlClient client,
            GenerationLog log) {

        List<WikidataDynamicObject> created = new ArrayList<>();
        // Calendar models this build cannot translate, per qualifier field, for
        // this load only — never process-wide state that outlives the run.
        Map<String, java.util.Set<String>> untranslated = new java.util.TreeMap<>();
        if (pool == null || cfg == null || !cfg.valid()) {
            return created;
        }

        // Index the entityType pool (whose statements we reify) and the WHOLE pool
        // (so a value/qualifier ref reuses an already-labelled pool object by qid).
        Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
        Map<String, WikidataDynamicObject> poolByQid = new LinkedHashMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o == null || o.qid() == null || !WikidataIds.isQid(o.qid())) {
                continue;
            }
            poolByQid.putIfAbsent(o.qid(), o);
            if (cfg.entityType().equals(o.typeName())) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }

        String valueField = blankTo(cfg.valueField(), "value");
        String stmtType = blankTo(cfg.statementType(), cfg.statementField());
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;

        // The allowed value set, read from ONE bound. This used to be an if/else over
        // two independent fields, so a model that set both had its type filter silently
        // ignored — a precedence nobody wrote down, applied at query time. The choice is
        // now made once at compile, and here there is nothing left to rank: an end is
        // bounded one way or it is not bounded at all (null = accept every value).
        Set<String> allowedValues = switch (cfg.objectBound().kind()) {
            case EXPLICIT -> new HashSet<>(cfg.objectBound().qids());
            case RELATION -> client == null ? null : new HashSet<>(
                    fetchValueQids(client, cfg.objectBound().qids().get(0), log));
            case UNBOUNDED -> null;
        };

        // Discovery and materialization are separate graph operations. An explicit
        // vocabulary normally supplies both sets; a seeded target class can supply
        // only the reverse-traversal seeds while leaving forward statements open.
        Set<String> discoveryValues = cfg.hasDiscoveryValueQids()
                ? new HashSet<>(cfg.discoveryValueQids())
                : allowedValues;

        // POPULATION subjects: with no source-class members in the pool, discover the
        // entities that carry the statement property into the value domain, stamp
        // them the load type, and index them — before the empty-pool bail. Guarded by
        // the value set (no unbounded membership scan).
        if (cfg.discoverSubjects()
                && (discoveryValues != null || cfg.subjectBound().bounded())) {
            List<WikidataDynamicObject> discovered =
                    new PopulationSubjectLoader().discover(
                            pool, cfg.propertyPid(), discoveryValues,
                            cfg.subjectBound(),
                            cfg.entityType(), cfg.valueDomainLabel(), client, log,
                            discoveryLimit);

            // SPARQL discovery yields QIDs only. Acquire the statement property, the
            // prospective role closure, and exactly the entity metadata those later
            // roles declare. Leaving metadata out here makes the fact store correctly
            // refuse the claims-only document later and downloads the same population
            // again merely to add aliases.
            List<String> newQids = new ArrayList<>();
            for (WikidataDynamicObject s : discovered) {
                if (s != null && s.qid() != null && WikidataIds.isQid(s.qid())) {
                    newQids.add(s.qid());
                }
            }
            if (!newQids.isEmpty()) {
                try {
                    api().facts().recordDemand(
                            "statement acquisition " + cfg.statementType(),
                            newQids, List.of(cfg.propertyPid()));
                    FactDemandBinder.bind(FactDemand.of(
                                    "statement acquisition", cfg.entityType(),
                                    List.of(cfg.propertyPid()),
                                    "load statements and qualifiers"),
                            newQids, api().facts(), "discovered statement subjects");
                    FactDemandBinder.Binding roleBinding = FactDemandBinder.bind(
                            factDemands.subjectDemands(), newQids, api().facts(),
                            "statement-subject role propagation");
                    LinkedHashSet<String> firstRequestPids = new LinkedHashSet<>();
                    firstRequestPids.add(cfg.propertyPid());
                    factDemands.subjectDemands().forEach(d ->
                            firstRequestPids.addAll(d.propertyPids()));
                    java.util.EnumSet<FactDemand.EntityMetadata> firstRequestMetadata =
                            java.util.EnumSet.of(FactDemand.EntityMetadata.LABEL);
                    factDemands.subjectDemands().forEach(d ->
                            firstRequestMetadata.addAll(d.metadata()));
                    Map<String, WikidataApiClient.ApiEntity> details;
                    try (GenerationLog.Group acquisition = sink.group(
                            "Acquire discovered " + cfg.entityType() + " subjects ("
                                    + newQids.size() + " entities, "
                                    + firstRequestPids.size() + " properties)")) {
                        details = api().getEntities(newQids,
                                new ArrayList<>(firstRequestPids), firstRequestMetadata,
                                acquisition.batchSink());
                    }
                    if (log != null && (roleBinding.claimPairs() > 0
                            || roleBinding.metadataPairs() > 0)) {
                        log.message("Statement-subject propagation: retained "
                                + roleBinding.claimPairs() + " QID/property pair(s) for "
                                + roleBinding.consumers() + " downstream role consumer(s); "
                                + roleBinding.metadataPairs()
                                + " QID/metadata pair(s).\n");
                    }
                    for (WikidataDynamicObject s : discovered) {
                        WikidataApiClient.ApiEntity e = details.get(s.qid());
                        if (e != null && e.label() != null && !e.label().isBlank()) {
                            s.name(e.label());
                        }
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                    }
                    // best effort: a subject keeps its QID label
                }
            }

            for (WikidataDynamicObject s : discovered) {
                if (s != null && s.qid() != null && WikidataIds.isQid(s.qid())) {
                    pool.add(s);   // a newly-discovered subject joins the shared pool
                    byQid.putIfAbsent(s.qid(), s);
                    poolByQid.putIfAbsent(s.qid(), s);
                }
            }
        }

        if (byQid.isEmpty()) {
            return created;
        }

        List<String> qualifierPids = new ArrayList<>();
        if (cfg.qualifiers() != null) {
            for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
                if (q != null && q.pid() != null && WikidataIds.isPid(q.pid())) {
                    qualifierPids.add(q.pid());
                }
            }
        }

        Map<String, WikidataDynamicObject> refCache = new LinkedHashMap<>();

        try (GenerationLog.Group g = sink.group("Qualifier load " + cfg.entityType()
                + " " + cfg.propertyPid() + " (" + byQid.size() + " entities)")) {

            Map<String, List<WikidataApiClient.ApiStatement>> statements;
            try {
                api().facts().recordDemand(
                        "statement acquisition " + cfg.statementType(),
                        byQid.keySet(), List.of(cfg.propertyPid()));
                statements = api().getStatements(new ArrayList<>(byQid.keySet()),
                        cfg.propertyPid(), qualifierPids, g.batchSink());
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return created;
                }
                g.message("WARNING: qualifier load via wbgetentities failed ("
                        + e.getMessage() + ") — generation cannot safely continue.\n");
                throw new IllegalStateException(
                        "Required qualifier statements could not be loaded completely", e);
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
                    applyQualifiers(stmt, s, cfg, poolByQid, refCache,
                            untranslated);
                    entity.merge(cfg.statementField(), stmt);
                    created.add(stmt);
                }
            }

            if (!untranslated.isEmpty()) {
                untranslated.forEach((field, models) -> g.message(
                        "WARNING: " + field + " kept " + models.size()
                                + " value(s) untyped — calendar model(s) "
                                + String.join(", ", models)
                                + " cannot be translated, and reading them as "
                                + "Gregorian would misdate exactly the values "
                                + "unusual enough to state one.\n"));
            }

            bindFieldPopulations(created, cfg, api().facts(), sink);

            // Name the value/qualifier refs that weren't already pooled+labelled,
            // then let each statement's name follow its (now-labelled) value.
            if (!deferLabels) resolveRefLabels(refCache, g);
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

    private void bindFieldPopulations(
            List<WikidataDynamicObject> statements,
            QualifierLoadConfig cfg,
            wikidata.api.WikidataFactStore facts,
            GenerationLog log) {
        if (statements == null || statements.isEmpty()) return;
        for (Map.Entry<String, List<FactDemand>> route : factDemands.fieldDemands().entrySet()) {
            LinkedHashSet<String> qids = new LinkedHashSet<>();
            for (WikidataDynamicObject statement : statements) {
                collectQids(statement.get(route.getKey()), qids);
            }
            FactDemandBinder.Binding binding = FactDemandBinder.bind(
                    route.getValue(), qids, facts,
                    "statement field " + cfg.statementType() + "." + route.getKey());
            if (binding.claimPairs() > 0 && log != null) {
                log.message("Statement-field propagation " + cfg.statementType() + "."
                        + route.getKey() + ": " + binding.entities() + " QID(s), "
                        + binding.claimPairs() + " planned property pair(s).\n");
            }
        }
    }

    private static void collectQids(Object value, Set<String> out) {
        if (value instanceof WikidataDynamicObject object) {
            if (WikidataIds.isQid(object.qid())) out.add(object.qid());
        } else if (value instanceof Collection<?> values) {
            values.forEach(item -> collectQids(item, out));
        }
    }

    /** Apply each configured qualifier from the statement onto the reified object,
     *  per its kind: ENTITY -> a (pool-unified) ref, YEAR -> a FlexibleDate parsed
     *  from the ISO time, STRING -> the raw literal. A {@code multi} qualifier keeps
     *  every value (e.g. co-nominees); a single one keeps the first. */
    private static void applyQualifiers(
            WikidataDynamicObject stmt, WikidataApiClient.ApiStatement s,
            QualifierLoadConfig cfg,
            Map<String, WikidataDynamicObject> poolByQid,
            Map<String, WikidataDynamicObject> refCache,
            Map<String, java.util.Set<String>> untranslated) {

        if (cfg.qualifiers() == null) {
            return;
        }
        for (QualifierLoadConfig.Qualifier q : cfg.qualifiers()) {
            if (q == null || q.pid() == null || !WikidataIds.isPid(q.pid())) {
                continue;
            }
            List<String> vals = s.qualifier(q.pid());
            if (vals.isEmpty()) {
                continue;
            }
            switch (q.kind() == null ? QualifierLoadConfig.Kind.STRING : q.kind()) {
                case ENTITY -> {
                    for (String vq : vals) {
                        if (!WikidataIds.isQid(vq)) {
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
                    for (String value : vals) {
                        Integer year = parseYear(value);
                        if (year != null) {
                            putQualifier(stmt, q, new aux.FlexibleDate(year));
                            if (!q.multi()) break;
                        }
                    }
                }
                case DATE -> {
                    // The literal is read whole rather than mined for a year, so the
                    // precision it states survives — and so does its calendar, which
                    // the API attached to the value and only this parser reads back.
                    for (String value : vals) {
                        // An untranslatable calendar drops THAT value, not the
                        // statement: the rest of the reified record is still true.
                        aux.FlexibleDate date =
                                wikidata.CalendarModelCodec.readTimeReporting(
                                        value, model -> untranslated
                                                .computeIfAbsent(q.fieldName(),
                                                        k -> new java.util.TreeSet<>())
                                                .add(model));
                        if (date != null) {
                            putQualifier(stmt, q, date);
                            if (!q.multi()) break;
                        }
                    }
                }
                case STRING -> {
                    // Exact-language values win; untagged is a fallback only when
                    // the requested language has no answer. The shared codec also
                    // strips transport metadata from ordinary literal fields.
                    for (String text : wikidata.MonolingualTextCodec.select(
                            vals, q.language())) {
                        putQualifier(stmt, q, text);
                        if (!q.multi()) break;
                    }
                }
            }
        }
    }

    private static void putQualifier(
            WikidataDynamicObject statement,
            QualifierLoadConfig.Qualifier qualifier,
            Object value) {
        if (qualifier.multi()) {
            statement.merge(qualifier.fieldName(), value);
        } else {
            statement.put(qualifier.fieldName(), value);
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
                            log == null ? null : log.batchSink());
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
                if (qid != null && WikidataIds.isQid(qid)) {
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
