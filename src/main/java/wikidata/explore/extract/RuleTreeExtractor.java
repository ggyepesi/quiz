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
            String sparql = RuleNodeQueryBuilder.fieldOptimizedValuesQuery(rootNode);
            roots = runFieldOptimizedQuery(rootNode, inlinedFields, sparql);
            progress.subquery("Root query (+" + inlinedFields.size()
                    + " inlined field" + (inlinedFields.size() == 1 ? "" : "s") + ")",
                    sparql, roots.size() + " objects");
        } else {
            String sparql = RuleNodeQueryBuilder.valuesQuery(rootNode);
            roots = runValueQuery(rootNode, sparql);
            progress.subquery("Root query", sparql, roots.size() + " objects");
        }

        if (childDepth > 0 && !complex.isEmpty()) {
            loadComplexEdgesParallel(roots, rootNode, complex,
                    childDepth, progress);
        } else if (childDepth == 0 && !rootNode.edges().isEmpty()) {
            progress.message("Child depth is 0; child edges were not loaded.\n");
        }

        return roots;
    }

    public List<String> previewQueries(RuleNode rootNode, int childDepth) {
        List<String> queries = new ArrayList<>();

        List<RuleIncludedField> inlinedFields =
                RuleNodeQueryBuilder.simpleInlinedFields(rootNode);
        List<RuleEdge> complex = complexEdges(rootNode);

        if (!inlinedFields.isEmpty()) {
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
                    try {
                        Map<String, WikidataDynamicObject> kids = new LinkedHashMap<>();
                        for (WikidataBinding b : client.query(sparql)) {
                            String childQid  = b.qid("value");
                            String childLabel = b.label("value");
                            if (childQid == null || !childQid.matches("Q\\d+"))
                                continue;
                            WikidataDynamicObject child = kids.computeIfAbsent(
                                    childQid, k -> registry.getOrCreate(childQid, childLabel));
                            child.type(childNode.name()); // stamp the child class (e.g. "Star")
                            addValueFilterFields(child, childNode, b);
                            addIncludedFields(child, childNode, b);
                        }
                        if (!kids.isEmpty()) childrenByParent.put(parentQid, kids);
                        // One structured, collapsible sub-query entry per parent (a
                        // single runnable SELECT). Parents run in parallel, so the
                        // recorder add is serialised here.
                        long ms = System.currentTimeMillis() - t0;
                        synchronized (progress) {
                            progress.subquery(title, sparql, kids.size() + " (" + ms + " ms)");
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
                        // the same log entry, then continue with the rest.
                        long ms = System.currentTimeMillis() - t0;
                        synchronized (progress) {
                            progress.subqueryFailed(title, sparql,
                                    ex.getMessage() + " (" + ms + " ms)");
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdown();
        }

        return new EdgeResult(edge, childrenByParent);
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
