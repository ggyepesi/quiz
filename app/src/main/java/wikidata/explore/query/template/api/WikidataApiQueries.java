package wikidata.explore.query.template.api;

import wikidata.api.WikidataApiClient;
import work.QueryTemplate;

import java.util.List;
import java.util.Map;

public final class WikidataApiQueries {

    private WikidataApiQueries() {}

    public static final QueryTemplate SEARCH_ENTITIES =
            new QueryTemplate(
                    "Wikidata entity search",
                    "wbsearchentities?search=${search}&language=${language}&type=item&limit=${limit}");

    public static final QueryTemplate CATEGORY_MEMBERS =
            new QueryTemplate(
                    "Wikipedia category members",
                    "categorymembers?category=${category}&limit=${limit}");

    public static final QueryTemplate RESOLVE_ARTICLES =
            new QueryTemplate(
                    "Resolve Wikipedia articles to Wikidata entities",
                    "wbgetentities?sites=enwiki&titles=${titles}&props=claims|labels|sitelinks");

    public static final QueryTemplate STARS_OF_CONSTELLATION =
            new QueryTemplate(
                    "Stars of constellation via category",
                    "Category:${constellationName}_(constellation) -> wbgetentities -> filter P1215/P215");

    public static String searchEntitiesRequest(
            String search,
            String language,
            int limit) {

        return SEARCH_ENTITIES.render(Map.of(
                "search", search == null ? "" : search,
                "language", language == null || language.isBlank()
                        ? "en"
                        : language,
                "limit", Math.max(1, limit)));
    }

    public static List<WikidataApiClient.SearchResult> searchEntities(
            WikidataApiClient api,
            String search,
            int limit) throws Exception {

        return api.searchEntities(search, Math.max(1, limit));
    }

    public static String categoryMembersRequest(
            String category,
            int limit) {

        return CATEGORY_MEMBERS.render(Map.of(
                "category", category == null ? "" : category,
                "limit", Math.max(1, limit)));
    }

    public static List<String> categoryMembers(
            WikidataApiClient api,
            String categoryTitle,
            int limit) throws Exception {

        return api.categoryMembers(categoryTitle, Math.max(1, limit));
    }

    public static List<WikidataApiClient.EntityResult> resolveArticles(
            WikidataApiClient api,
            List<String> titles,
            List<String> propertyPids) throws Exception {

        return api.resolveArticles(titles, propertyPids);
    }

    public static String starsOfConstellationRequest(
            String constellationName,
            double magnitudeLimit,
            int limit) {

        return STARS_OF_CONSTELLATION.render(Map.of(
                "constellationName", normalizeTitlePart(constellationName),
                "magnitudeLimit", magnitudeLimit,
                "limit", Math.max(1, limit)));
    }

    public static List<WikidataApiClient.EntityResult> starsOfConstellation(
            WikidataApiClient api,
            String constellationName,
            double magnitudeLimit,
            int limit) throws Exception {

        return api.starsOfConstellation(
                constellationName,
                magnitudeLimit,
                Math.max(1, limit));
    }

    public static String constellationCategory(String constellationName) {
        return "Category:"
                + normalizeTitlePart(constellationName)
                + "_(constellation)";
    }

    private static String normalizeTitlePart(String s) {
        return s == null ? "" : s.trim().replace(" ", "_");
    }
}