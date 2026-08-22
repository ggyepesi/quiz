package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;

import wikidata.WikidataIds;

import work.Query;
import work.QueryContext;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.RuleDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one explicitly selected Wikidata property value for the subject and proposes it
 * as a field value. Selection comes from a sample entity's real claims; field names carry
 * no source-specific meaning here. The provider only proposes; the
 * value lands via the ordinary review → Correction overlay, so it fills an existing
 * field or a newly added one identically.
 */
public final class WikimediaFieldEnrichmentProvider implements EnrichmentProvider {

    private final WikimediaEntityLookup lookup;
    // The property CHOSEN from the sample entity's real claims.
    private final String propertyPid;
    private final String propertyLabel;
    private final RuleDirection direction;
    // Supplied by whoever owns the pool; null keeps the old value-only behaviour.
    private final ReferenceResolver references;

    /** Read the property chosen from the sample entity's claims. */
    public WikimediaFieldEnrichmentProvider(String propertyPid) {
        this(propertyPid, null, RuleDirection.ROOT_TO_ITEM,
                WikimediaEntityLookup.defaultFetcher());
    }

    /** Execute the same source-rule representation used by ModelBuilder. Only a Wikidata
     *  (SPARQL) source is served here; any other source type yields a null PID so
     *  {@link #supports} rejects it rather than reading a DBpedia/etc. property as a
     *  Wikidata claim. */
    public WikimediaFieldEnrichmentProvider(FieldSourceMapping source) {
        this(source, null);
    }

    /** With a resolver, an entity-valued property fills a reference field; without one
     *  it behaves exactly as before. */
    public WikimediaFieldEnrichmentProvider(
            FieldSourceMapping source, ReferenceResolver references) {
        this(source == null || source.sourceType() != FieldSourceType.SPARQL
                        ? null : source.propertyPid(),
                source == null ? null : source.propertyLabel(),
                source == null ? RuleDirection.ROOT_TO_ITEM : source.direction(),
                WikimediaEntityLookup.defaultFetcher(), references);
    }

    WikimediaFieldEnrichmentProvider(
            String propertyPid, WikimediaEntityLookup.JsonFetcher fetcher) {
        this(propertyPid, null, RuleDirection.ROOT_TO_ITEM, fetcher, null);
    }

    WikimediaFieldEnrichmentProvider(
            String propertyPid, String propertyLabel, RuleDirection direction,
            WikimediaEntityLookup.JsonFetcher fetcher) {
        this(propertyPid, propertyLabel, direction, fetcher, null);
    }

    WikimediaFieldEnrichmentProvider(
            String propertyPid, String propertyLabel, RuleDirection direction,
            WikimediaEntityLookup.JsonFetcher fetcher, ReferenceResolver references) {
        this.references = references;
        this.propertyPid = propertyPid == null || propertyPid.isBlank()
                ? null : propertyPid.trim();
        this.propertyLabel = propertyLabel == null ? "" : propertyLabel.trim();
        this.direction = direction == null ? RuleDirection.ROOT_TO_ITEM : direction;
        this.lookup = new WikimediaEntityLookup(fetcher);
    }

