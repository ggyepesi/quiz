package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.DiscoveredProperty;
import wikidata.explore.query.result.DiscoveredProperty.PropertyKind;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;
import wikidata.explore.query.template.sparql.SparqlQueries;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Class-level property discovery: samples N instances of a class, then
 * profiles which direct properties they have (outgoing) and which
 * properties reference the class item (incoming), in parallel.
 *
 * The sample comes from the rule node's instance query, or from a plain
 * P31 query when an override class QID is given.
 */
public class DiscoverClassPropertiesQuery
        implements Query<List<DiscoveredProperty>> {

    public static final int RESULT_LIMIT = 100;

    private static final String TYPE_ITEM     = "http://wikiba.se/ontology#WikibaseItem";
    private static final String TYPE_STRING   = "http://wikiba.se/ontology#String";
    private static final String TYPE_MONO     = "http://wikiba.se/ontology#Monolingualtext";
    private static final String TYPE_QUANTITY = "http://wikiba.se/ontology#Quantity";
    private static final String TYPE_TIME     = "http://wikiba.se/ontology#Time";
    private static final String TYPE_URL      = "http://wikiba.se/ontology#Url";
    private static final String TYPE_COMMONS  = "http://wikiba.se/ontology#CommonsMedia";
    private static final String TYPE_GLOBE    = "http://wikiba.se/ontology#GlobeCoordinate";
    private static final String TYPE_EXT_ID   = "http://wikiba.se/ontology#ExternalId";
    private static final String TYPE_MATH     = "http://wikiba.se/ontology#Math";

    private final RuleNode node;
    private final String overrideClassQid;
    private final int sampleSize;

    public DiscoverClassPropertiesQuery(
            RuleNode node,
            String overrideClassQid,
            int sampleSize) {

        this.node = node;
        this.overrideClassQid =
                overrideClassQid == null ? "" : overrideClassQid.trim();
        this.sampleSize = Math.max(1, sampleSize);
    }

    @Override
    public String purpose() {
        return "Discover class properties";
    }

    @Override
    public String skeleton() {
        return "sample class instances -> outgoing + incoming property profile";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("classQid", classQid());

        if (node != null) {
            p.put("node", node.name() == null ? "?" : node.name());
        }

        return p;
    }

    @Override
    public List<DiscoveredProperty> execute(QueryContext context)
            throws Exception {

        List<String> qids = sampleQids(context);

        if (qids.isEmpty()) {
            context.message("Discover: no sample instances found.\n");
            return List.of();
        }

        String outgoingSparql =
                SparqlQueries.discoverOutgoingProperties(qids, RESULT_LIMIT);

        // Incoming = properties pointing AT sampled instances (e.g. a star's
        // P59 -> this constellation), not at the class item itself.
        String incomingSparql =
                SparqlQueries.discoverIncomingProperties(qids, RESULT_LIMIT);

        context.message("\nDiscover: outgoing properties ("
                                + qids.size()
                                + " items)\n"
                                + "-".repeat(40)
                                + "\n"
                                + outgoingSparql
                                + "\n");

        context.message("\nDiscover: incoming properties to "
                                + classQid()
                                + "\n"
                                + "-".repeat(40)
                                + "\n"
                                + incomingSparql
                                + "\n");

        CompletableFuture<List<DiscoveredProperty>> outgoing =
                context.sparql()
                       .queryAsync(outgoingSparql)
                       .thenApply(rows ->
                                          parseBindings(rows, qids.size(), "outgoing"));

        CompletableFuture<List<DiscoveredProperty>> incoming =
                context.sparql()
                       .queryAsync(incomingSparql)
                       .thenApply(rows ->
                                          parseBindings(rows, qids.size(), "incoming"));

        return outgoing.thenCombine(incoming, (out, in) -> {
            List<DiscoveredProperty> merged = new ArrayList<>();
            merged.addAll(out);
            merged.addAll(in);
            return merged;
        }).get();
    }

    @Override
    public int rowCount(List<DiscoveredProperty> result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(List<DiscoveredProperty> result) {
        return rowCount(result) + " properties";
    }

    private String classQid() {
        if (WikidataIds.isQid(overrideClassQid)) {
            return overrideClassQid;
        }

        return node == null || node.sourceQid() == null
                ? ""
                : node.sourceQid();
    }

    private List<String> sampleQids(QueryContext context)
            throws Exception {

        String sparql;
        String var;

        if (WikidataIds.isQid(overrideClassQid)) {
            sparql = SparqlQueries.sampleInstancesByP31(
                    overrideClassQid,
                    sampleSize);
            var = "item";
        } else {
            // Multi-target membership (e.g. P1411 -> many award categories): take
            // one representative per target so all categories are profiled, not a
            // flat LIMIT that could come entirely from one category.
            String stratified =
                    RuleNodeQueryBuilder.stratifiedSampleQuery(node.sampleCopy(sampleSize));
            sparql = stratified != null
                    ? stratified
                    : RuleNodeQueryBuilder.valuesQuery(node.sampleCopy(sampleSize));
            var = "value";
        }

        String sampleSparql = sparql;
        String sampleVar = var;

        return context.step(
                "Sample class QIDs",
                "SPARQL",
                null,
                Map.of("sampleSize", String.valueOf(sampleSize)),
                step -> {
                    step.request(sampleSparql);

                    List<String> qids = new ArrayList<>();

                    for (WikidataBinding b : context.sparql().query(sampleSparql)) {
                        String qid = b.qid(sampleVar);

                        if (qid != null && WikidataIds.isQid(qid)) {
                            qids.add(qid);
                        }
                    }

                    step.summary(qids.size() + " QIDs");
                    return qids;
                });
    }

    private static List<DiscoveredProperty> parseBindings(
            List<WikidataBinding> bindings,
            int sampleSize,
            String direction) {

        List<DiscoveredProperty> result = new ArrayList<>();

        for (WikidataBinding b : bindings) {
            String pid = b.qid("prop");

            if (pid == null || !WikidataIds.isPid(pid)) {
                continue;
            }

            String label = b.label("prop");
            String typeUri = b.value("type");
            String countStr = b.value("count");
            String example = b.value("example");
            String exLabel = b.value("exampleLabel");

            int count = 0;
            try {
                count = Integer.parseInt(countStr);
            } catch (Exception ignored) {
            }

            String exQid = entityQid(example);
            String exDisplay =
                    exLabel != null && !exLabel.isBlank()
                            ? exLabel
                            : (!exQid.isBlank() ? exQid : localName(example));

            result.add(new DiscoveredProperty(
                    pid,
                    label != null && !label.isBlank() ? label : pid,
                    typeUri,
                    typeLabel(typeUri),
                    kindOf(typeUri),
                    count,
                    sampleSize,
                    exQid,
                    exDisplay == null ? "" : exDisplay,
                    direction));
        }

        return result;
    }

    private static String entityQid(String value) {
        if (value == null) return "";
        int i = value.lastIndexOf('/');
        String tail = i >= 0 ? value.substring(i + 1) : value;
        return WikidataIds.isQid(tail) ? tail : "";
    }

    private static String localName(String uri) {
        if (uri == null) return "";
        int i = uri.lastIndexOf('/');
        return i >= 0 ? uri.substring(i + 1) : uri;
    }

    private static String typeLabel(String uri) {
        if (uri == null) return "?";
        return switch (uri) {
            case TYPE_ITEM     -> "entity";
            case TYPE_STRING   -> "string";
            case TYPE_MONO     -> "text";
            case TYPE_QUANTITY -> "quantity";
            case TYPE_TIME     -> "date/time";
            case TYPE_URL      -> "URL";
            case TYPE_COMMONS  -> "media";
            case TYPE_GLOBE    -> "coordinate";
            case TYPE_EXT_ID   -> "external ID";
            case TYPE_MATH     -> "math";
            default            -> "other";
        };
    }

    private static PropertyKind kindOf(String uri) {
        if (uri == null) return PropertyKind.SCALAR;
        return switch (uri) {
            case TYPE_ITEM    -> PropertyKind.ENTITY;
            case TYPE_COMMONS -> PropertyKind.MEDIA;
            default           -> PropertyKind.SCALAR;
        };
    }
}
