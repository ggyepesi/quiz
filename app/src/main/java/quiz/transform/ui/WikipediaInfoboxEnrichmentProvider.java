package quiz.transform.ui;

import datasource.SourceRef;
import datasource.evidence.InfoboxParameters;
import datasource.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentProvider;
import quiz.enrichment.EnrichmentRequest;
import quiz.enrichment.FieldValueCompatibility;
import quiz.enrichment.WikimediaEntityLookup;
import wikidata.WikidataIds;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fills a field directly from a versioned native Wikipedia Infobox parameter. */
final class WikipediaInfoboxEnrichmentProvider implements EnrichmentProvider {
    private final String key;
    private final String label;

    WikipediaInfoboxEnrichmentProvider(FieldSourceMapping source) {
        key = source != null && source.sourceType() == FieldSourceType.WIKIPEDIA_INFOBOX
                ? source.propertyPid() : "";
        label = source == null ? "" : source.propertyLabel();
    }

    @Override public String name() { return "Wikipedia infobox values"; }
    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && WikidataIds.isQid(request.subject().id())
                && InfoboxParameters.Key.parse(key) != null;
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) throw new IllegalArgumentException(
                "Wikipedia infobox discovery needs a QID and Template.parameter key");
        return new Query<>() {
            @Override public String purpose() { return "Read a native Wikipedia infobox value"; }
            @Override public String skeleton() { return "QID -> Wikipedia article -> Infobox parameter"; }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() { return key; }
            @Override public Map<String, String> parameters() {
                return Map.of("qid", request.subject().id(), "parameter", key);
            }
            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                var entity = new WikimediaEntityLookup().byQid(request.subject().id())
                        .execute(context);
                String title = entity == null ? "" : entity.sitelink("enwiki");
                if (title == null || title.isBlank()) return empty(request);
                var infobox = new wikipedia.WikipediaInfoboxClient().byTitle(title).execute(context);
                if (infobox == null) return empty(request);
                // Whether this page's infobox is the template the key names is the key's
                // own question, asked the same way the domain-scale acquisition asks it.
                return proposal(request, infobox,
                        infobox.valueOf(InfoboxParameters.Key.parse(key)));
            }
            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.fields().size();
            }
        };
    }

    private EnrichmentProposal proposal(EnrichmentRequest request,
            InfoboxParameters infobox, String lexical) {
        if (lexical == null || lexical.isBlank()) return empty(request);
        String identityId = "wikipedia-infobox";
        SourceRef source = new SourceRef("Wikipedia", request.subject().id(),
                infobox.document().url(), key, label, infobox.document().revision());
        var identity = new EnrichmentProposal.IdentityCandidate(identityId,
                request.subject().displayName(), List.of(), "Native Wikipedia infobox",
                source, 0.85, List.of("Read from " + key));
        Object value = DBpediaFieldEnrichmentProvider.typedValue(request, lexical);
        String incompatibility = FieldValueCompatibility.problem(request.targetSchema(), value);
        var action = incompatibility != null ? EnrichmentProposal.ReviewAction.IGNORE
                : request.collection() ? EnrichmentProposal.ReviewAction.ADD_TO_COLLECTION
                : request.currentValue() == null ? EnrichmentProposal.ReviewAction.FILL_IF_EMPTY
                : EnrichmentProposal.ReviewAction.REPLACE;
        var field = new EnrichmentProposal.FieldCandidate(
                "wikipedia-infobox-" + Integer.toHexString(key.toLowerCase(Locale.ROOT).hashCode()),
                identityId, request.targetField(), request.currentValue(), value, source,
                action, incompatibility, request.collection());
        return new EnrichmentProposal(request.subject(), List.of(identity), List.of(field), List.of());
    }

    private static EnrichmentProposal empty(EnrichmentRequest request) {
        return new EnrichmentProposal(request.subject(), List.of(), List.of(), List.of());
    }
}
