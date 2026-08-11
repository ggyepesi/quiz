package wikidata.explore.rule;

import wikidata.WikidataIds;

import wikidata.explore.model.RuleDirection;
import wikidata.explore.filter.WikidataValueFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RuleNode {

    // -----------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------

    private String name;
    private String itemVar;

    // -----------------------------------------------------------------
    // Source / property
    // -----------------------------------------------------------------

    private String sourceQid    = "";
    private String sourceLabel  = "";
    // Membership accepts instance-of sourceQid OR any of these (e.g. add
    // zodiacal constellation so Aries/Cancer count as constellations).
    private final Set<String> additionalSourceQids = new LinkedHashSet<>();
    private String propertyPid  = "";
    private String propertyLabel = "";
    private RuleDirection direction = RuleDirection.ITEM_TO_ROOT;

    // Membership constraint on the node's values: ?value <membershipPid>
    // <membershipQid> (e.g. P31 Q523 = "instance of star"). Used by a
    // CHILD_OBJECTS edge so the referenced class's instances are filtered to
    // that class — otherwise an edge like P59 "constellation" returns every
    // object in the constellation (galaxies, nebulae, …), not just stars.
    private String membershipPid = "";
    private String membershipQid = "";

    // -----------------------------------------------------------------
    // Label config
    // -----------------------------------------------------------------

    private RuleLabelConfig labelConfig = new RuleLabelConfig();

    // -----------------------------------------------------------------
    // Included direct fields
    // -----------------------------------------------------------------

    private final List<RuleIncludedField> includedFields = new ArrayList<>();

    // -----------------------------------------------------------------
    // Filters / exclusions / edges
    // -----------------------------------------------------------------

    private int limit = 200;

    // Sort the node's entities by one of its included fields before the limit
    // (e.g. apparentMagnitude ascending = brightest stars first). Blank = none.
    private String  sortFieldName = "";
    private boolean sortDescending = false;

    private final Set<String>  includedQids = new LinkedHashSet<>();
    private final Set<String>  excludedQids = new LinkedHashSet<>();

    private final List<PredicateObjectExclusion> excludedPredicateObjects =
            new ArrayList<>();

    private final List<WikidataValueFilter> valueFilters =
            new ArrayList<>();

    private final List<RuleEdge> edges = new ArrayList<>();

    // -----------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------

    public RuleNode(String name, String itemVar) {
        this.name    = name;
        this.itemVar = itemVar;
    }

    /**
     * Copy of this node without child edges or included fields, with the
     * given limit — used to sample a node's instances cheaply.
     */
    /**
     * The membership copy used to discover members: a {@link #sampleCopy} that also
     * carries what membership actually depends on — the class RANKING (which decides
     * WHICH {@code limit} instances are kept) and every REQUIRED field, as a
     * constraint. sampleCopy alone drops both, which silently produced an unranked
     * slice whose members need not have the required property at all.
     */
    public RuleNode backboneCopy(int limit) {
        RuleNode s = sampleCopy(limit);
        s.rankBySitelinks(rankBySitelinks());
        s.rankPropertyPid(rankPropertyPid());
        s.rankDescending(rankDescending());
        s.rankBand(rankFrom(), rankUntil());
        for (RuleIncludedField f : includedFields()) {
            if (f != null && !f.optional()) {
                s.addMembershipConstraint(f);
            }
        }
        return s;
    }

    public RuleNode sampleCopy(int limit) {
        RuleNode s = new RuleNode(name, itemVar);

        s.sourceQid(sourceQid);
        s.sourceLabel(sourceLabel);
        // Multi-target membership (e.g. P1411 -> 59 award categories, all in the
        // additional set with a blank sourceQid): without these the sample copy
        // has NO membership and its query becomes "VALUES ?value { }" -> 0 rows,
        // so Discover/Sample find nothing for a multi-target class.
        additionalSourceQids().forEach(s::addAdditionalSourceQid);
        s.membershipPid(membershipPid());
        s.membershipQid(membershipQid());
        s.requireSitelink(requireSitelink());
        s.propertyPid(propertyPid);
        s.propertyLabel(propertyLabel);
        s.direction(direction());
        s.limit(limit);
        s.labelConfig(labelConfig());

        s.includedQidsText(includedQidsText());
        s.excludedQidsText(excludedQidsText());
        s.excludedPredicateObjectsText(excludedPredicateObjectsText());

        valueFilters().forEach(s::addValueFilter);

        return s;
    }

    // -----------------------------------------------------------------
    // Accessors — identity
    // -----------------------------------------------------------------

    public String name() { return name; }
    public void   name(String name) { this.name = name; }

    public String itemVar() { return itemVar; }
    public void   itemVar(String itemVar) { this.itemVar = itemVar; }

    // -----------------------------------------------------------------
    // Accessors — source / property
    // -----------------------------------------------------------------

    public String sourceQid()  { return sourceQid; }
    public void   sourceQid(String sourceQid)
        { this.sourceQid = cleanQid(sourceQid); }

    public Set<String> additionalSourceQids() { return additionalSourceQids; }

    public RuleNode addAdditionalSourceQid(String qid) {
        qid = cleanQid(qid);
        if (!qid.isBlank()) additionalSourceQids.add(qid);
        return this;
    }

    /** All membership type QIDs (sourceQid + extras), in order, blanks dropped. */
    public java.util.List<String> allSourceQids() {
        java.util.List<String> out = new ArrayList<>();
        if (sourceQid != null && !sourceQid.isBlank()) out.add(sourceQid);
        for (String q : additionalSourceQids) if (q != null && !q.isBlank()) out.add(q);
        return out;
    }

    public String sourceLabel() { return sourceLabel; }
    public void   sourceLabel(String sourceLabel)
        { this.sourceLabel = sourceLabel == null ? "" : sourceLabel.trim(); }

    public String propertyPid()  { return propertyPid; }
    public void   propertyPid(String propertyPid)
        { this.propertyPid = cleanPid(propertyPid); }

    public String propertyLabel() { return propertyLabel; }
    public void   propertyLabel(String propertyLabel)
        { this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim(); }

    public String membershipPid() { return membershipPid; }
    public void   membershipPid(String pid) { this.membershipPid = cleanPid(pid); }

    public String membershipQid() { return membershipQid; }
    public void   membershipQid(String qid) { this.membershipQid = cleanQid(qid); }

    // "Notable only": require an English Wikipedia article (sitelink). A
    // selective entry that bounds a huge class to its notable members.
    private boolean requireSitelink = false;
    public boolean requireSitelink() { return requireSitelink; }
    public void    requireSitelink(boolean b) { this.requireSitelink = b; }

    // Class-level ranking: keep the top `limit` instances by this measure.
    // rankBySitelinks → wikibase:sitelinks (notability); else rankPropertyPid
    // (a sortable field's property, e.g. brightness/area). Empty = order by label.
    private boolean rankBySitelinks = false;
    private String  rankPropertyPid = "";
    private boolean rankDescending  = true;
    public boolean rankBySitelinks() { return rankBySitelinks; }
    public void    rankBySitelinks(boolean b) { this.rankBySitelinks = b; }
    public String  rankPropertyPid() { return rankPropertyPid == null ? "" : rankPropertyPid; }
    public void    rankPropertyPid(String p) { this.rankPropertyPid = p == null ? "" : p.trim(); }
    public boolean rankDescending() { return rankDescending; }
    public void    rankDescending(boolean d) { this.rankDescending = d; }
    public boolean hasRank() { return rankBySitelinks || !rankPropertyPid().isBlank(); }

    // A half-open band [rankFrom, rankUntil) on the ranking measure, used to partition a
    // membership scan the endpoint will not complete in one request. UNBOUNDED = absent.
    // The measure is monotone, so descending bands visit the most notable members first
    // and the scan can stop as soon as `limit` of them have been collected.
    public static final long UNBOUNDED = -1;
    private long rankFrom = UNBOUNDED;
    private long rankUntil = UNBOUNDED;

    public long rankFrom() { return rankFrom; }
    public long rankUntil() { return rankUntil; }
    public boolean hasRankBand() { return rankFrom != UNBOUNDED || rankUntil != UNBOUNDED; }

    /** Restricts this node to one band of the ranking measure. */
    public RuleNode rankBand(long from, long until) {
        if (from != UNBOUNDED && until != UNBOUNDED && from >= until) {
            throw new IllegalArgumentException(
                    "Empty rank band [" + from + ", " + until + ")");
        }
        this.rankFrom = from;
        this.rankUntil = until;
        return this;
    }

    public boolean hasMembershipFilter() {
        return membershipPid != null && !membershipPid.isBlank()
                && membershipQid != null && !membershipQid.isBlank();
    }

    public RuleDirection direction() { return direction; }
    public void direction(RuleDirection direction)
        { this.direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction; }

    // -----------------------------------------------------------------
    // Accessors — label config
    // -----------------------------------------------------------------

    public RuleLabelConfig labelConfig() { return labelConfig; }
    public void labelConfig(RuleLabelConfig labelConfig)
        { this.labelConfig = labelConfig == null ? new RuleLabelConfig() : labelConfig; }

    // -----------------------------------------------------------------
    // Accessors — included fields
    // -----------------------------------------------------------------

    // Fields the MEMBERSHIP query must satisfy, as constraints only: their triple
    // is emitted non-OPTIONAL and nothing is selected from them. A required field
    // has to narrow membership at the point membership is decided — the value
    // itself is fetched later, over the members, by the batched field capture.
    // Kept apart from includedFields precisely so it contributes no SELECT: a
    // multi-valued property in the select list yields one row per value, and the
    // LIMIT then counts pairs instead of instances (R11/R13).
    private final List<RuleIncludedField> membershipConstraints = new ArrayList<>();

    public List<RuleIncludedField> membershipConstraints() {
        return membershipConstraints;
    }

    public RuleNode addMembershipConstraint(RuleIncludedField field) {
        if (field != null) membershipConstraints.add(field);
        return this;
    }

    public List<RuleIncludedField> includedFields() { return includedFields; }

    public RuleNode addIncludedField(RuleIncludedField field) {
        if (field != null) includedFields.add(field);
        return this;
    }

    // -----------------------------------------------------------------
    // Accessors — limit / QIDs / exclusions / filters / edges
    // -----------------------------------------------------------------

    public int  limit() { return limit; }
    public void limit(int limit) { this.limit = Math.max(1, limit); }

    public String  sortFieldName() { return sortFieldName == null ? "" : sortFieldName; }
    public void    sortFieldName(String s) { this.sortFieldName = s == null ? "" : s.trim(); }
    public boolean sortDescending() { return sortDescending; }
    public void    sortDescending(boolean d) { this.sortDescending = d; }
    public boolean hasSort() { return sortFieldName != null && !sortFieldName.isBlank(); }

    public Set<String>  includedQids() { return includedQids; }
    public Set<String>  excludedQids()  { return excludedQids; }

    public List<PredicateObjectExclusion> excludedPredicateObjects()
        { return excludedPredicateObjects; }

    public List<WikidataValueFilter> valueFilters() { return valueFilters; }
    public List<RuleEdge>            edges()         { return edges; }

    public RuleNode addIncludedQid(String qid) {
        qid = cleanQid(qid);
        if (!qid.isBlank()) includedQids.add(qid);
        return this;
    }

    public RuleNode addExcludedQid(String qid) {
        qid = cleanQid(qid);
        if (!qid.isBlank()) excludedQids.add(qid);
        return this;
    }

    public RuleNode addValueFilter(WikidataValueFilter filter) {
        if (filter != null) valueFilters.add(filter);
        return this;
    }

    public RuleNode addEdge(RuleEdge edge) {
        if (edge != null) edges.add(edge);
        return this;
    }

    // -----------------------------------------------------------------
    // QID / exclusion text helpers
    // -----------------------------------------------------------------

    public String includedQidsText()          { return qidsText(includedQids); }
    public void   includedQidsText(String t)  { includedQids.clear();  includedQids.addAll(parseQids(t)); }

    public String excludedQidsText()          { return qidsText(excludedQids); }
    public void   excludedQidsText(String t)  { excludedQids.clear();  excludedQids.addAll(parseQids(t)); }

    public String excludedPredicateObjectsText()
        { return predicateObjectExclusionsText(excludedPredicateObjects); }

    public void excludedPredicateObjectsText(String text) {
        excludedPredicateObjects.clear();
        excludedPredicateObjects.addAll(parsePredicateObjectExclusions(text));
    }

    // -----------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------

    public String directionPreviewText() {
        return direction.previewText(
                name,
                propertyLabel == null || propertyLabel.isBlank()
                        ? propertyPid : propertyLabel,
                sourceLabel == null || sourceLabel.isBlank()
                        ? sourceQid : sourceLabel);
    }

    public String relationPhrase() {
        String left = sourceLabel == null || sourceLabel.isBlank()
                ? sourceQid : sourceLabel;
        String prop = propertyLabel == null || propertyLabel.isBlank()
                ? propertyPid : propertyLabel;
        return direction == RuleDirection.ITEM_TO_ROOT
                ? blankTo(name, "item") + " " + prop + " " + left
                : left + " " + prop + " " + blankTo(name, "item");
    }

    public String displayName() {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null || name.isBlank() ? "Node" : name);
        if (propertyPid != null && !propertyPid.isBlank())
            sb.append("  [").append(relationPhrase()).append("]");
        if (!includedQids.isEmpty())
            sb.append("  include ").append(includedQids.size())
              .append(" QID").append(includedQids.size() == 1 ? "" : "s");
        if (!excludedQids.isEmpty())
            sb.append("  exclude ").append(excludedQids.size())
              .append(" QID").append(excludedQids.size() == 1 ? "" : "s");
        if (!includedFields.isEmpty())
            sb.append("  +").append(includedFields.size()).append(" field")
              .append(includedFields.size() == 1 ? "" : "s");
        return sb.toString();
    }

    @Override public String toString() { return displayName(); }

    // -----------------------------------------------------------------
    // Static utilities
    // -----------------------------------------------------------------

    public static String cleanQid(String qid) {
        if (qid == null) return "";
        qid = qid.trim();
        if (qid.startsWith("wd:")) qid = qid.substring(3);
        return qid.trim();
    }

    public static String cleanPid(String pid) {
        if (pid == null) return "";
        pid = pid.trim();
        if (pid.startsWith("wdt:")) pid = pid.substring(4);
        return pid.trim();
    }

    public static Set<String> parseQids(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String token : text.split("[,;\\s]+")) {
            String qid = cleanQid(token);
            if (WikidataIds.isQid(qid)) out.add(qid);
        }
        return out;
    }

    public static String qidsText(Set<String> qids) {
        return qids == null || qids.isEmpty() ? "" : String.join(" ", qids);
    }

    public static List<PredicateObjectExclusion>
    parsePredicateObjectExclusions(String text) {
        List<PredicateObjectExclusion> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split("[,;\\s]+")) {
            String[] pieces = part.split("[:=]");
            if (pieces.length != 2) continue;
            String pid = cleanPid(pieces[0]);
            String qid = cleanQid(pieces[1]);
            if (WikidataIds.isPid(pid) && WikidataIds.isQid(qid))
                out.add(new PredicateObjectExclusion(pid, qid));
        }
        return out;
    }

    public static String predicateObjectExclusionsText(
            List<PredicateObjectExclusion> exclusions) {
        if (exclusions == null || exclusions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PredicateObjectExclusion e : exclusions) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(e.predicatePid()).append(":").append(e.objectQid());
        }
        return sb.toString();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    public record PredicateObjectExclusion(String predicatePid,
                                           String objectQid) {}
}
