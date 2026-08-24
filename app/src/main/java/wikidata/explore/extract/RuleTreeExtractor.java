package wikidata.explore.extract;

import wikidata.WikidataIds;

import wikidata.explore.extract.WikidataDynamicObject;

import wikidata.explore.query.template.rule.RuleIncludedFieldSparql;
import wikidata.api.WikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleEdge;
import wikidata.explore.rule.RuleNode;
import wikidata.api.FactDemand;
import wikidata.api.FactDemandBinder;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.CommonsMedia;
import wikidata.explore.filter.WikidataValueFilter;

import java.util.ArrayList;
import java.util.Iterator;
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
    // Where the open top band starts. Bands below it halve down to zero, so this only
    // sets how finely the notable tail is cut, not what is covered.
    private static final long TOP_RANK_BAND = 64;
    private static final int POOL_SIZE  = 3;
    // Concurrent per-parent child queries within one edge (kept modest so we
    // don't hammer the SPARQL endpoint).
    private static final int EDGE_PARENT_POOL = 4;
    private static final String PIPE_REGEX = "\\|";
    private static final String PAIR_SEPARATOR = "§";

    private final WikidataSparqlClient client;
    private GenerationLog log = GenerationLog.NOOP;

    // How the batched stages retry and narrow. Run-scoped, never part of the model:
    // with correct partitioning the result is identical whatever the batch size.
    private batch.BatchPolicy batchPolicy = batch.BatchPolicy.defaults().withResume(false);
    private work.CancellationToken cancellation = new work.CancellationToken();

    public RuleTreeExtractor batchPolicy(batch.BatchPolicy policy) {
        this.batchPolicy = policy == null
                ? batch.BatchPolicy.defaults().withResume(false) : policy;
        return this;
    }

    /** Binds every adaptive batch stage to the owning process cancellation token. */
    public RuleTreeExtractor cancellation(work.CancellationToken token) {
        this.cancellation = token == null ? new work.CancellationToken() : token;
        return this;
    }

    // The action-API client for wbgetentities (labels + outgoing entity claims) —
    // the reliable, non-SPARQL path. Lazily created with the project user agent;
    // override via api(...) to share one / inject logging.
    private WikidataApiClient api;
    private boolean deferLabels;
    private List<FactDemand> factDemands = List.of();

    public RuleTreeExtractor api(WikidataApiClient api) {
        this.api = api;
        return this;
    }

    /** Whole-domain generation hydrates labels once after semantic closure. */
    public RuleTreeExtractor deferLabels(boolean defer) {
        this.deferLabels = defer;
        return this;
    }

    /** Prospective downstream claims to retain with this root population's fetch. */
    public RuleTreeExtractor factDemands(List<FactDemand> demands) {
        this.factDemands = demands == null ? List.of() : List.copyOf(demands);
        return this;
    }

    private WikidataApiClient api() {
        if (api == null) {
            api = new WikidataApiClient("QuizProject/1.0 (ggyepesi@gmail.com)");
        }
        return api;
    }

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
            if (!WikidataIds.isPid(pid)) continue;
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
            // Stage 1 – Membership backbone.
            //
            // Logical operation:
            //   Discover the complete set of root members.
            //
            // Physical execution:
            //   sampleCopy keeps every membership constraint (multi-target QIDs,
            //   value filters, sitelink requirements and exclusions) but removes
            //   included fields. The resulting label-free backbone has no
            //   GROUP_CONCAT and can therefore return the complete member set
            //   reliably.
            //
            // For a large fixed multi-root membership, a membership target field
            // can be captured directly from the same backbone join. The query
            // exposes both ?value and ?root, producing membership edges
            // (member -> membership root). Those edges are materialized as fields
            // without repeating the expensive relation in a later enrichment query.
            //
            // Keeping membership discovery separate from field capture makes the
            // served roots deterministic without confusing a failed capture with a
            // genuinely missing value. Any field-capture failure aborts generation.
            boolean largeMembership =
                    rootNode.additionalSourceQids().size() > membershipTargetBatchSize;
            List<RuleIncludedField> membershipTargets = largeMembership
                    ? RuleNodeQueryBuilder.membershipTargetFields(rootNode)
                    : List.of();

            MembershipBackbone backbone =
                    runBackbone(rootNode, membershipTargets, progress);
            List<WikidataDynamicObject> members = backbone.members();
            progress.message("wbgetentities will fetch for " + members.size()
                                     + " member entities (plus any QID-only label refs).\n");
            if (!membershipTargets.isEmpty()) {
                materializeMembershipTargets(
                        members, membershipTargets, backbone.edges());
            }

            // Stage 2 – Member field capture.
            //
            // Populate the remaining direct fields over the already discovered
            // members. OUTGOING fields are the member's own claims (QIDs, literals
            // and media alike), so they are fetched together through wbgetentities;
            // querying common outgoing properties through WDQS can trigger full scans.
            //
            // INCOMING fields have no corresponding claim on the member itself, so
            // they remain on the member-batched SPARQL path.
            List<RuleIncludedField> directFields = new ArrayList<>();
            for (RuleIncludedField f : rootNode.includedFields()) {
                if (!membershipTargets.contains(f)) directFields.add(f);
            }
            List<RuleIncludedField> outgoing = new ArrayList<>();
            List<RuleIncludedField> incoming = new ArrayList<>();
            for (RuleIncludedField f : directFields) {
                if (f.direction() == RuleDirection.ROOT_TO_ITEM) {
                    requireApiProperty(rootNode, f);
                    outgoing.add(f);
                }
                else if (inlinedFields.contains(f)) incoming.add(f);
            }
            if (!outgoing.isEmpty() || !factDemands.isEmpty()) {
                captureOutgoingFieldsViaApi(members, outgoing, progress);
            }
            if (!incoming.isEmpty()) {
                captureMemberFields(members, incoming, progress);
            }

            // Residual enrichment handles only fields not covered by Stage 1 or
            // Stage 2. It runs against a copy of the node with membership-target and
            // member-captured fields removed. If no inlined field remains, the heavy
            // field-optimized query is skipped entirely.
            List<RuleIncludedField> specialized = new ArrayList<>(membershipTargets);
            specialized.addAll(outgoing);
            specialized.addAll(incoming);
            RuleNode enrichNode = rootNode;
            List<RuleIncludedField> enrichFields = inlinedFields;
            if (!specialized.isEmpty()) {
                enrichNode = rootNode.sampleCopy(rootNode.limit());
                for (RuleIncludedField f : rootNode.includedFields()) {
                    if (!specialized.contains(f)) enrichNode.addIncludedField(f);
                }
                enrichFields = RuleNodeQueryBuilder.simpleInlinedFields(enrichNode);
            }

            // Stage 2 captures EVERY entity-list field, so no inlined field can
            // reach the residual: the heavy fieldOptimizedValuesQuery — the shape
            // that soft-timed-out on a large membership — is unreachable by
            // construction and no longer called here.
            if (!enrichFields.isEmpty()) {
                throw new IllegalStateException(
                        "entity-list field(s) " + enrichFields.stream()
                                .map(RuleIncludedField::fieldName).toList()
                                + " reached the residual query; Stage 2 must capture them");
            }

            // Scalar/media fields still need a query, but bounded by the members the
            // backbone already found rather than re-scanning the class.
            List<WikidataDynamicObject> enriched =
                    captureResidualFields(enrichNode, rootNode, members, progress);

            // Union by qid (both paths share registry instances). Enrichment errors
            // propagate before this point, so an incomplete field set is never
            // published as a successful result.
            LinkedHashMap<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();
            for (WikidataDynamicObject o : members) byQid.put(o.qid(), o);
            for (WikidataDynamicObject o : enriched) byQid.putIfAbsent(o.qid(), o);
            roots = new ArrayList<>(byQid.values());
        } else {
            String sparql = RuleNodeQueryBuilder.valuesQuery(rootNode);
            roots = runRootQuery("Root query", sparql, progress,
                                 () -> runValueQuery(rootNode, sparql));
            if (!factDemands.isEmpty()) {
                captureOutgoingFieldsViaApi(roots, List.of(), progress);
            }
        }

        // Stage 4 – Recursive child extraction.
        //
        // Complex child edges are loaded only after the root membership and root
        // fields are stable. Each child load reuses the shared registry, so repeated
        // references resolve to the same canonical object.
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

        // Stage 3 – Label resolution.
        //
        // Resolve names for QID-only references created by Stage 1 and Stage 2
        // through one shared wbgetentities pass. This is a no-op when every registry
        // object already has a real label.
        if (!deferLabels) resolveLabels(progress);

        return roots;
    }

    public List<String> previewQueries(RuleNode rootNode, int childDepth) {
        List<String> queries = new ArrayList<>();

        List<RuleIncludedField> inlinedFields =
                RuleNodeQueryBuilder.simpleInlinedFields(rootNode);
        List<RuleEdge> complex = complexEdges(rootNode);

        // Mirror load(): every direct entity-list field is captured over the bounded
        // member set; a large fixed multi-QID membership can additionally capture
        // an equivalent target field from the backbone itself.
        boolean largeMembership =
                rootNode.additionalSourceQids().size() > membershipTargetBatchSize;
        List<RuleIncludedField> membershipTargets = largeMembership
                ? RuleNodeQueryBuilder.membershipTargetFields(rootNode)
                : List.of();

        if (!inlinedFields.isEmpty() && largeMembership) {
            // Stage 1 – show the actual physical membership queries that load()
            // executes. Previewing the original node would misleadingly display one
            // large VALUES query even though runBackbone() partitions the roots.
            List<String> targets = new ArrayList<>(rootNode.additionalSourceQids());
            int totalBatches = (targets.size() + membershipTargetBatchSize - 1)
                    / membershipTargetBatchSize;
            int batchNumber = 0;
            for (int from = 0; from < targets.size(); from += membershipTargetBatchSize) {
                List<String> batch = new ArrayList<>(targets.subList(
                        from,
                        Math.min(from + membershipTargetBatchSize, targets.size())));
                RuleNode backbone = rootNode.backboneCopy(rootNode.limit());
                backbone.additionalSourceQids().clear();
                batch.forEach(backbone::addAdditionalSourceQid);

                boolean capture = !membershipTargets.isEmpty();
                String sparql = capture
                        ? RuleNodeQueryBuilder.membershipBackboneQuery(backbone)
                        : RuleNodeQueryBuilder.membershipBackboneQueryNoLabel(backbone);
                String captured = capture
                        ? " capturing target field(s) "
                          + membershipTargets.stream()
                                             .map(RuleIncludedField::fieldName)
                                             .reduce((a, b) -> a + ", " + b)
                                             .orElse("")
                        : "";
                queries.add("# Membership backbone batch " + (++batchNumber)
                                    + "/" + totalBatches + " (" + batch.size() + " root"
                                    + (batch.size() == 1 ? "" : "s") + ")"
                                    + captured + ":\n" + sparql);
            }

        } else if (!inlinedFields.isEmpty()) {
            // Single-root class: Stage 1 is the membership backbone alone — there is
            // no membership-target field to capture from it.
            RuleNode backbone = rootNode.backboneCopy(rootNode.limit());
            queries.add("# Root membership (backbone):\n"
                                + RuleNodeQueryBuilder.valuesQuery(backbone));
        }

        if (!inlinedFields.isEmpty()) {
            // Stage 2 – every OUTGOING field comes from the same wbgetentities claims
            // documents, regardless of whether its value is a QID or a literal.
            // INCOMING entity fields still require a member-bounded SPARQL query.
            for (RuleIncludedField f : rootNode.includedFields()) {
                if (membershipTargets.contains(f)) continue;
                if (f.direction() == RuleDirection.ROOT_TO_ITEM) {
                    requireApiProperty(rootNode, f);
                    queries.add("# Field \"" + f.fieldName() + "\" via wbgetentities "
                                        + "claims (props=labels|claims, ids batched by 50): each "
                                        + "member's " + RuleNode.cleanPid(f.propertyPid())
                                        + " values — not SPARQL.");
                } else if (inlinedFields.contains(f)) {
                    queries.add("# Member field \"" + f.fieldName() + "\" (batched by "
                                        + memberFieldBatchSize + " members):\n"
                                        + RuleNodeQueryBuilder
                            .memberFieldBatchQuery(f, List.of("Q_MEMBER_BATCH"))
                            .replace("wd:Q_MEMBER_BATCH", "<member batch>"));
                }
            }

            queries.add("# Labels for QID-only refs via wbgetentities "
                                + "(props=labels, ids batched by 50).");
            queries.add("# (no whole-class field query: every entity-list field is "
                                + "captured over the bounded member set above.)");

            RuleNode residual = rootNode.sampleCopy(rootNode.limit());
            for (RuleIncludedField f : rootNode.includedFields()) {
                if (!membershipTargets.contains(f)
                        && f.direction() == RuleDirection.ITEM_TO_ROOT
                        && !inlinedFields.contains(f)) {
                    residual.addIncludedField(f);
                }
            }
            if (!residual.includedFields().isEmpty()) {
                queries.add("# Residual scalar/media fields (batched by "
                                    + memberFieldBatchSize + " members):\n"
                                    + RuleNodeQueryBuilder
                        .memberBoundedValuesQuery(residual, List.of("Q0"))
                        .replace("wd:Q0", "<member batch>"));
            }
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
    // Physical batch sizes used by the staged extraction plan (see
    // docs/extraction-batched-membership.md). Configurable; defaults chosen to keep
    // each query well under the WDQS soft timeout.
    //   membershipTargetBatchSize — membership roots per backbone query. A big
    //     relational membership (e.g. P1411 → 58 Oscar categories) finds too many
    //     members to label in one query and soft-times-out; splitting the roots
    //     keeps each query small.
    //   memberFieldBatchSize — members per Stage-2 direct-field query.
    //   labelBatchSize — QIDs per Stage-3 label-resolution batch.
    private int membershipTargetBatchSize = 10;
    private int memberFieldBatchSize = 100;
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
     * Stage-1 result: the complete member registry plus any captured membership
     * registry and, when a {@linkplain RuleNodeQueryBuilder#membershipTargetFields
     * target} field was captured, the membership-edge map (member qid →
     * insertion-ordered set of target qids). The edge map is empty when nothing was
     * captured.
     */
    private record MembershipBackbone(
            List<WikidataDynamicObject> members,
            Map<String, LinkedHashSet<String>> edges) {}

    /**
     * Stage 1 – Membership backbone. For a large multi-target relational
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

        // Taken before discovery starts, so a rejected candidate can be told apart
        // from an object another class run already put in the shared pool.
        Set<String> pooledBefore = registry.qids();

        List<String> targets = new ArrayList<>(rootNode.additionalSourceQids());
        if (targets.size() <= membershipTargetBatchSize) {
            // A ranked class is scanned in bands: one request over the whole class is
            // what exceeds the endpoint's ceiling, and the ranking measure is the only
            // per-entity quantity available before the members are known.
            List<WikidataDynamicObject> members = rootNode.hasRank()
                    ? runBandedBackbone(rootNode, pooledBefore, progress)
                    : runSingleBackbone(rootNode, progress);
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
                throw new InterruptedException(
                        "Interrupted while loading membership backbone");
            }
            List<String> batch = new ArrayList<>(targets.subList(
                    from, Math.min(from + membershipTargetBatchSize, targets.size())));
            RuleNode backbone = rootNode.backboneCopy(rootNode.limit());
            backbone.additionalSourceQids().clear();
            batch.forEach(backbone::addAdditionalSourceQid);
            String sparql = capture
                    ? RuleNodeQueryBuilder.membershipBackboneQuery(backbone)
                    : RuleNodeQueryBuilder.membershipBackboneQueryNoLabel(backbone);
            List<WikidataDynamicObject> part = runRootQuery(
                    "Root membership (backbone " + (++n) + "/" + total + ")",
                    sparql, progress,
                    () -> runMembershipBatch(backbone, sparql, capture, edges));
            for (WikidataDynamicObject o : part) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }

        // Every physical batch has its own defensive LIMIT, but the configured
        // node limit is a logical limit on the DISTINCT union, not a multiplier
        // applied once per target batch. Process all batches first so nominees that
        // are retained can still collect membership edges from later categories,
        // then truncate the insertion-ordered union and discard edges for members
        // outside that final set.
        keepTopMembers(byQid, rootNode.limit(), pooledBefore);
        edges.keySet().retainAll(byQid.keySet());

        // Per-root member counts as a distinct, copy-pasteable log entry: the member
        // count each membership root contributed, sorted by root QID so two runs'
        // blocks diff cleanly (e.g. which category an 11181-vs-11176 shift came from).
        // A structured subquery (not a message) so it's its own card with the total
        // as the summary and the full per-root list in the request. Only when the
        // root was captured (edges present).
        if (capture && !edges.isEmpty()) {
            java.util.TreeMap<String, Integer> membersPerRoot = new java.util.TreeMap<>();
            for (LinkedHashSet<String> roots : edges.values()) {
                for (String r : roots) membersPerRoot.merge(r, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder("root\tmembers\n");
            for (Map.Entry<String, Integer> e : membersPerRoot.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            progress.subquery(
                    rootNode.name() + " membership — per-root member counts",
                    sb.toString(),
                    byQid.size() + " members across " + membersPerRoot.size() + " roots");
        }

        return new MembershipBackbone(new ArrayList<>(byQid.values()), edges);
    }

    /**
     * Parses one membership backbone batch: each {@code ?value} becomes a registry
     * member and, when capturing, each {@code (?value, ?root)}
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
            if (qid == null || !WikidataIds.isQid(qid)) continue;

            WikidataDynamicObject obj = rowsByQid.computeIfAbsent(
                    qid, k -> registry.getOrCreate(qid, label));
            obj.type(node.name());
            addValueFilterFields(obj, node, b);

            if (capture) {
                String targetQid = b.qid("root");
                if (targetQid != null && WikidataIds.isQid(targetQid)) {
                    edges.computeIfAbsent(qid, k -> new LinkedHashSet<>())
                         .add(targetQid);
                }
            }
        }
        return new ArrayList<>(rowsByQid.values());
    }

    /**
     * Stage 1 – Materialize membership-target fields directly from the
     * edge map: each target qid resolves to its canonical registry object and is
     * merged onto the member (merge dedups + preserves the insertion order the
     * LinkedHashSet already fixed). Labels are placeholders (the qid) until the
     * shared Stage-3 label pass resolves them.
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

    /** An unranked class has no measure to band on, so it stays one request. */
    private List<WikidataDynamicObject> runSingleBackbone(
            RuleNode rootNode, GenerationLog progress) throws Exception {
        RuleNode backbone = rootNode.backboneCopy(rootNode.limit());
        String sparql = RuleNodeQueryBuilder.valuesQuery(backbone);
        return runRootQuery("Root membership (backbone)", sparql, progress,
                            () -> runValueQuery(backbone, sparql));
    }

    /**
     * Membership discovered band by band, most notable first, stopping as soon as the
     * configured limit is reached.
     *
     * <p>Stopping early is the point of descending order: the limit asks for the top N by
     * the ranking measure, so once N distinct members are in hand every remaining band
     * holds only lower-ranked ones. For the movies case that turns a scan of 47,639
     * candidates into the first few bands. Each band runs through the executor, so one
     * the endpoint refuses is retried and then bisected rather than failing the run.
     */
    private List<WikidataDynamicObject> runBandedBackbone(
            RuleNode rootNode, Set<String> pooledBefore, GenerationLog progress)
            throws Exception {

        int limit = rootNode.limit();
        LinkedHashMap<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();

        String measure = rootNode.rankBySitelinks()
                ? "sitelinks" : RuleNode.cleanPid(rootNode.rankPropertyPid());
        String goal = "Find the top " + limit + " " + rootNode.name() + " by " + measure
                + " — scanned in descending bands, stopping at the limit "
                + "(one request over the whole class exceeds the endpoint's ceiling)";

        try (GenerationLog.Group group = progress.group(goal)) {
            for (RankBandWorkUnit band
                    : RankBandWorkUnit.descendingBands(rootNode, client, TOP_RANK_BAND)) {
                if (byQid.size() >= limit) {
                    group.message("Reached the limit of " + limit
                                          + " members; lower-ranked bands are not scanned.\n");
                    break;
                }
                executor(group).run(List.of(band), (descriptor, rows) -> {
                    for (WikidataDynamicObject member : mapValueRows(rootNode, rows)) {
                        byQid.putIfAbsent(member.qid(), member);
                    }
                });
            }
        }

        // The limit is logical, over the DISTINCT union: each band carries it only as a
        // defensive per-request cap, so the band that crosses the limit takes the total
        // past it — for movies, five bands found 31,630 candidates for a limit of 20,000.
        keepTopMembers(byQid, limit, pooledBefore);
        return new ArrayList<>(byQid.values());
    }

    /**
     * Truncates a discovered union to the node's limit — in the pool as well as in the
     * returned list.
     *
     * <p>Discovery over-produces on both batched paths: a rank band returns everything
     * in it, and a multi-root union counts each root's members. Dropping the excess from
     * the returned list is not enough, because every candidate was created in the shared
     * registry and the SAVE writes the registry, not the returned list. The rejected
     * candidates were therefore persisted as field-less instances — 11,634 of them in a
     * 20,000-limit movies run, indistinguishable in the pool from real members whose
     * fields genuinely failed to load.
     *
     * <p>Only candidates this pass created are removed. The pool is shared across class
     * runs, so an object that was already there belongs to another run.
     */
    private void keepTopMembers(
            LinkedHashMap<String, WikidataDynamicObject> byQid,
            int limit,
            Set<String> pooledBefore) {

        if (limit <= 0 || byQid.size() <= limit) {
            return;
        }
        Iterator<Map.Entry<String, WikidataDynamicObject>> entries =
                byQid.entrySet().iterator();
        int position = 0;
        while (entries.hasNext()) {
            String qid = entries.next().getKey();
            if (position++ < limit) continue;   // insertion order IS rank order
            entries.remove();
            if (!pooledBefore.contains(qid)) {
                registry.remove(qid);
            }
        }
    }

    /**
     * Stage 2 – Member field capture. Fetch each remaining multivalued direct
     * with its own MEMBER-batched row query instead of an inline GROUP_CONCAT over
     * the whole class. A failed batch aborts generation: returning the complete
     * membership with that batch empty would turn an extraction error into false
     * "missing field" results.
     */
    private void captureMemberFields(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> fields,
            GenerationLog progress) throws Exception {

        List<String> memberQids = members.stream()
                                         .map(WikidataDynamicObject::qid)
                                         .filter(q -> q != null && WikidataIds.isQid(q))
                                         .toList();
        if (memberQids.isEmpty()) return;

        List<batch.WorkUnit<List<WikidataBinding>>> units = new ArrayList<>();
        Map<String, RuleIncludedField> fieldsByName = new LinkedHashMap<>();
        for (RuleIncludedField field : fields) {
            fieldsByName.put(field.fieldName(), field);
            for (int from = 0; from < memberQids.size(); from += memberFieldBatchSize) {
                List<String> batchQids = memberQids.subList(
                        from, Math.min(from + memberFieldBatchSize, memberQids.size()));
                units.add(new MemberBatchQueryUnit(
                        "wikidata.memberField",
                        "Member field \"" + field.fieldName() + "\"",
                        batchQids,
                        qids -> RuleNodeQueryBuilder.memberFieldBatchQuery(field, qids),
                        client,
                        Map.of("field", field.fieldName())));
            }
        }

        // The executor owns retrying, narrowing a batch the endpoint refuses, and
        // failing the run rather than leaving a field half-filled. Publication happens
        // here, once per successful batch, so a retried attempt cannot merge twice.
        String goal = "Fill " + fieldList(fields) + " for " + memberQids.size()
                + " members — " + units.size() + " batches of " + memberFieldBatchSize
                + " (incoming references, one query per batch)";
        try (GenerationLog.Group group = progress.group(goal)) {
            executor(group).run(units,
                    (descriptor, rows) -> mergeMemberField(descriptor, rows));
        }
    }

    /** An outgoing field is read from a member's claims document. A missing PID is a
     * broken model, not an empty field: fail before it can fall between the API path
     * and the incoming-only residual SPARQL path. */
    private static void requireApiProperty(RuleNode owner, RuleIncludedField field) {
        String pid = RuleNode.cleanPid(field.propertyPid());
        if (WikidataIds.isPid(pid)) return;
        String qualified = owner == null || owner.name() == null
                ? field.fieldName() : owner.name() + "." + field.fieldName();
        throw new IllegalArgumentException(
                "Outgoing field '" + qualified
                        + "' requires a Wikidata property PID; got '"
                        + field.propertyPid() + "'");
    }

    /** Publishes one member-field batch onto the registry-shared backbone members.
     *  merge() is the final duplicate guard, so replaying a committed batch is safe. */
    private void mergeMemberField(
            batch.WorkDescriptor descriptor, List<WikidataBinding> rows) {
        String fieldName = descriptor.parameters().get("field");
        for (WikidataBinding row : rows) {
            String memberQid = row.qid("value");
            String fieldValueQid = row.qid("fieldValue");
            if (memberQid == null || !WikidataIds.isQid(memberQid)) continue;
            if (fieldValueQid == null || !WikidataIds.isQid(fieldValueQid)) continue;
            WikidataDynamicObject member = registry.get(memberQid);
            if (member == null) continue;   // not a backbone member
            member.merge(fieldName, registry.getOrCreate(fieldValueQid, fieldValueQid));
        }
    }

    /** The fields a stage is filling, named — {@code [locations, director]}. A stage
     *  that reports only how MANY fields it carries leaves a reader of a failed batch
     *  with no way to tell what went unfilled. */
    private static String fieldList(List<RuleIncludedField> fields) {
        return fields.stream()
                     .map(RuleIncludedField::fieldName)
                     .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    /** The executor every batched stage runs on. */
    private batch.BatchExecutor<List<WikidataBinding>> executor(GenerationLog progress) {
        return new batch.BatchExecutor<>(
                batchPolicy,
                batchProgress(progress),
                wikidata.WikidataBatchFailureClassifier.INSTANCE,
                cancellation,
                batch.BatchCheckpointStore.NONE);
    }

    /** Adapts the extraction log to the executor's progress seam. */
    private static batch.BatchProgress batchProgress(GenerationLog progress) {
        return (title, request) -> {
            GenerationLog.Running running = progress.subqueryStarted(title, request);
            return new batch.BatchProgress.Running() {
                @Override public void done(String summary) { running.done(summary); }
                @Override public void failed(String error) { running.failed(error); }
            };
        };
    }

    /**
     * Runs one member-field batch and merges each {@code (?value, ?fieldValue)} onto
     * the existing backbone member (registry-shared, canonical). merge is the final
     * duplicate guard (dedup + insertion order). Returns the members it touched so
     * the log shows a row count.
     */
    /**
     * Residual (scalar/media) field capture, bounded by the members the backbone
     * already found and batched like every other Stage-2 query.
     *
     * <p>The whole-class form re-scans the class to rediscover those members — the
     * shape that soft-times-out on a large membership, and the one that used to
     * leave fields silently unfilled. Failures propagate: an unfilled scalar is
     * indistinguishable from a genuinely absent value once the run is saved.
     */
    private List<WikidataDynamicObject> captureResidualFields(
            RuleNode enrichNode,
            RuleNode rootNode,
            List<WikidataDynamicObject> members,
            GenerationLog progress) throws Exception {

        if (enrichNode == rootNode || enrichNode.includedFields().isEmpty()) {
            return new ArrayList<>();
        }

        List<String> memberQids = members.stream()
                                         .map(WikidataDynamicObject::qid)
                                         .filter(q -> q != null && WikidataIds.isQid(q))
                                         .toList();
        if (memberQids.isEmpty()) {
            return new ArrayList<>();
        }

        String names = fieldList(enrichNode.includedFields());
        Map<String, WikidataDynamicObject> byQid = new LinkedHashMap<>();

        List<batch.WorkUnit<List<WikidataBinding>>> units = new ArrayList<>();
        for (int i = 0; i < memberQids.size(); i += memberFieldBatchSize) {
            units.add(new MemberBatchQueryUnit(
                    "wikidata.residualFields",
                    // Named, not counted: "(+2)" said how many fields a batch carried
                    // but never which, so a failure gave no clue what went unfilled.
                    "Residual fields " + names,
                    memberQids.subList(
                            i, Math.min(i + memberFieldBatchSize, memberQids.size())),
                    qids -> RuleNodeQueryBuilder.memberBoundedValuesQuery(enrichNode, qids),
                    client,
                    Map.of()));
        }

        String goal = "Fill " + names + " for " + memberQids.size() + " members — "
                + units.size() + " batches of " + memberFieldBatchSize
                + " (scalar/media values, bounded by the members already found)";
        try (GenerationLog.Group group = progress.group(goal)) {
            executor(group).run(units, (descriptor, rows) -> {
                for (WikidataDynamicObject o : mapValueRows(enrichNode, rows)) {
                    byQid.putIfAbsent(o.qid(), o);
                }
            });
        }

        return new ArrayList<>(byQid.values());
    }

    /**
     * Stage 2 – Fetch OUTGOING direct fields (the member's own claims, e.g. type =
     * P31 and publicationDate = P577) from the reliable wbgetentities API instead of
     * the SPARQL query engine (P31 full-scans and times out there). One claims pass over the
     * members. A failed API batch aborts generation so the UI cannot present a
     * partially fetched claim set as genuinely missing data. Also fills any blank
     * member label the API returns.
     */
    private void captureOutgoingFieldsViaApi(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> outgoingFields,
            GenerationLog progress) throws Exception {

        List<String> memberQids = members.stream()
                                         .map(WikidataDynamicObject::qid)
                                         .filter(q -> q != null && WikidataIds.isQid(q))
                                         .toList();
        if (memberQids.isEmpty()) return;

        for (RuleIncludedField field : outgoingFields) {
            requireApiProperty(null, field);
        }

        LinkedHashSet<String> directPids = new LinkedHashSet<>();
        outgoingFields.stream()
                .map(f -> RuleNode.cleanPid(f.propertyPid()))
                .filter(WikidataIds::isPid)
                .forEach(directPids::add);
        LinkedHashSet<String> pids = new LinkedHashSet<>(directPids);
        factDemands.stream().flatMap(d -> d.propertyPids().stream()).forEach(pids::add);

        // A class with nothing to fetch but a LABEL or ALIASES demand is asked anyway,
        // and this deliberately costs a request the old condition avoided. Labels
        // resolved HERE are in place before anything derives from them; left to final
        // hydration they settle two phases later, after composition has already named
        // an owned part from a QID — which is how ten Oscars parts came to be called
        // "Q312674 — Structured Name" (#115). Correctness does not need it, since
        // hydration still covers every class whose binding declares labels; the run
        // reads better for it, and fewer derived names are built from a placeholder.
        boolean retainsMetadata = factDemands.stream()
                .anyMatch(demand -> !demand.metadata().isEmpty());
        if (pids.isEmpty() && !retainsMetadata) return;

        if (!directPids.isEmpty()) {
            api().facts().recordDemand(
                    "root fields " + fieldList(outgoingFields), memberQids, directPids);
        }
        FactDemandBinder.Binding binding = FactDemandBinder.bind(
                factDemands, memberQids, api().facts(), "root acquisition");

        String names = fieldList(outgoingFields);
        String propagated = factDemands.isEmpty() ? "" : factDemands.stream()
                .filter(d -> !d.propertyPids().isEmpty() || !d.metadata().isEmpty())
                .map(d -> d.consumer() + " needs "
                        + (d.propertyPids().isEmpty() ? "" : d.propertyPids())
                        + (d.metadata().isEmpty() ? "" : d.metadata()))
                .distinct().collect(java.util.stream.Collectors.joining("; "));
        int batches = (memberQids.size() + 49) / 50;
        try (GenerationLog.Group g = progress.group(
                (names.isBlank() ? "Retain downstream facts" : "Fill " + names)
                        + " for " + memberQids.size() + " members — "
                        + batches + " batches of 50 (the members' own claims, fetched "
                        + "via wbgetentities: these properties full-scan in SPARQL)"
                        + (propagated.isBlank() ? "" : "; propagated: " + propagated))) {
            Map<String, WikidataApiClient.ApiEntity> details =
                    api().getEntities(memberQids, new ArrayList<>(pids), g.batchSink());
            boolean retainAliases = factDemands.stream().anyMatch(demand ->
                    demand.metadata().contains(FactDemand.EntityMetadata.ALIASES));
            boolean retainLabel = factDemands.stream().anyMatch(demand ->
                    demand.metadata().contains(FactDemand.EntityMetadata.LABEL));
            int filled = applyEntityClaims(
                    members, outgoingFields, details, retainLabel, retainAliases);
            if (!outgoingFields.isEmpty()) {
                g.message("Fetched field(s) [" + names + "] for " + filled + "/"
                                  + memberQids.size() + " members via wbgetentities.\n");
            }
            if (!propagated.isBlank()) {
                g.message("Retained prospective downstream claim(s): "
                                  + propagated + "; bound " + binding.claimPairs()
                                  + " QID/property and " + binding.metadataPairs()
                                  + " QID/metadata pair(s).\n");
            }
        }
    }

    /**
     * Merges each member's outgoing-field claim values (canonical registry refs,
     * typed dates, strings or media) and fills a blank member label from the API.
     * Returns how many members got at least one value. Package-visible for
     * RuleTreeExtractorLabelTest.
     */
    int applyEntityClaims(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> outgoingFields,
            Map<String, WikidataApiClient.ApiEntity> details) {
        return applyEntityClaims(members, outgoingFields, details, true, true);
    }

    private int applyEntityClaims(
            List<WikidataDynamicObject> members,
            List<RuleIncludedField> outgoingFields,
            Map<String, WikidataApiClient.ApiEntity> details,
            boolean retainLabel,
            boolean retainAliases) {

        int filled = 0;
        for (WikidataDynamicObject member : members) {
            WikidataApiClient.ApiEntity e = details.get(member.qid());
            if (e == null) continue;
            if (retainAliases) member.aliases(e.aliases());
            if (retainLabel && isPlaceholderLabel(member) && !e.label().isBlank()) {
                member.name(e.label());
            }
            boolean any = false;
            for (RuleIncludedField f : outgoingFields) {
                String pid = RuleNode.cleanPid(f.propertyPid());
                for (String raw : e.values(pid)) {
                    member.merge(f.fieldName(), toFieldValue(f, raw, null));
                    any = true;
                }
                // The source SAID this field is unknown / none rather than staying
                // silent. Recorded so curation stops offering a gap nothing can fill.
                String valueless = e.valuelessClaim(pid);
                if (valueless != null) {
                    member.fieldStatus(f.fieldName(), "novalue".equals(valueless)
                            ? FieldStatus.ASSERTED_NONE : FieldStatus.ASSERTED_UNKNOWN);
                }
            }
            if (any) filled++;
        }
        return filled;
    }

    /**
     * Stage 3 – Label resolution. Resolve every registry object still carrying a
     * placeholder name (the qid), including references created by Stage 1
     * membership-target capture and Stage 2 member-field capture. One shared
     * best-effort wbgetentities pass (labels only) over the distinct placeholder
     * qids, resolved best-effort: a throttled batch leaves only its own placeholders
     * named by QID instead of discarding the labels every other batch returned.
     * That isolation lives in the API client, so the batches still fan out.
     * No-op when nothing is unlabeled (e.g. a small membership whose SERVICE labels
     * already named everything).
     */
    void resolveLabels(GenerationLog progress) {
        List<WikidataDynamicObject> placeholders = new ArrayList<>();
        for (WikidataDynamicObject o : registry.values()) {
            if (isPlaceholderLabel(o)) placeholders.add(o);
        }
        if (placeholders.isEmpty()) return;

        // isPlaceholderLabel already required a valid QID, so this is every
        // placeholder — no index-parallel pairing to keep aligned.
        List<String> qids = placeholders.stream()
                                        .map(WikidataDynamicObject::qid)
                                        .toList();
        int totalBatches = (qids.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        try (GenerationLog.Group g = progress.group("wbgetentities LABELS — "
                                                            + qids.size() + " refs, " + totalBatches
                                                            + " batches (names for QID-only references)")) {
            // The shared resolver owns label semantics for both generation and the
            // TransformApp repair flow. Generation selects its bounded-parallel mode;
            // interactive repair selects sequential mode to avoid a user-triggered burst.
            wikidata.api.WikidataEntityLabelResolver.Result resolved =
                    new wikidata.api.WikidataEntityLabelResolver(api()).resolve(
                            qids,
                            wikidata.api.WikidataEntityLabelResolver.Execution.BOUNDED_PARALLEL,
                            g.batchSink());
            Map<String, WikidataApiClient.ApiEntity> details = new LinkedHashMap<>();
            resolved.labels().forEach((qid, label) -> details.put(
                    qid, new WikidataApiClient.ApiEntity(qid, label, Map.of())));
            resolved.missing().forEach(qid -> details.put(
                    qid, new WikidataApiClient.ApiEntity(qid, "", Map.of(), true)));
            int filled = applyLabels(placeholders, details);
            g.message("Resolved " + filled + "/" + placeholders.size()
                              + " entity label(s) via wbgetentities"
                              + (resolved.failedBatches() == 0 ? ".\n"
                                      : "; " + resolved.failedBatches()
                                              + " batch(es) failed and keep their QIDs.\n"));
        } catch (Exception e) {
            // Only interruption and a fatal setup error reach here now; a throttled
            // batch is absorbed above and costs only its own labels.
            if (Thread.currentThread().isInterrupted()) return;
            progress.message("WARNING: label resolution via wbgetentities failed ("
                                     + e.getMessage() + ") — " + placeholders.size()
                                     + " entity ref(s) keep their QID as the name this run.\n");
        }
    }

    /**
     * Sets each placeholder object's name from the resolved label. Returns how many
     * were filled. Package-visible for RuleTreeExtractorLabelTest.
     */
    int applyLabels(
            List<WikidataDynamicObject> objects,
            Map<String, WikidataApiClient.ApiEntity> details) {
        int filled = 0;
        for (WikidataDynamicObject o : objects) {
            if (!isPlaceholderLabel(o)) continue;
            WikidataApiClient.ApiEntity e = details.get(o.qid());
            if (e != null && e.missing()) {
                o.wikidataEntityMissing(true);
            } else if (e != null && !e.label().isBlank()) {
                o.name(e.label());
                filled++;
            }
        }
        return filled;
    }

    /** A ref whose name was never resolved — blank or still equal to its qid. */
    private static boolean isPlaceholderLabel(WikidataDynamicObject o) {
        if (o == null) return false;
        String qid = o.qid();
        if (qid == null || !WikidataIds.isQid(qid)) return false;
        String name = o.getDisplayName();
        return name == null || name.isBlank() || name.equals(qid);
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
            if (qid == null || !WikidataIds.isQid(qid)) continue;

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
        if (childQid == null && WikidataIds.isQid(uriOrQid)) {
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
            if (p != null && p.qid() != null && WikidataIds.isQid(p.qid()))
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
            if (childQid == null || !WikidataIds.isQid(childQid)) {
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
        return mapValueRows(node, client.query(sparql));
    }

    /**
     * Maps value rows onto registry objects — this IS the publication step, so it runs
     * only after a batch has fully succeeded. Shared by the batched stages (through a
     * committer) and by the queries not yet converted, so there is one mapping.
     */
    private List<WikidataDynamicObject> mapValueRows(
            RuleNode node, List<WikidataBinding> rows) {

        Map<String, WikidataDynamicObject> rowsByQid = new LinkedHashMap<>();

        for (WikidataBinding b : rows) {
            String qid   = b.qid("value");
            String label = b.label("value");
            if (qid == null || !WikidataIds.isQid(qid)) continue;

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
        return WikidataIds.isQid(tail) ? tail : null;
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
