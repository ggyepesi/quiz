package wikidata.explore.extract;

import wikidata.explore.query.template.rule.RuleIncludedFieldSparql;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleEdge;
import wikidata.explore.rule.RuleNode;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.CommonsMedia;
import wikidata.explore.filter.WikidataValueFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Field-based extractor.
 *
 * User-visible fields remain fields. Internally:
 * - media/scalar fields -> normal included fields
 * - simple Entity/Object List fields -> GROUP_CONCAT in root query
 * - complex child edges -> batched child queries
 */
public class RuleTreeExtractor {
    // Not final: a shared registry can be injected so several class runs
    // (a whole-domain generation) pool into one graph — an entity referenced by
    // one class and generated as another's root is then a single, type-stamped
    // object, with references already linked.
    private final WikidataObjectRegistry registry;

    private static final int BATCH_SIZE = 50;
    private static final int POOL_SIZE  = 3;
    // Concurrent per-parent child queries within one edge (kept modest so we
    // don't hammer the SPARQL endpoint).
    private static final int EDGE_PARENT_POOL = 4;
    private static final String PIPE_REGEX = "\\|";
    private static final String PAIR_SEPARATOR = "§";

    private final WikidataSparqlClient client;
    private GenerationLog log = GenerationLog.NOOP;

    // Per-parent child queries that fail (timeout / error) are caught so one
    // parent doesn't abort the run — but they're COUNTED so a partial extraction
    // is surfaced loudly, not silent (a swallowed failure = missing children).
    private final java.util.concurrent.atomic.AtomicInteger childQueryFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Child (per-parent edge) queries that failed during this run — a non-zero
     *  count means the extracted pool is PARTIAL. */
    public int childQueryFailures() {
        return childQueryFailures.get();
    }

    public RuleTreeExtractor(WikidataSparqlClient client) {
        this(client, new WikidataObjectRegistry());
    }

    /** Shares {@code registry} across runs (whole-domain generation). */
    public RuleTreeExtractor(WikidataSparqlClient client, WikidataObjectRegistry registry) {
        this.client = client;
        this.registry = registry == null ? new WikidataObjectRegistry() : registry;
    }

    public void log(GenerationLog log) {
        this.log = log == null ? GenerationLog.NOOP : log;
    }

    public void log(Consumer<String> log) {
        this.log = GenerationLog.of(log);
    }

    public WikidataObjectRegistry registry() {
        return registry;
    }
    
    public static String valuesQuery(RuleNode node) {
        return RuleNodeQueryBuilder.valuesQuery(node);
    }

    public static String valuesQueryForSpecificParent(
            RuleNode node, String parentQid) {
        return RuleNodeQueryBuilder.valuesQueryForSpecificParent(node, parentQid);
    }

    public static String batchedValuesQuery(
            RuleNode node, List<String> parentQids) {
        return RuleNodeQueryBuilder.batchedValuesQuery(node, parentQids);
    }

    public static String fieldOptimizedValuesQuery(RuleNode node) {
        return RuleNodeQueryBuilder.fieldOptimizedValuesQuery(node);
    }

    public static List<RuleEdge> simpleEdges(RuleNode parent) {
        List<RuleEdge> simple = new ArrayList<>();
        for (RuleEdge edge : parent.edges()) {
            if (edge == null || edge.childNode() == null) continue;
            RuleNode child = edge.childNode();
            if (!child.edges().isEmpty()) continue;
            if (!child.includedFields().isEmpty()) continue; // has own fields -> full child objects
            if (!child.valueFilters().isEmpty()) continue;
            if (!child.excludedPredicateObjects().isEmpty()) continue;
            String pid = RuleNode.cleanPid(child.propertyPid());
            if (!pid.matches("P\\d+")) continue;
            simple.add(edge);
        }
        return simple;
    }

    public static List<RuleEdge> complexEdges(RuleNode parent) {
        Set<RuleEdge> simpleSet = new java.util.HashSet<>(simpleEdges(parent));
        List<RuleEdge> complex = new ArrayList<>();
        for (RuleEdge edge : parent.edges())
            if (edge != null && !simpleSet.contains(edge)) complex.add(edge);
        return complex;
    }

    public List<WikidataDynamicObject> load(
            RuleNode rootNode, int childDepth) throws Exception {
        return load(rootNode, childDepth, log);
    }

