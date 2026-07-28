package quiz.enrichment;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic image discovery from an object's originating record page. It understands
 * OpenGraph/Twitter metadata and relevant page images; it has no site-specific rules.
 */
public final class SourcePageImageEnrichmentProvider implements EnrichmentProvider {

    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile(
            "([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final PageFetcher fetcher;

    public SourcePageImageEnrichmentProvider() {
        // One polite HTTP path: UrlOpener (retry on 429, cross-protocol redirects,
        // contact User-Agent, self-throttle) instead of a private client per provider.
        this(uri -> {
            try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        });
    }

    SourcePageImageEnrichmentProvider(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override public String name() {
        return "Source pages";
    }

    @Override public boolean supports(EnrichmentRequest request) {
        return request != null && request.sources().stream()
                .anyMatch(source -> httpUri(source.recordUrl()) != null);
    }

    @Override public Query<EnrichmentProposal> discover(EnrichmentRequest request) {
        List<EnrichmentProposal.SourceRef> sources = request.sources().stream()
                .filter(source -> httpUri(source.recordUrl()) != null)
                .toList();
        return new Query<>() {
            @Override public String purpose() { return "Find images on source records"; }
            @Override public String skeleton() { return "Read source-page image metadata"; }
            @Override public String queryType() { return "HTTP"; }
            @Override public String description() { return "Source page image discovery"; }
            @Override public Map<String, String> parameters() {
                return Map.of("sources", Integer.toString(sources.size()));
            }

            @Override public EnrichmentProposal execute(QueryContext context) throws Exception {
                List<EnrichmentProposal.IdentityCandidate> identities = new ArrayList<>();
                List<EnrichmentProposal.MediaCandidate> media = new ArrayList<>();
                int sourceIndex = 0;
                int mediaIndex = 0;
                for (EnrichmentProposal.SourceRef source : sources) {
                    URI page = httpUri(source.recordUrl());
                    String html = context.step(
                            "Read " + source.kind() + " source page",
                            "HTTP",
                            "GET source record",
                            Map.of("url", page.toString()),
                            step -> {
                                step.request(page.toString());
                                String result = fetcher.fetch(page);
                                step.summary(result.length() + " characters");
                                return result;
                            });
                    String identityId = "source-page-" + sourceIndex++;
                    identities.add(new EnrichmentProposal.IdentityCandidate(
                            identityId,
                            request.subject().displayName(),
                            List.of(),
                            "Originating domain record",
                            source,
                            1.0,
                            List.of("This source record was carried by the domain object")));
                    for (DiscoveredImage image : extract(page, html, request.subject().displayName())) {
                        media.add(new EnrichmentProposal.MediaCandidate(
                                "source-page-image-" + mediaIndex++,
                                identityId,
                                request.targetField(),
                                image.url(),
                                image.url(),
                                source,
                                image.method(),
                                image.confidence(),
                                "",
                                "",
                                request.collection()));
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

    static List<DiscoveredImage> extract(URI page, String html, String subjectName) {
        Map<String, DiscoveredImage> images = new LinkedHashMap<>();
        Matcher metas = META.matcher(html == null ? "" : html);
        while (metas.find()) {
            Map<String, String> attributes = attributes(metas.group());
            String key = first(attributes.get("property"), attributes.get("name"));
            String value = attributes.get("content");
            if (value == null || key == null) continue;
            String normalized = key.toLowerCase(Locale.ROOT);
            if ("og:image".equals(normalized) || "og:image:url".equals(normalized)
                    || "twitter:image".equals(normalized)
                    || "twitter:image:src".equals(normalized)) {
                add(images, page, value, "Page metadata: " + key, 0.92);
            }
        }

        Matcher tags = IMG.matcher(html == null ? "" : html);
        while (tags.find()) {
            Map<String, String> attributes = attributes(tags.group());
            String src = first(attributes.get("src"), attributes.get("data-src"));
            if (src == null) continue;
            String evidence = (src + " " + attributes.getOrDefault("alt", "") + " "
                    + attributes.getOrDefault("class", "")).toLowerCase(Locale.ROOT);
            if (looksRelevant(evidence, subjectName)) {
                add(images, page, src, "Relevant image on source page", 0.82);
            }
        }
        return new ArrayList<>(images.values());
    }

    private static boolean looksRelevant(String evidence, String subjectName) {
        if (evidence.contains("logo") || evidence.contains("icon")
                || evidence.contains("favicon") || evidence.contains("avatar")) {
            return false;
        }
        if (evidence.contains("portrait") || evidence.contains("profile")
                || evidence.contains("laureate")) {
            return true;
        }
        if (subjectName == null) return false;
        long matched = List.of(subjectName.toLowerCase(Locale.ROOT).split("\\s+")).stream()
                .filter(token -> token.length() > 2)
                .filter(evidence::contains)
                .count();
        return matched >= 2;
    }

    private static void add(
            Map<String, DiscoveredImage> images,
            URI page,
            String rawUrl,
            String method,
            double confidence) {
        try {
            String decoded = rawUrl.replace("&amp;", "&").strip();
            URI resolved = page.resolve(decoded);
            String evidence = resolved.toString().toLowerCase(Locale.ROOT);
            if (!evidence.contains("favicon") && !evidence.contains("/logo")
                    && !evidence.contains("-logo") && !evidence.contains("_logo")
                    && httpUri(resolved.toString()) != null) {
                images.putIfAbsent(resolved.toString(),
                        new DiscoveredImage(resolved.toString(), method, confidence));
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed page metadata.
        }
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = ATTR.matcher(tag);
        while (matcher.find()) {
            out.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(3));
        }
        return out;
    }

    private static String first(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static URI httpUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    interface PageFetcher {
        String fetch(URI uri) throws Exception;
    }

    record DiscoveredImage(String url, String method, double confidence) { }
}
