package quiz.enrichment;

import wikidata.WikidataIds;

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
                && WikidataIds.isQid(request.subject().id());
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

                ImageQuery imageQuery = imageQueryFor(request.targetField());
                for (PropertyImage property : imageQuery.properties()) {
                    addClaims(entity, property.property(),
                            "Wikidata " + property.property() + " " + property.label(),
                            property.confidence(),
                            wikidataIdentity, wikidataSource, request, media, seenUrls);
                }

                // The article's lead image is a general photo of the subject — helpful for
                // a portrait, but not for a specific emblem (a flag/coat of arms field
                // wants P41/P94, not the country's page image), so it's off for those.
                if (imageQuery.includeWikipediaImage()) {
                    String articleTitle = entity.sitelink("enwiki");
                    if (!articleTitle.isBlank()) {
                        try {
                            addWikipediaImage(context, request, articleTitle,
                                    identities, media, seenUrls);
                        } catch (Exception ex) {
                            // A local Wikipedia failure must not hide valid claim results.
                            context.message("English Wikipedia image lookup failed: "
                                    + ex.getMessage());
                        }
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
            EnrichmentProposal.SourceRef propertySource =
                    new EnrichmentProposal.SourceRef(
                            source.kind(), source.sourceId(), source.recordUrl(), property);
            media.add(new EnrichmentProposal.MediaCandidate(
                    "wikidata-" + property.toLowerCase() + "-" + index++,
                    identityId,
                    request.targetField(),
                    url,
                    url,
                    propertySource,
                    method + ": " + filename,
                    confidence,
                    "",
                    "Check the linked Commons file page",
                    request.collection()));
        }
    }

    /** The Wikidata image properties that fit a target field: a flag field pulls P41,
     *  coat of arms P94, seal P158, logo P154, and anything else the general image set
     *  (P18 + P154). Matched on the field name (or dotted path leaf) by keyword, so
     *  {@code flagVersions}/{@code armsVersions}/{@code portrait} all route correctly. */
    static ImageQuery imageQueryFor(String field) {
        String f = field == null ? "" : field.toLowerCase(java.util.Locale.ROOT);
        if (mentions(f, "flag")) {
            return new ImageQuery(List.of(new PropertyImage("P41", "flag image", 0.98)), false);
        }
        if (mentions(f, "coat", "arms", "armorial", "escutcheon", "crest")) {
            return new ImageQuery(
                    List.of(new PropertyImage("P94", "coat of arms image", 0.98)), false);
        }
        if (mentions(f, "seal")) {
            return new ImageQuery(List.of(new PropertyImage("P158", "seal image", 0.96)), false);
        }
        if (mentions(f, "logo")) {
            return new ImageQuery(List.of(new PropertyImage("P154", "logo image", 0.96)), false);
        }
        return new ImageQuery(List.of(
                new PropertyImage("P18", "image", 0.98),
                new PropertyImage("P154", "logo image", 0.9)), true);
    }

    private static boolean mentions(String field, String... keywords) {
        for (String keyword : keywords) {
            if (field.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    record PropertyImage(String property, String label, double confidence) { }

    record ImageQuery(List<PropertyImage> properties, boolean includeWikipediaImage) { }

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