    public List<WikidataDynamicObject> load(
            RuleNode rootNode,
            int childDepth,
            GenerationLog progress) throws Exception {

        if (progress == null) progress = GenerationLog.NOOP;

        List<RuleIncludedField> inlinedFields =
                RuleNodeQueryBuilder.simpleInlinedFields(rootNode);
        List<RuleEdge> complex = complexEdges(rootNode);
        System.out.println("Included fields:");
        for (RuleIncludedField f : rootNode.includedFields()) {
            System.out.println(
                    "  " + f.fieldName()
                            + " pid=" + f.propertyPid()
                            + " kind=" + f.kind()
                            + " collection=" + f.collection()
                            + " inline=" + RuleNodeQueryBuilder.isInlineableEntityListField(f));
        }

        System.out.println("Inlined fields: " + inlinedFields.size());
        List<WikidataDynamicObject> roots;

        if (!inlinedFields.isEmpty()) {
            // Two-phase root load. Phase 1 — a LIGHT membership backbone: sampleCopy
            // keeps all membership semantics (multi-target QIDs, value filters,
            // sitelink, exclusions) but drops the included fields, so its query has
            // no GROUP_CONCAT and reliably returns the COMPLETE member set. Phase 2 —
            // the heavy field-optimized query enriches those same registry objects
            // (shared by qid) with the inlined fields in place.
            //
            // Splitting them makes the served roots deterministic: membership no
            // longer rides on the heavy query, which soft-times-out on WDQS and
            // returns a different partial row set each run (the 11076-vs-11142 drift).
            // Slice 1 — a large fixed multi-QID membership captures its "target"
            // field (= the membership roots) FROM the backbone join itself: the
            // batched membership query exposes the root, and the collected (member,
            // root) edges materialize the field. The heavy field-optimized
            // enrichment's target GROUP_CONCAT + label re-join over ~11k members
            // soft-times-out, so the captured field is then removed from it.
            boolean largeMembership =
                    rootNode.additionalSourceQids().size() > membershipTargetBatchSize;
            List<RuleIncludedField> membershipTargets = largeMembership
                    ? RuleNodeQueryBuilder.membershipTargetFields(rootNode)
                    : List.of();

            MembershipBackbone backbone =
                    runBackbone(rootNode, membershipTargets, progress);
            List<WikidataDynamicObject> members = backbone.members();
            if (!membershipTargets.isEmpty()) {
                materializeMembershipTargets(
                        members, membershipTargets, backbone.edges());
            }

            // Slice 2 (step 5) — the remaining inlined entity-list fields (those not
            // captured as targets) are fetched per MEMBER-batch, not inline-
            // GROUP_CONCAT'd over the whole class.
            List<RuleIncludedField> directEntityFields = new ArrayList<>();
            if (largeMembership) {
                for (RuleIncludedField f : inlinedFields) {
                    if (!membershipTargets.contains(f)) directEntityFields.add(f);
                }
                if (!directEntityFields.isEmpty()) {
                    captureMemberFields(members, directEntityFields, progress);
                }
            }

            // The enrichment runs over fields NOT handled by a specialized slice: a
            // copy of the node minus the target + member-batched fields. On a large
            // membership every inlined entity-list field is now specialized, so if no
            // inlined field remains the heavy query is not issued at all (step 7).
            List<RuleIncludedField> specialized = new ArrayList<>(membershipTargets);
            specialized.addAll(directEntityFields);
            RuleNode enrichNode = rootNode;
            List<RuleIncludedField> enrichFields = inlinedFields;
            if (!specialized.isEmpty()) {
                enrichNode = rootNode.sampleCopy(rootNode.limit());
                for (RuleIncludedField f : rootNode.includedFields()) {
                    if (!specialized.contains(f)) enrichNode.addIncludedField(f);
                }
                enrichFields = RuleNodeQueryBuilder.simpleInlinedFields(enrichNode);
            }

            List<WikidataDynamicObject> enriched;
            if (enrichFields.isEmpty()) {
                // Step 7: every requested inlined field was assigned to a slice, so
                // the heavy fieldOptimizedValuesQuery is skipped entirely.
                enriched = new ArrayList<>();
                // A large membership with leftover NON-inlined (scalar) fields would
                // need the heavy valuesQuery (the original timeout); those have no
                // member-batched path yet. No model class hits this today.
                if (largeMembership && enrichNode != rootNode
                        && !enrichNode.includedFields().isEmpty()) {
                    progress.message("WARNING: " + enrichNode.includedFields().size()
                            + " non-entity field(s) on a large membership have no "
                            + "member-batched path yet and were left unfilled.\n");
                }
            } else {
                final RuleNode qNode = enrichNode;
                final List<RuleIncludedField> qFields = enrichFields;
                String sparql = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(qNode);
                String title = "Root enrichment (+" + qFields.size()
                        + " inlined field" + (qFields.size() == 1 ? "" : "s") + ")";
                try {
                    enriched = runRootQuery(title, sparql, progress,
                            () -> runFieldOptimizedQuery(qNode, qFields, sparql));
                } catch (Exception e) {
                    // Best-effort: the backbone already holds the COMPLETE, type-stamped
                    // member set, so a failed enrichment must NOT fail the run — that is
                    // the "a partial enrichment never drops a member" guarantee the union
                    // below relies on. The heavy inline-GROUP_CONCAT query soft-times-out
                    // on WDQS (60s → HTTP 200 with a truncated body → JSON parse error),
                    // which used to abort the whole generation. Now the members survive;
                    // the inlined fields are just unfilled this pass.
                    if (e instanceof InterruptedException
                            || Thread.currentThread().isInterrupted()) {
                        throw e;   // a cancel is not a soft failure
                    }
                    progress.message("WARNING: root enrichment failed ("
                            + e.getMessage() + ") — members are COMPLETE from the "
                            + "backbone, but the " + qFields.size()
                            + " inlined field(s) are unfilled this run. Re-run to fill "
                            + "them (the enrichment query is over the WDQS timeout).\n");
                    enriched = new ArrayList<>();
                }
            }

            // Union by qid (both share registry instances): a member either query
            // found is a root, so a partial enrichment never drops a member.
            LinkedHashMap<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
            for (WikidataDynamicObject o : members) byQid.put(o.qid(), o);
            for (WikidataDynamicObject o : enriched) byQid.putIfAbsent(o.qid(), o);
            roots = new ArrayList<>(byQid.values());
        } else {
            String sparql = RuleNodeQueryBuilder.valuesQuery(rootNode);
            roots = runRootQuery("Root query", sparql, progress,
                    () -> runValueQuery(rootNode, sparql));
        }

        if (childDepth > 0 && !complex.isEmpty()) {
            loadComplexEdgesParallel(roots, rootNode, complex,
                    childDepth, progress);
        } else if (childDepth == 0 && !rootNode.edges().isEmpty()) {
            progress.message("Child depth is 0; child edges were not loaded.\n");
        }

        if (childQueryFailures.get() > 0) {
            progress.message("WARNING: " + childQueryFailures.get()
                    + " child query(ies) FAILED during \"" + rootNode.name()
                    + "\" extraction — the result is PARTIAL for those parents "
                    + "(not silently complete). Re-run if completeness matters.\n");
        }

        return roots;
    }

