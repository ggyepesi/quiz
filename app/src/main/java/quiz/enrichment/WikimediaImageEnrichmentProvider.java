package quiz.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.explore.CommonsMedia;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the distinct images attached to a Wikidata identity and its English
 * Wikipedia article. P18 and P154 remain separately attributed candidates; the
 * Wikipedia page image is not assumed to be the same Commons file (it may be a
 * local, non-free image, as for Amnesty International).
 */
public final class WikimediaImageEnrichmentProvider implements EnrichmentProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WikimediaEntityLookup lookup;
    private final WikimediaEntityLookup.JsonFetcher fetcher;

    public WikimediaImageEnrichmentProvider() {
        this(WikimediaEntityLookup.defaultFetcher());
    }

    WikimediaImageEnrichmentProvider(
            WikimediaEntityLookup.JsonFetcher fetcher) {
        this.fetcher = fetcher;
        this.lookup = new WikimediaEntityLookup(fetcher);
    }

    @Override public String name() {
        return "Wikimedia";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.subject() != null
                && request.subject().id() != null
                && request.subject().id().matches("Q\\d+");
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        if (!supports(request)) {
            throw new IllegalArgumentException("Wikimedia image discovery requires a QID");
        }
        String qid = request.subject().id();
        return new Query<>() {
            @Override public String purpose() {
                return "Find Wikidata and Wikipedia images";
            }

            @Override public String skeleton() {
                return "Wikidata P18/P154 + English Wikipedia page image";
            }

            @Override public String queryType() {
                return "Wikimedia API";
            }

            @Override public String description() {
                return "Wikimedia image discovery";
            }

            @Override public Map<String, String> parameters() {
                return Map.of("qid", qid);
            }

            @Override public EnrichmentProposal execute(QueryContext context)
                    throws Exception {
                WikimediaEntityLookup.EntityRecord entity =
                        lookup.byQid(qid).execute(context);
                List<EnrichmentProposal.IdentityCandidate> identities =
                        new ArrayList<>();
                List<EnrichmentProposal.MediaCandidate> media =
                        new ArrayList<>();
                Set<String> seenUrls = new LinkedHashSet<>();

                String wikidataIdentity = "wikimedia-wikidata";
                EnrichmentProposal.SourceRef wikidataSource =
                        new EnrichmentProposal.SourceRef(
                                "Wikidata", qid,
                                "https://www.wikidata.org/wiki/" + qid);
                identities.add(identity(wikidataIdentity, request, wikidataSource,
                        "Approved Wikidata entity"));

                addClaims(entity, "P18", "Wikidata P18 image", 0.98,
                        wikidataIdentity, wikidataSource, request, media, seenUrls);
                addClaims(entity, "P154", "Wikidata P154 logo image", 0.94,
                        wikidataIdentity, wikidataSource, request, media, seenUrls);

                String articleTitle =
                        entity.sitelink("enwiki");
                if (!articleTitle.isBlank()) {
                    try {
                        addWikipediaImage(context, request, articleTitle,
                                identities, media, seenUrls);
                    } catch (Exception ex) {
                        // A local Wikipedia failure must not hide valid P18/P154 results.
                        context.message("English Wikipedia image lookup failed: "
                                + ex.getMessage());
                    }
                }
                return new EnrichmentProposal(
                        request.subject(), identities, List.of(), media);
            }

            @Override public int rowCount(EnrichmentProposal result) {
                return result == null ? 0 : result.media().size();
            }
        };
    }

    private void addWikipediaImage(
            QueryContext context,
            EnrichmentRequest request,
            String title,
            List<EnrichmentProposal.IdentityCandidate> identities,
            List<EnrichmentProposal.MediaCandidate> media,
            Set<String> seenUrls) throws Exception {
        URI api = wikipediaApi(title);
        JsonNode root = context.step(
                "Read English Wikipedia page image",
                "Wikipedia API",
                "pageimages original/thumbnail",
                Map.of("title", title),
                step -> {
                    step.request(api.toString());
                    var json = MAPPER.readTree(fetcher.fetch(api));
                    step.summary("Wikipedia page image loaded");
                    return json;
                });
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isObject()) {
            return;
        }
        var values = pages.elements();
        while (values.hasNext()) {
            JsonNode page = values.next();
            String imageUrl = page.path("original").path("source").asText("");
            if (imageUrl.isBlank()) {
                imageUrl = page.path("thumbnail").path("source").asText("");
            }
            if (imageUrl.isBlank() || !seenUrls.add(imageUrl)) {
                continue;
            }
            String identityId = "wikimedia-enwiki";
            EnrichmentProposal.SourceRef source =
                    new EnrichmentProposal.SourceRef(
                            "Wikipedia (English)", title, wikipediaPage(title));
            identities.add(identity(identityId, request, source,
                    "English Wikipedia article linked from Wikidata"));
            media.add(new EnrichmentProposal.MediaCandidate(
                    "wikipedia-page-image",
                    identityId,
                    request.targetField(),
                    imageUrl,
                    imageUrl,
                    source,
                    "English Wikipedia page image",
                    0.92,
                    "",
                    "Check the file page; Wikipedia-local images may be non-free",
                    request.collection()));
            return;
        }
    }

    private static void addClaims(
            WikimediaEntityLookup.EntityRecord entity,
            String property,
            String method,
            double confidence,
            String identityId,
            EnrichmentProposal.SourceRef source,
            EnrichmentRequest request,
            List<EnrichmentProposal.MediaCandidate> media,
            Set<String> seenUrls) {
        int index = 0;
        for (WikimediaEntityLookup.Claim claim : entity.claims(property)) {
            if (claim.deprecated()) {
                continue;
            }
            String filename = claim.value().stringValue();
            if (filename.isBlank()) {
                continue;
            }
            String url = CommonsMedia.filePathUrl(filename);
            if (!seenUrls.add(url)) {
                continue;
            }
            media.add(new EnrichmentProposal.MediaCandidate(
                    "wikidata-" + property.toLowerCase() + "-" + index++,
                    identityId,
                    request.targetField(),
                    url,
                    url,
                    source,
                    method + ": " + filename,
                    confidence,
                    "",
                    "Check the linked Commons file page",
                    request.collection()));
        }
    }

    private static EnrichmentProposal.IdentityCandidate identity(
            String id,
            EnrichmentRequest request,
            EnrichmentProposal.SourceRef source,
            String description) {
        return new EnrichmentProposal.IdentityCandidate(
                id,
                request.subject().displayName(),
                List.of(),
                description,
                source,
                1.0,
                List.of("Resolved from Wikidata identifier "
                        + request.subject().id()));
    }

    private static URI wikipediaApi(String title) {
        return URI.create("https://en.wikipedia.org/w/api.php"
                + "?action=query&prop=pageimages&piprop=original%7Cthumbnail"
                + "&pithumbsize=1200&titles=" + encode(title)
                + "&format=json");
    }

    private static String wikipediaPage(String title) {
        return "https://en.wikipedia.org/wiki/"
                + encode(title.replace(' ', '_'));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

}