    @Override public String name() {
        return "Wikimedia values";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && request.subject().id() != null
                && WikidataIds.isQid(request.subject().id())
                && propertyPid != null
                && direction == RuleDirection.ROOT_TO_ITEM;
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException(
                    "Wikimedia value discovery needs a QID and a chosen property");
        }
        String qid = request.subject().id();
        String property = propertyPid;
        return new Query<>() {
            @Override public String purpose() {
                return "Read a Wikidata property value";
            }

            @Override public String skeleton() {
                return "wbgetentities claim " + property;
            }

            @Override public String queryType() {
                return "Wikimedia API";
            }

            @Override public String description() {
                return "Wikimedia value discovery";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("qid", qid, "property", property);
            }

            @Override public EnrichmentProposal execute(QueryContext context)
                    throws Exception {
                WikimediaEntityLookup.EntityRecord entity =
                        lookup.byQid(qid).execute(context);

                String identityId = "wikimedia-wikidata";
                SourceRef source = new SourceRef(
                        "Wikidata", qid, "https://www.wikidata.org/wiki/" + qid,
                        property, propertyLabel, direction.name());
                EnrichmentProposal.IdentityCandidate identity =
                        new EnrichmentProposal.IdentityCandidate(
                                identityId, request.subject().displayName(), List.of(),
                                "Approved Wikidata entity", source, 1.0,
                                List.of("Resolved from Wikidata identifier " + qid));

                List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
                Object value = bestValue(entity.claims(property));
                // Entity-valued claims are QIDs. A text field wants the entity's label;
                // a reference field wants the entity itself, resolved through the pool
                // that owns instance identity. Both need the label, so it is fetched
                // once here either way.
                boolean createdReference = false;
                if (value instanceof String entityQid && WikidataIds.isQid(entityQid)
                        && (textTarget(request) || referenceTarget(request))) {
                    String label = lookup.labels(List.of(entityQid)).execute(context)
                                         .getOrDefault(entityQid, entityQid);
                    if (textTarget(request)) {
                        value = label;
                    } else if (references == null) {
                        // No resolver: leave the QID, so compatibility reports the
                        // mismatch rather than the field silently filling with a string.
                        value = entityQid;
                    } else {
                        ReferenceResolver.Resolved resolved = references.resolve(
                                entityQid, label, targetTypeOf(request));
                        value = resolved == null ? entityQid : resolved.value();
                        createdReference = resolved != null && resolved.created();
                    }
                }
                if (value != null) {
                    String incompatibility = FieldValueCompatibility.problem(
                            request.targetSchema(), value);
                    fields.add(new EnrichmentProposal.FieldCandidate(
                            "wikidata-" + property.toLowerCase(Locale.ROOT),
                            identityId,
                            request.targetField(),
                            request.currentValue(),
                            value,
                            source,
                            incompatibility == null && request.collection()
                                    ? EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                                    : incompatibility == null && request.currentValue() != null
                                    ? EnrichmentProposal.ReviewAction.REPLACE
                                    : incompatibility == null
                                    ? EnrichmentProposal.ReviewAction.FILL_IF_EMPTY
                                    : EnrichmentProposal.ReviewAction.IGNORE,
                            incompatibility,
                            request.collection()));
                }
                return new EnrichmentProposal(
                        request.subject(), List.of(identity), fields, List.of());
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }
        };
    }

    /** Pick the newest dated non-deprecated claim; rank breaks otherwise-equal ties. */
    private static Object bestValue(List<WikimediaEntityLookup.Claim> claims) {
        WikimediaEntityLookup.Claim best = null;
        for (WikimediaEntityLookup.Claim claim : claims) {
            if (claim.deprecated()) {
                continue;
            }
            if (best == null || newerThan(claim, best)) {
                best = claim;
            }
        }
        return best == null ? null : formatValue(best.value());
    }

    /** A field that holds an entity, not a rendering of one. */
    private static boolean referenceTarget(EnrichmentRequest request) {
        return kindOf(request) == objectview.field.FieldKind.REFERENCE;
    }

    /** The class the field expects its targets to be, so a created instance is stamped
     *  as one instead of joining the pool untyped. */
    private static String targetTypeOf(EnrichmentRequest request) {
        objectview.field.FieldRef schema = request.targetSchema();
        return schema == null ? null : schema.targetType();
    }

    private static objectview.field.FieldKind kindOf(EnrichmentRequest request) {
        objectview.field.FieldRef schema = request.targetSchema();
        if (schema == null) return null;
        return schema.collection() ? schema.valueKind() : schema.kind();
    }

    private static boolean textTarget(EnrichmentRequest request) {
        return kindOf(request) == objectview.field.FieldKind.TEXT;
    }

    /** Format a claim value by its SHAPE, so a property chosen from real data works
     *  regardless of type: a quantity → a number, a time → its ISO date (year for a
     *  Jan-1 value), an entity → its QID, a literal → its string. Shape (not the snak's
     *  {@code datatype}) so a value with no declared datatype still classifies. */
    private static Object formatValue(WikimediaEntityLookup.TypedValue value) {
        Object raw = value.value();
        if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("amount")) {
                return quantityAmount(value);
            }
            if (map.get("time") instanceof String time) {
                return timeValue(time);
            }
            if (map.get("id") instanceof String id) {
                return id;
            }
            return null;
        }
        if (raw instanceof String text) {
            return text.isBlank() ? null : text;
        }
        return raw;
    }

    /** A Wikidata time is {@code +2020-08-21T00:00:00Z}; return the year for a Jan-1
     *  value (year-precision dates), else the {@code yyyy-MM-dd} date. */
    private static Object timeValue(String time) {
        String date = time.replaceFirst("^\\+", "").replaceFirst("T.*", "");
        if (date.endsWith("-01-01")) {
            return date.substring(0, date.indexOf('-', 1));
        }
        return date;
    }

    private static boolean newerThan(
            WikimediaEntityLookup.Claim candidate,
            WikimediaEntityLookup.Claim current) {
        String candidateTime = pointInTime(candidate);
        String currentTime = pointInTime(current);
        if (!candidateTime.equals(currentTime)) {
            if (candidateTime.isBlank()) return false;
            if (currentTime.isBlank()) return true;
            return candidateTime.compareTo(currentTime) > 0;
        }
        return rankScore(candidate.rank()) > rankScore(current.rank());
    }

    /** P585 time objects use a fixed-width signed ISO representation, sortable as text. */
    private static String pointInTime(WikimediaEntityLookup.Claim claim) {
        for (WikimediaEntityLookup.TypedValue qualifier
                : claim.qualifiers().getOrDefault("P585", List.of())) {
            if (qualifier.value() instanceof Map<?, ?> map) {
                Object time = map.get("time");
                if (time != null) return time.toString();
            }
        }
        return "";
    }

    private static int rankScore(String rank) {
        return "preferred".equals(rank) ? 2 : "normal".equals(rank) ? 1 : 0;
    }

    /** A Wikidata quantity value is {@code {amount:"+331002651", unit:"1", …}} — return
     *  the amount as a Long when it is integral (Jackson may have already typed it as a
     *  Double, so parse via BigDecimal), else a Double; strip the leading sign. */
    private static Object quantityAmount(WikimediaEntityLookup.TypedValue value) {
        if (!(value.value() instanceof Map<?, ?> map)) {
            return null;
        }
        Object amount = map.get("amount");
        if (amount == null) {
            return null;
        }
        String text = amount.toString();
        if (text.startsWith("+")) {
            text = text.substring(1);
        }
        try {
            double number = Double.parseDouble(text);
            // Present a whole quantity (population, area) as a Long, not 5.93E7; only
            // snap when it is integral and exactly representable in the long range.
            if (number == Math.rint(number) && !Double.isInfinite(number)
                    && Math.abs(number) < 9.007199254740992E15) {
                return (long) number;
            }
            return number;
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

}