    public List<String> previewQueries(RuleNode rootNode, int childDepth) {
        List<String> queries = new ArrayList<>();

        List<RuleIncludedField> inlinedFields =
                RuleNodeQueryBuilder.simpleInlinedFields(rootNode);
        List<RuleEdge> complex = complexEdges(rootNode);

        // Mirror load(): a large fixed multi-QID membership captures its target
        // field from the membership backbone and drops it from the enrichment.
        boolean largeMembership =
                rootNode.additionalSourceQids().size() > membershipTargetBatchSize;
        List<RuleIncludedField> membershipTargets = largeMembership
                ? RuleNodeQueryBuilder.membershipTargetFields(rootNode)
                : List.of();

        if (largeMembership) {
            // Slice 1 — membership backbone (captures target field(s) when present).
            if (!membershipTargets.isEmpty()) {
                queries.add("# Membership backbone (batched by "
                        + membershipTargetBatchSize + " roots) capturing target field(s) "
                        + membershipTargets.stream().map(RuleIncludedField::fieldName)
                        .reduce((a, b) -> a + ", " + b).orElse("") + ":\n"
                        + RuleNodeQueryBuilder.membershipBackboneQuery(rootNode));
            } else {
                queries.add("# Membership backbone (batched by "
                        + membershipTargetBatchSize + " roots):\n"
                        + RuleNodeQueryBuilder.valuesQuery(rootNode));
            }

            // Slice 2 — each remaining inlined entity-list field, per member-batch.
            List<RuleIncludedField> directEntityFields = new ArrayList<>();
            for (RuleIncludedField f : inlinedFields) {
                if (!membershipTargets.contains(f)) directEntityFields.add(f);
            }
            for (RuleIncludedField f : directEntityFields) {
                queries.add("# Member field \"" + f.fieldName() + "\" (batched by "
                        + memberFieldBatchSize + " members):\n"
                        + RuleNodeQueryBuilder
                                .memberFieldBatchQuery(f, List.of("Q_MEMBER_BATCH"))
                                .replace("wd:Q_MEMBER_BATCH", "<member batch>"));
            }

            if (!inlinedFields.isEmpty()) {
                queries.add("# (fieldOptimizedValuesQuery skipped — every inlined "
                        + "entity-list field is slice-covered above.)");
            }
        } else if (!inlinedFields.isEmpty()) {
            queries.add("# Root + inlined entity-list fields: "
                    + inlinedFields.stream().map(RuleIncludedField::fieldName)
                    .reduce((a, b) -> a + ", " + b).orElse("")
                    + "\n"
                    + RuleNodeQueryBuilder.fieldOptimizedValuesQuery(rootNode));
        } else {
            queries.add(RuleNodeQueryBuilder.valuesQuery(rootNode));
        }

        if (childDepth > 0) {
            appendChildPreviewQueries(
                    queries, rootNode, complex, childDepth,
                    "?each_" + rootNode.itemVar());
        }

        return queries;
    }

