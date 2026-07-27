package quiz.transform.ui;

import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentProvider;
import quiz.enrichment.EnrichmentRequest;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DBpedia identity + media discovery adapted to the reusable enrichment model. */
final class DBpediaImageEnrichmentProvider implements EnrichmentProvider {

    @Override public String name() {
        return "DBpedia";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        if (request == null || request.subject() == null
                || request.targetField() == null || request.targetField().isBlank()) {
            return false;
        }
        String id = request.subject().id();
        String name = request.subject().displayName();
        return (id != null && id.matches("Q\\d+"))
                || (name != null && !name.isBlank());
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException("DBpedia requires a QID or display name");
        }
        String id = request.subject().id();
        String qid = id != null && id.matches("Q\\d+") ? id : null;
        Query<List<DBpediaLookup.ImageHit>> delegate =
                DBpediaLookup.images(qid, request.subject().displayName());

        return new Query<>() {
            @Override public String purpose() { return delegate.purpose(); }
            @Override public String skeleton() { return delegate.skeleton(); }
            @Override public String queryType() { return delegate.queryType(); }
            @Override public String description() { return delegate.description(); }
            @Override public Map<String, String> parameters() { return delegate.parameters(); }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                return proposal(request, delegate.execute(context), qid != null);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.media().size();
            }

            @Override public String summary(EnrichmentProposal result) {
                return rowCount(result) + " media candidate(s)";
            }
        };
    }

    static EnrichmentProposal proposal(
            EnrichmentRequest request,
            List<DBpediaLookup.ImageHit> hits,
            boolean qidMatch) {
        Map<String, List<DBpediaLookup.ImageHit>> byResource = new LinkedHashMap<>();
        for (DBpediaLookup.ImageHit hit : hits) {
            String resource = hit.resourceUrl() == null || hit.resourceUrl().isBlank()
                    ? "dbpedia:" + request.subject().displayName() : hit.resourceUrl();
            byResource.computeIfAbsent(resource, ignored -> new ArrayList<>()).add(hit);
        }

        List<EnrichmentProposal.IdentityCandidate> identities = new ArrayList<>();
        List<EnrichmentProposal.MediaCandidate> media = new ArrayList<>();
        int identityIndex = 0;
        int mediaIndex = 0;
        for (Map.Entry<String, List<DBpediaLookup.ImageHit>> entry : byResource.entrySet()) {
            String identityId = "dbpedia-" + identityIndex++;
            String resource = entry.getKey();
            String canonicalName = resourceName(resource, request.subject().displayName());
            EnrichmentProposal.SourceRef source =
                    new EnrichmentProposal.SourceRef("DBpedia", resource, resource);
            identities.add(new EnrichmentProposal.IdentityCandidate(
                    identityId,
                    canonicalName,
                    canonicalName.equals(request.subject().displayName())
                            ? List.of() : List.of(request.subject().displayName()),
                    "DBpedia entity with image data",
                    source,
                    qidMatch ? 0.99 : 0.75,
                    List.of(qidMatch
                            ? "Matched through Wikidata identifier " + request.subject().id()
                            : "Matched through the display name or a DBpedia redirect")));
            for (DBpediaLookup.ImageHit hit : entry.getValue()) {
                media.add(new EnrichmentProposal.MediaCandidate(
                        "dbpedia-image-" + mediaIndex++,
                        identityId,
                        request.targetField(),
                        hit.imageUrl(),
                        hit.imageUrl(),
                        source,
                        "foaf:depiction / dbo:thumbnail",
                        qidMatch ? 0.98 : 0.72,
                        "",
                        "",
                        request.collection()));
            }
        }
        return new EnrichmentProposal(request.subject(), identities, List.of(), media);
    }

    private static String resourceName(String resource, String fallback) {
        int slash = resource.lastIndexOf('/');
        String name = slash >= 0 ? resource.substring(slash + 1) : "";
        if (name.isBlank() || name.startsWith("dbpedia:")) {
            return fallback;
        }
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Keep DBpedia's resource spelling.
        }
        return name.replace('_', ' ');
    }
}
