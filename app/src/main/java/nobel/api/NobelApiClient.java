package nobel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import wikidata.WikidataLanguageDefaults;
import work.Query;
import work.QueryContext;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads awarded Nobel Prizes from the nobelprize.org public API.
 *
 * <p>The source is authoritative for the PRIZE: which category, which year, how it was
 * divided and for what. Wikidata remains authoritative for the PERSON, and the two join
 * on the laureate id this API issues (Wikidata P8024).
 *
 * <p>Requests go through {@code UrlOpener}, the one polite outbound path - contact user
 * agent, self-throttling, redirect following and transient retry - rather than a private
 * HTTP client of this class's own.
 */
public final class NobelApiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://api.nobelprize.org/2.1/nobelPrizes";

    /** The six categories the domain models, in the source's own codes. */
    public static final List<String> CATEGORIES =
            List.of("phy", "che", "med", "lit", "pea", "eco");

    private final Fetcher fetcher;

    /** What a fetch of one URI yields. The seam that lets the parse be tested offline. */
    @FunctionalInterface
    public interface Fetcher {
        String fetch(URI uri) throws Exception;
    }

    public NobelApiClient() {
        this(uri -> {
            try (InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    public NobelApiClient(Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher, "fetcher is required");
    }

    /** Every awarded prize in one category, oldest first. */
    public Query<List<NobelPrizeAward>> category(String categoryCode) {
        String code = categoryCode == null ? "" : categoryCode.trim().toLowerCase();
        if (!CATEGORIES.contains(code)) throw new IllegalArgumentException(
                "Unknown Nobel category code: " + categoryCode + ". Known: " + CATEGORIES);
        URI uri = URI.create(ENDPOINT + "?nobelPrizeCategory=" + code
                + "&limit=200&sort=asc");
        return new Query<>() {
            @Override public String purpose() { return "Read awarded Nobel Prizes"; }
            @Override public String queryType() { return "Nobel API"; }
            @Override public String skeleton() {
                return "category -> prize(year) -> achievement(motivation)"
                        + " -> laureate award(portion)";
            }
            @Override public String description() {
                return "Prize structure from nobelprize.org; the person comes from Wikidata.";
            }
            @Override public Map<String, String> parameters() {
                return Map.of("category", code);
            }
            @Override public List<NobelPrizeAward> execute(QueryContext context)
                    throws Exception {
                return context.step("Read " + code + " prizes", "Nobel API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            List<NobelPrizeAward> awards =
                                    parse(JSON.readTree(fetcher.fetch(uri)), code);
                            step.summary(awards.size() + " prize(s), "
                                    + awards.stream().mapToInt(
                                            a -> a.achievements().size()).sum()
                                    + " achievement(s), "
                                    + awards.stream().mapToInt(
                                            a -> a.laureateAwards().size()).sum()
                                    + " laureate award(s)");
                            return awards;
                        });
            }
            @Override public int rowCount(List<NobelPrizeAward> result) {
                return result == null ? 0 : result.size();
            }
        };
    }

    /**
     * Parses one category-filtered {@code nobelPrizes} response. The API response does
     * not repeat the category code, so the request that selected the category supplies
     * it rather than a label or URL being reverse-engineered into one.
     */
    public static List<NobelPrizeAward> parse(JsonNode root, String categoryCode) {
        String code = categoryCode == null ? "" : categoryCode.trim().toLowerCase();
        if (!CATEGORIES.contains(code)) throw new IllegalArgumentException(
                "Unknown Nobel category code: " + categoryCode + ". Known: " + CATEGORIES);
        List<NobelPrizeAward> awards = new ArrayList<>();
        if (root == null) return awards;
        for (JsonNode prize : root.path("nobelPrizes")) {
            awards.add(new NobelPrizeAward(
                    code,
                    en(prize.path("category")),
                    prize.path("awardYear").asInt(),
                    en(prize.path("topMotivation")),
                    achievements(prize.path("laureates"))));
        }
        return awards;
    }

    /**
     * Groups laureate awards into derived achievements by their motivation.
     *
     * <p>The API states the motivation on each laureate, so a share is not a record of
     * its own. Physics 2018 has three laureate awards and two achievements: Ashkin for
     * optical tweezers, Mourou and Strickland together for ultra-short pulses. The
     * portion remains on each laureate award. Grouping preserves first-appearance order,
     * and an unstated motivation stays alone rather than merging unrelated silent entries.
     */
    private static List<NobelPrizeAward.Achievement> achievements(JsonNode laureates) {
        Map<String, List<NobelPrizeAward.LaureateAward>> byMotivation =
                new LinkedHashMap<>();
        Map<String, String> motivations = new LinkedHashMap<>();
        int unstated = 0;
        for (JsonNode node : laureates) {
            boolean organization = node.hasNonNull("orgName");
            String motivation = en(node.path("motivation"));
            String key = motivation.isBlank() ? "unstated#" + unstated++ : motivation;
            byMotivation.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new NobelPrizeAward.LaureateAward(
                            text(node.path("id")),
                            organization ? en(node.path("orgName")) : name(node),
                            text(node.path("portion")),
                            node.path("sortOrder").asInt(),
                            organization));
            motivations.putIfAbsent(key, motivation);
        }
        List<NobelPrizeAward.Achievement> achievements = new ArrayList<>();
        byMotivation.forEach((key, members) -> achievements.add(
                new NobelPrizeAward.Achievement(motivations.get(key), members)));
        return achievements;
    }

    /** A person's usual name, falling back to the full one when no short form exists. */
    private static String name(JsonNode laureate) {
        String known = en(laureate.path("knownName"));
        return known.isBlank() ? en(laureate.path("fullName")) : known;
    }

    /**
     * A multilingual field in the application's default language. The source states most
     * text in several languages; which one this domain is served in is one decision, and
     * it is not this class's to restate.
     */
    private static String en(JsonNode node) {
        return text(node.path(WikidataLanguageDefaults.CODE));
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }
}