    private void appendChildPreviewQueries(
            List<String> queries,
            RuleNode parentNode,
            List<RuleEdge> edges,
            int remainingDepth,
            String parentVar) {

        if (remainingDepth <= 0) return;
        for (RuleEdge edge : edges) {
            RuleNode child = edge.childNode();
            queries.add("# For each " + parentNode.name()
                    + " result, load complex edge \""
                    + edge.fieldName() + "\" (one query per parent, limit "
                    + child.limit() + "):\n"
                    + RuleNodeQueryBuilder.childQueryForParent(
                            child, "Q_PARENT", child.limit())
                            .replace("wd:Q_PARENT", "wd:<parent>"));
            appendChildPreviewQueries(
                    queries, child, complexEdges(child),
                    remainingDepth - 1, "?each_" + child.itemVar());
        }
    }

    @FunctionalInterface
    private interface RootQuery {
        List<WikidataDynamicObject> run() throws Exception;
    }

    // Runs the root query and records it in the log EITHER way: a successful
    // subquery with its row count, or a subqueryFailed carrying the SPARQL so a
    // timeout is debuggable from the UI (it used to log only on success, so a
    // timed-out root query left no query text in the log at all).
    // Batch sizes for the specialized extraction slices (see
    // docs/extraction-batched-membership.md). Configurable; defaults chosen to keep
    // each query well under the WDQS soft timeout.
    //   membershipTargetBatchSize — membership roots per backbone query. A big
    //     relational membership (e.g. P1411 → 58 Oscar categories) finds too many
    //     members to label in one query and soft-times-out; splitting the roots
    //     keeps each query small.
    //   memberFieldBatchSize — members per slice-2 direct-field row query.
    //   labelBatchSize — QIDs per slice-3 label-resolution batch.
    private int membershipTargetBatchSize = 10;
    private int memberFieldBatchSize = 250;
    private int labelBatchSize = 500;

    public RuleTreeExtractor membershipTargetBatchSize(int n) {
        if (n > 0) this.membershipTargetBatchSize = n;
        return this;
    }

    public RuleTreeExtractor memberFieldBatchSize(int n) {
        if (n > 0) this.memberFieldBatchSize = n;
        return this;
    }

    public RuleTreeExtractor labelBatchSize(int n) {
        if (n > 0) this.labelBatchSize = n;
        return this;
    }

    /**
     * The result of the membership backbone (design steps 1-3): the COMPLETE member
     * registry and, when a {@linkplain RuleNodeQueryBuilder#membershipTargetFields
     * target} field was captured, the membership-edge map (member qid →
     * insertion-ordered set of target qids). The edge map is empty when nothing was
     * captured.
     */
    private record MembershipBackbone(
            List<WikidataDynamicObject> members,
            Map<String, LinkedHashSet<String>> edges) {}

