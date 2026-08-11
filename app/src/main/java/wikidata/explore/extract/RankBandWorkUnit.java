package wikidata.explore.extract;

import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One band of a membership scan, sliced on the ranking measure.
 *
 * <p>Membership DISCOVERY cannot be batched the way field capture is: the members are not
 * known yet, so there is no list to chunk. Root batching does not help either — it slices
 * {@code additionalSourceQids}, and a single-root class like "film" has one. What is
 * available per entity is the ranking measure ({@code wikibase:sitelinks}, or the
 * configured rank property), and it is monotone, so half-open bands over it are disjoint,
 * cover the range exactly, and can be walked most-notable-first.
 *
 * <p>Bands are not sized by a counting pass — that costs a query per band and was itself
 * refused by the endpoint when tried. They start wide and BISECT whenever the endpoint
 * refuses one, which is the same escalation every other unit uses.
 */
public final class RankBandWorkUnit implements WorkUnit<List<WikidataBinding>> {

    public static final String TYPE = "wikidata.rankBand";
    public static final String P_FROM = "rankFrom";
    public static final String P_UNTIL = "rankUntil";

    private final RuleNode node;
    private final long from;
    private final long until;
    private final WikidataSparqlClient client;

    public RankBandWorkUnit(
            RuleNode node, long from, long until, WikidataSparqlClient client) {
        this.node = node;
        this.from = from;
        this.until = until;
        this.client = client;
    }

    /**
     * Bands covering the whole measure, widest value first: {@code [64,∞) [32,64) …
     * [1,2) [0,1)}. Doubling widths approximate the measure's distribution — sitelink
     * counts are heavily skewed, so equal-width bands would put nearly everything in the
     * lowest one — and the executor narrows any band that still proves too heavy.
     */
    public static List<RankBandWorkUnit> descendingBands(
            RuleNode node, WikidataSparqlClient client, long topThreshold) {
        List<RankBandWorkUnit> bands = new ArrayList<>();
        long lower = topThreshold;
        bands.add(new RankBandWorkUnit(node, lower, RuleNode.UNBOUNDED, client));
        while (lower > 1) {
            long next = lower / 2;
            bands.add(new RankBandWorkUnit(node, next, lower, client));
            lower = next;
        }
        bands.add(new RankBandWorkUnit(node, 0, 1, client));
        return bands;
    }

    public long from() {
        return from;
    }

    public long until() {
        return until;
    }

    @Override
    public WorkDescriptor descriptor() {
        String band = from + ".." + (until == RuleNode.UNBOUNDED ? "" : until);
        return new WorkDescriptor(
                TYPE,
                node.name() + "@" + band,
                node.name() + " rank band [" + from + ", "
                        + (until == RuleNode.UNBOUNDED ? "∞" : until) + ")",
                Map.of(P_FROM, String.valueOf(from), P_UNTIL, String.valueOf(until)));
    }

    @Override
    public List<WikidataBinding> execute() throws Exception {
        return client.query(RuleNodeQueryBuilder.valuesQuery(banded()));
    }

    /** The node restricted to this band. Copied, so sibling bands never see each other's
     *  restriction — the executor may hold several at once after a split. */
    private RuleNode banded() {
        return node.backboneCopy(node.limit()).rankBand(from, until);
    }

    @Override
    public List<? extends WorkUnit<List<WikidataBinding>>> split() {
        if (until == RuleNode.UNBOUNDED) {
            // The open top band cannot be halved — there is no upper bound to halve
            // towards — so it is raised instead: the part above twice the threshold, and
            // the finite remainder below it. Together they still cover [from, ∞).
            long raised = Math.max(from + 1, from * 2);
            return List.of(
                    new RankBandWorkUnit(node, raised, RuleNode.UNBOUNDED, client),
                    new RankBandWorkUnit(node, from, raised, client));
        }
        if (until - from < 2) {
            return List.of();   // a single measure value: nothing left to narrow
        }
        long middle = from + (until - from) / 2;
        return List.of(
                new RankBandWorkUnit(node, middle, until, client),
                new RankBandWorkUnit(node, from, middle, client));
    }
}
