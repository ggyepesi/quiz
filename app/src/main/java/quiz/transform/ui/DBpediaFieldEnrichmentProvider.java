package quiz.transform.ui;

import wikidata.WikidataIds;

import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentProvider;
import quiz.enrichment.EnrichmentRequest;
import quiz.enrichment.FieldValueCompatibility;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fills a field from a DBpedia (Wikipedia infobox) property, joined to the subject by
 * {@code owl:sameAs} on its Wikidata QID — the Wikipedia fallback for values Wikidata lacks,
 * e.g. a country's official long name ({@code dbo:longName}) or a population Wikidata is
 * missing. It proposes {@link EnrichmentProposal.ReviewAction#FILL_IF_EMPTY}, so a value only
 * lands where the field is empty ("only when Wikidata lacks it"); an existing value is never
 * silently overwritten (that becomes an explicit REPLACE tick, like the Wikidata provider).
 *
 * <p>Handles a field whose source is {@link FieldSourceType#DBPEDIA}; a source of any other
 * type yields a null property so {@link #supports} rejects it — the mirror of
 * {@link quiz.enrichment.WikimediaFieldEnrichmentProvider} which serves only SPARQL sources.
 */
final class DBpediaFieldEnrichmentProvider implements EnrichmentProvider {

    private final String propertyName;   // a DBpedia dbo:/dbp: property, e.g. "longName"
    private final String propertyLabel;

    DBpediaFieldEnrichmentProvider(FieldSourceMapping source) {
        this.propertyName = source == null || source.sourceType() != FieldSourceType.DBPEDIA
                ? null : source.propertyPid();
        this.propertyLabel = source == null || source.propertyLabel() == null
                ? "" : source.propertyLabel();
    }

    @Override public String name() {
        return "DBpedia values";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && request.subject().id() != null
                && WikidataIds.isQid(request.subject().id())
                && propertyName != null && !propertyName.isBlank();
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException(
                    "DBpedia value discovery needs a QID and a DBpedia property");
        }
        Query<List<String>> delegate =
                DBpediaLookup.values(request.subject().id(), propertyName);
        return new Query<>() {
            @Override public String purpose() { return "Read a DBpedia (Wikipedia) value"; }
            @Override public String skeleton() { return delegate.skeleton(); }
            @Override public String queryType() { return delegate.queryType(); }
            @Override public String description() { return delegate.description(); }
            @Override public Map<String, String> parameters() { return delegate.parameters(); }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                return proposal(request, delegate.execute(context));
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }

            @Override public String summary(EnrichmentProposal result) {
                return rowCount(result) + " value candidate(s)";
            }
        };
    }

    private EnrichmentProposal proposal(EnrichmentRequest request, List<String> values) {
        String qid = request.subject().id();
        String identityId = "dbpedia-field";
        EnrichmentProposal.SourceRef source = new EnrichmentProposal.SourceRef(
                "DBpedia", qid, "http://dbpedia.org/sparql", propertyName, propertyLabel, "");
        EnrichmentProposal.IdentityCandidate identity =
                new EnrichmentProposal.IdentityCandidate(
                        identityId, request.subject().displayName(), List.of(),
                        "DBpedia (Wikipedia infobox) via owl:sameAs", source, 0.9,
                        List.of("Joined to DBpedia by Wikidata identifier " + qid));

        List<EnrichmentProposal.FieldCandidate> fields = new ArrayList<>();
        String lexical = values == null || values.isEmpty() ? null : values.get(0);
        if (lexical != null && !lexical.isBlank()) {
            Object value = typedValue(request, lexical);
            String incompatibility = FieldValueCompatibility.problem(request.targetSchema(), value);
            fields.add(new EnrichmentProposal.FieldCandidate(
                    "dbpedia-" + propertyName.toLowerCase(Locale.ROOT),
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
        return new EnrichmentProposal(request.subject(), List.of(identity), fields, List.of());
    }

    /** Preserve numeric fields as numbers instead of storing a DBpedia literal such as
     *  population as text. Other ordered values (notably ISO dates) remain strings. */
    static Object typedValue(EnrichmentRequest request, String lexical) {
        if (lexical == null || request == null || request.targetSchema() == null) {
            return lexical;
        }
        objectview.field.FieldKind expected = request.targetSchema().collection()
                ? request.targetSchema().valueKind() : request.targetSchema().kind();
        if (expected != objectview.field.FieldKind.ORDERED
                || !lexical.trim().matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) {
            return lexical;
        }
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(lexical.trim());
            try {
                return number.longValueExact();
            } catch (ArithmeticException notWholeOrTooLarge) {
                return number.doubleValue();
            }
        } catch (NumberFormatException invalidNumber) {
            return lexical;
        }
    }
}