    /**
     * Phase-1 membership. For a large multi-target relational membership, split the
     * roots into batches and union the members by qid — each query stays well under
     * the WDQS timeout, and the union can't drop a member. When {@code targetFields}
     * is non-empty the batched query ALSO exposes the membership root (one query, no
     * second request), and every (member, root) edge is collected into the edge map
     * for the caller to materialize the target field from. A small membership runs
     * as a single query, unchanged (and never captures — it stays on the byte-
     * identical path).
     */
    private MembershipBackbone runBackbone(
            RuleNode rootNode,
            List<RuleIncludedField> targetFields,
            GenerationLog progress) throws Exception {

        List<String> targets = new ArrayList<>(rootNode.additionalSourceQids());
        if (targets.size() <= membershipTargetBatchSize) {
            RuleNode backbone = rootNode.sampleCopy(rootNode.limit());
            String sparql = RuleNodeQueryBuilder.valuesQuery(backbone);
            List<WikidataDynamicObject> members = runRootQuery(
                    "Root membership (backbone)", sparql, progress,
                    () -> runValueQuery(backbone, sparql));
            return new MembershipBackbone(members, new LinkedHashMap<>());
        }

        boolean capture = !targetFields.isEmpty();
        LinkedHashMap<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> edges = new LinkedHashMap<>();
        int total = (targets.size() + membershipTargetBatchSize - 1)
                / membershipTargetBatchSize;
        int n = 0;
        for (int from = 0; from < targets.size(); from += membershipTargetBatchSize) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            List<String> batch = new ArrayList<>(targets.subList(
                    from, Math.min(from + membershipTargetBatchSize, targets.size())));
            RuleNode backbone = rootNode.sampleCopy(rootNode.limit());
            backbone.additionalSourceQids().clear();
            batch.forEach(backbone::addAdditionalSourceQid);
            String sparql = capture
                    ? RuleNodeQueryBuilder.membershipBackboneQuery(backbone)
                    : RuleNodeQueryBuilder.valuesQuery(backbone);
            List<WikidataDynamicObject> part = runRootQuery(
                    "Root membership (backbone " + (++n) + "/" + total + ")",
                    sparql, progress,
                    () -> runMembershipBatch(backbone, sparql, capture, edges));
            for (WikidataDynamicObject o : part) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }
        return new MembershipBackbone(new ArrayList<>(byQid.values()), edges);
    }

    /**
     * Parses one membership backbone batch: each {@code ?value} becomes a registry
     * member (design step 3), and — when capturing — each {@code (?value, ?root)}
     * row adds an edge to {@code edges} (member qid → insertion-ordered set of
     * target qids). Returns this batch's members so the log shows a row count.
     */
    private List<WikidataDynamicObject> runMembershipBatch(
            RuleNode node, String sparql, boolean capture,
            Map<String, LinkedHashSet<String>> edges) throws Exception {

        Map<String, WikidataDynamicObject> rowsByQid = new LinkedHashMap<>();
        for (WikidataBinding b : client.query(sparql)) {
            String qid = b.qid("value");
            String label = b.label("value");
            if (qid == null || !qid.matches("Q\\d+")) continue;

            WikidataDynamicObject obj = rowsByQid.computeIfAbsent(
                    qid, k -> registry.getOrCreate(qid, label));
            obj.type(node.name());
            addValueFilterFields(obj, node, b);

            if (capture) {
                String targetQid = b.qid("root");
                if (targetQid != null && targetQid.matches("Q\\d+")) {
                    edges.computeIfAbsent(qid, k -> new LinkedHashSet<>())
                            .add(targetQid);
                }
            }
        }
        return new ArrayList<>(rowsByQid.values());
    }

    /**
     * Design step 4 — materialize the membership-target field(s) directly from the
     * edge map: each target qid resolves to its canonical registry object and is
     * merged onto the member (merge dedups + preserves the insertion order the
     * LinkedHashSet already fixed). Labels are placeholders (the qid) until the
     * shared label pass (slice 3) resolves them.
     */
    // Package-visible for RuleTreeExtractorMembershipTargetTest.
    void materializeMembershipTargets(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> targetFields,
            Map<String, LinkedHashSet<String>> edges) {

        for (WikidataDynamicObject member : members) {
            LinkedHashSet<String> targetQids = edges.get(member.qid());
            if (targetQids == null || targetQids.isEmpty()) continue;
            for (String targetQid : targetQids) {
                WikidataDynamicObject ref = registry.getOrCreate(targetQid, targetQid);
                for (RuleIncludedField tf : targetFields) {
                    member.merge(tf.fieldName(), ref);
                }
            }
        }
    }

    /**
     * Slice 2 (design step 5) — fetch each remaining multivalued direct entity field
     * with its own MEMBER-batched row query instead of an inline GROUP_CONCAT over
     * the whole class. Best-effort per batch: a failed batch is warned and skipped —
     * the members are already COMPLETE from the backbone, so a partial field capture
     * must never fail the run (it only leaves that field thinner).
     */
    private void captureMemberFields(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> fields,
            GenerationLog progress) throws Exception {

        List<String> memberQids = members.stream()
                .map(WikidataDynamicObject::qid)
                .filter(q -> q != null && q.matches("Q\\d+"))
                .toList();
        if (memberQids.isEmpty()) return;
        int total = (memberQids.size() + memberFieldBatchSize - 1) / memberFieldBatchSize;

        for (RuleIncludedField field : fields) {
            int n = 0;
            for (int from = 0; from < memberQids.size(); from += memberFieldBatchSize) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                List<String> batch = memberQids.subList(
                        from, Math.min(from + memberFieldBatchSize, memberQids.size()));
                String sparql =
                        RuleNodeQueryBuilder.memberFieldBatchQuery(field, batch);
                final int bn = ++n;
                try {
                    runRootQuery(
                            "Member field \"" + field.fieldName()
                                    + "\" (" + bn + "/" + total + ")",
                            sparql, progress,
                            () -> applyMemberField(field, sparql));
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    progress.message("WARNING: member field \"" + field.fieldName()
                            + "\" batch " + bn + "/" + total + " failed ("
                            + e.getMessage() + ") — members are COMPLETE; that "
                            + "batch's values are unfilled this run.\n");
                }
            }
        }
    }

    /**
     * Runs one member-field batch and merges each {@code (?value, ?fieldValue)} onto
     * the existing backbone member (registry-shared, canonical). merge is the final
     * duplicate guard (dedup + insertion order). Returns the members it touched so
     * the log shows a row count.
     */
    private List<WikidataDynamicObject> applyMemberField(
            RuleIncludedField field, String sparql) throws Exception {

        Map<String, WikidataDynamicObject> touched = new LinkedHashMap<>();
        for (WikidataBinding b : client.query(sparql)) {
            String valueQid = b.qid("value");
            String fieldValueQid = b.qid("fieldValue");
            if (valueQid == null || !valueQid.matches("Q\\d+")) continue;
            if (fieldValueQid == null || !fieldValueQid.matches("Q\\d+")) continue;
            WikidataDynamicObject member = registry.get(valueQid);
            if (member == null) continue;   // not a backbone member
            member.merge(field.fieldName(),
                    registry.getOrCreate(fieldValueQid, fieldValueQid));
            touched.put(valueQid, member);
        }
        return new ArrayList<>(touched.values());
    }

    private List<WikidataDynamicObject> runRootQuery(
            String title, String sparql, GenerationLog progress, RootQuery body)
            throws Exception {
        // Open the node BEFORE issuing, so the (often slow) root query shows in the
        // log while it runs instead of only once it returns.
        GenerationLog.Running running = progress.subqueryStarted(title, sparql);
        long start = System.currentTimeMillis();
        try {
            List<WikidataDynamicObject> roots = body.run();
            running.done(roots.size() + " objects");
            return roots;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            String msg = e.getMessage();
            running.failed(
                    (msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg)
                            + " (after " + ms + " ms)");
            throw e;
        }
    }

    private List<WikidataDynamicObject> runFieldOptimizedQuery(
            RuleNode node,
            List<RuleIncludedField> inlinedFields,
            String sparql) throws Exception {

        Map<String, WikidataDynamicObject> rowsByQid = new LinkedHashMap<>();

        for (WikidataBinding b : client.query(sparql)) {
            String qid   = b.qid("value");
            String label = b.label("value");
            if (qid == null || !qid.matches("Q\\d+")) continue;

            WikidataDynamicObject obj = rowsByQid.computeIfAbsent(
                    qid, k -> registry.getOrCreate(qid, label));
            obj.type(node.name()); // stamp the class so it renders as its type

            addValueFilterFields(obj, node, b);
            addIncludedFields(obj, node, b, inlinedFields);

            for (RuleIncludedField field : inlinedFields) {
                String concat = b.value(RuleNodeQueryBuilder.inlinedFieldAlias(field));
                if (concat == null || concat.isBlank()) continue;

                for (String token : concat.split(PIPE_REGEX)) {
                    token = token.trim();
                    if (token.isBlank()) continue;
                    obj.merge(field.fieldName(), parseInlinedEntityPair(token));
                }
            }
        }

        return new ArrayList<>(rowsByQid.values());
    }

    private Object parseInlinedEntityPair(String token) {
        String[] parts = token.split(PAIR_SEPARATOR, 2);
        String uriOrQid = parts.length > 0 ? parts[0].trim() : "";
        String label = parts.length > 1 ? parts[1].trim() : "";

        String childQid = entityQid(uriOrQid);
        if (childQid == null && uriOrQid.matches("Q\\d+")) {
            childQid = uriOrQid;
        }

        if (childQid != null) {
            return registry.getOrCreate(
                    childQid,
                    StringUtils.firstNonBlank(label, childQid));
        }

        return StringUtils.firstNonBlank(label, uriOrQid);
    }

    private void loadComplexEdgesParallel(
            List<WikidataDynamicObject> parentObjects,
            RuleNode parentNode,
            List<RuleEdge> edges,
            int remainingDepth,
            GenerationLog progress) throws Exception {

        if (edges.isEmpty() || parentObjects.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(POOL_SIZE, edges.size()));
        try {
            List<Future<EdgeResult>> futures = new ArrayList<>();
            for (RuleEdge edge : edges)
                futures.add(pool.submit(
                        () -> loadEdgeBatched(edge, parentObjects, progress)));

            for (Future<EdgeResult> future : futures) {
                EdgeResult result = future.get();
                applyEdgeResult(result, parentObjects);
                progress.message("  Edge \"" + result.edge.fieldName()
                        + "\" complete: " + result.totalLoaded
                        + " child objects\n");

                if (remainingDepth > 1 && !result.allChildren.isEmpty()) {
                    List<RuleEdge> grandchildComplex =
                            complexEdges(result.edge.childNode());
                    if (!grandchildComplex.isEmpty())
                        loadComplexEdgesParallel(
                                result.allChildren,
                                result.edge.childNode(),
                                grandchildComplex,
                                remainingDepth - 1,
                                progress);
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private EdgeResult loadEdgeBatched(
            RuleEdge edge,
            List<WikidataDynamicObject> parentObjects,
            GenerationLog progress) throws Exception {

        RuleNode childNode = edge.childNode();

        List<String> parentQids = new ArrayList<>();
        for (WikidataDynamicObject p : parentObjects) {
            if (p != null && p.qid() != null && p.qid().matches("Q\\d+"))
                parentQids.add(p.qid());
        }

        int limit = childNode.limit();

        progress.message("\nLoading edge \"" + edge.fieldName()
                + "\" for " + parentQids.size()
                + " parents (per-parent, limit " + limit + ")\n");

        // One query PER PARENT, each with its own LIMIT — the batched
        // multi-parent query can't express a per-parent limit and so pulls
        // every candidate (e.g. all magnitude-stars) and times out. Run them in
        // a small pool to stay friendly to the SPARQL endpoint.
        Map<String, Map<String, WikidataDynamicObject>> childrenByParent =
                new java.util.concurrent.ConcurrentHashMap<>();

        java.util.concurrent.atomic.AtomicInteger edgeFailures =
                new java.util.concurrent.atomic.AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(EDGE_PARENT_POOL, Math.max(1, parentQids.size())));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String parentQid : parentQids) {
                futures.add(pool.submit(() -> {
                    String sparql = RuleNodeQueryBuilder.childQueryForParent(
                            childNode, parentQid, limit);
                    long t0 = System.currentTimeMillis();
                    String title = edge.fieldName() + " <- " + parentQid;
                    // Show the per-parent query while it runs (parents run in
                    // parallel, so the recorder mutation is serialised on progress).
                    GenerationLog.Running running;
                    synchronized (progress) {
                        running = progress.subqueryStarted(title, sparql);
                    }
                    try {
                        Map<String, WikidataDynamicObject> kids;
                        String note = "";
                        try {
                            kids = queryParentChildren(childNode, sparql);
                        } catch (InterruptedException ie) {
                            throw ie;
                        } catch (Exception firstAttempt) {
                            // One retry for a transient hiccup (the client already
                            // retries its classified-transient failures; this covers
                            // the rest) before giving up on this parent.
                            Thread.sleep(500);
                            kids = queryParentChildren(childNode, sparql);
                            note = " (retry)";
                        }
                        if (!kids.isEmpty()) childrenByParent.put(parentQid, kids);
                        // One structured, collapsible sub-query entry per parent (a
                        // single runnable SELECT). Parents run in parallel, so the
                        // recorder add is serialised here.
                        long ms = System.currentTimeMillis() - t0;
                        synchronized (progress) {
                            running.done(kids.size() + note + " (" + ms + " ms)");
                        }
                    } catch (InterruptedException ie) {
                        // A real cancellation (the user stopped the run) must abort,
                        // not be swallowed as a per-parent failure.
                        Thread.currentThread().interrupt();
                        throw ie;
                    } catch (Exception ex) {
                        // One parent timing out / failing must NOT abort the whole
                        // domain run (and must not leave the other parents firing as
                        // orphaned queries). Record it as a FAILED child step under
                        // the same log entry, COUNT it (so the partial result is
                        // surfaced, not silent), then continue with the rest.
                        edgeFailures.incrementAndGet();
                        childQueryFailures.incrementAndGet();
                        long ms = System.currentTimeMillis() - t0;
                        synchronized (progress) {
                            running.failed(ex.getMessage() + " (" + ms + " ms)");
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }

        int failed = edgeFailures.get();
        if (failed > 0) {
            progress.message("WARNING: " + failed + " of " + parentQids.size()
                    + " \"" + edge.fieldName() + "\" child queries FAILED — the pool "
                    + "is INCOMPLETE for those parents (they contribute no children). "
                    + "Re-run, reduce depth, or narrow the field.\n");
        }

        return new EdgeResult(edge, childrenByParent);
    }

    /** Runs one parent's child query and collects the children (stamped + fields).
     *  Extracted so the caller can retry it once on a transient failure. */
    private Map<String, WikidataDynamicObject> queryParentChildren(
            RuleNode childNode, String sparql) throws Exception {
        Map<String, WikidataDynamicObject> kids = new LinkedHashMap<>();
        for (WikidataBinding b : client.query(sparql)) {
            String childQid  = b.qid("value");
            String childLabel = b.label("value");
            if (childQid == null || !childQid.matches("Q\\d+")) {
                continue;
            }
            WikidataDynamicObject child = kids.computeIfAbsent(
                    childQid, k -> registry.getOrCreate(childQid, childLabel));
            child.type(childNode.name());   // stamp the child class (e.g. "Star")
            addValueFilterFields(child, childNode, b);
            addIncludedFields(child, childNode, b);
        }
        return kids;
    }

    private void applyEdgeResult(
            EdgeResult result,
            List<WikidataDynamicObject> parentObjects) {

        RuleNode childNode = result.edge.childNode();

        for (WikidataDynamicObject parent : parentObjects) {
            Map<String, WikidataDynamicObject> childMap =
                    result.childrenByParent.getOrDefault(
                            parent.qid(), Map.of());

            List<WikidataDynamicObject> children =
                    new ArrayList<>(childMap.values());

            List<WikidataDynamicObject> trimmed =
                    trimChildren(children, childNode.limit());

            if (result.edge.collection()) {
                for (WikidataDynamicObject child : trimmed)
                    parent.merge(result.edge.fieldName(), child);
            } else {
                parent.merge(result.edge.fieldName(),
                        trimmed.isEmpty() ? null : trimmed.get(0));
            }

            result.allChildren.addAll(trimmed);
        }
    }

    private static class EdgeResult {
        final RuleEdge edge;
        final Map<String, Map<String, WikidataDynamicObject>> childrenByParent;
        final List<WikidataDynamicObject> allChildren = new ArrayList<>();
        int totalLoaded;

        EdgeResult(RuleEdge edge,
                   Map<String, Map<String, WikidataDynamicObject>> childrenByParent) {
            this.edge = edge;
            this.childrenByParent = childrenByParent;
            for (Map<String, WikidataDynamicObject> m : childrenByParent.values())
                totalLoaded += m.size();
        }
    }

    private List<WikidataDynamicObject> runValueQuery(
            RuleNode node, String sparql) throws Exception {

        Map<String, WikidataDynamicObject> rowsByQid = new LinkedHashMap<>();

        for (WikidataBinding b : client.query(sparql)) {
            String qid   = b.qid("value");
            String label = b.label("value");
            if (qid == null || !qid.matches("Q\\d+")) continue;

            WikidataDynamicObject obj = rowsByQid.computeIfAbsent(
                    qid, k -> registry.getOrCreate(qid, label));
            obj.type(node.name());

            addValueFilterFields(obj, node, b);
            addIncludedFields(obj, node, b);
        }

        return new ArrayList<>(rowsByQid.values());
    }

    private void addValueFilterFields(
            WikidataDynamicObject obj, RuleNode node, WikidataBinding b) {
        if (node == null || node.valueFilters().isEmpty()) return;
        for (WikidataValueFilter filter : node.valueFilters()) {
            if (filter == null) continue;
            String var   = filter.variableName();
            String value = b.value(var);
            if (value != null && !value.isBlank()) obj.merge(var, value);
        }
    }

    private void addIncludedFields(
            WikidataDynamicObject obj, RuleNode node, WikidataBinding b) {
        addIncludedFields(obj, node, b, List.of());
    }

    private void addIncludedFields(
            WikidataDynamicObject obj,
            RuleNode node,
            WikidataBinding b,
            List<RuleIncludedField> skipFields) {

        if (node == null || node.includedFields().isEmpty()) return;
        int index = 0;
        for (RuleIncludedField field : node.includedFields()) {
            if (field == null) { index++; continue; }
            if (skipFields.contains(field)) { index++; continue; }

            String var   = RuleIncludedFieldSparql.variableName(field, index);
            String raw   = b.value(var);
            String label = b.value(var + "Label");
            if (raw != null && !raw.isBlank())
                obj.merge(field.fieldName(), toFieldValue(field, raw, label));
            index++;
        }
    }

    private Object toFieldValue(
            RuleIncludedField field, String raw, String label) {
        if (field.isMediaField()) {
            String src   = StringUtils.firstNonBlank(raw, label);
            String mLabel = CommonsMedia.fileName(src);
            String mUrl   = CommonsMedia.filePathUrl(src);
            return new WikidataMediaValue(mLabel, mUrl,
                    CommonsMedia.isSvg(mLabel));
        }
        String qid = entityQid(raw);
        if (qid != null)
            return registry.getOrCreate(
                    qid, StringUtils.firstNonBlank(label, qid));
        // A time literal ([+-]YYYY-MM-DDThh:mm:ssZ) becomes a typed date, at the
        // precision the literal's conventional padding implies — not a raw string.
        aux.FlexibleDate date = aux.FlexibleDate.fromWikidataLiteral(raw);
        if (date == null) {
            date = aux.FlexibleDate.fromWikidataLiteral(label);
        }
        if (date != null) {
            return date;
        }
        return StringUtils.firstNonBlank(label, raw);
    }

    private static String entityQid(String value) {
        if (value == null) return null;
        int idx = value.lastIndexOf('/');
        String tail = idx >= 0 ? value.substring(idx + 1) : value;
        return tail.matches("Q\\d+") ? tail : null;
    }

    private static List<WikidataDynamicObject> trimChildren(
            List<WikidataDynamicObject> children, int limit) {
        if (children == null || children.isEmpty()) return List.of();
        int max = Math.max(1, limit);
        return children.size() <= max
                ? children
                : new ArrayList<>(children.subList(0, max));
    }
}
