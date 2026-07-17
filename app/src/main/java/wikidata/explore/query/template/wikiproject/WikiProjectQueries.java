package wikidata.explore.query.template.wikiproject;

import wikidata.explore.query.core.QueryTemplate;

import java.util.Map;
import java.util.Set;

public final class WikiProjectQueries {

    private WikiProjectQueries() {}

    public static final Set<String> DEFAULT_DOMAINS =
            Set.of(
                    "Astronomy",
                    "Solar System",
                    "Stars",
                    "Constellations",
                    "Planets",
                    "Mythology",
                    "Birds",
                    "Mammals");

    public static final QueryTemplate PROJECT_PAGE =
            new QueryTemplate(
                    "WikiProject page",
                    "Wikipedia:WikiProject ${domain}");

    public static final QueryTemplate PROJECT_DIRECTORY =
            new QueryTemplate(
                    "WikiProject directory",
                    "Wikipedia:WikiProject Council/Directory");

    public static final QueryTemplate PROJECT_DOMAIN_SEARCH =
            new QueryTemplate(
                    "WikiProject domain search",
                    "Wikipedia:WikiProject ${domain}");

    public static String projectPageRequest(String domain) {
        return PROJECT_PAGE.render(Map.of(
                "domain", domain == null ? "" : domain.trim()));
    }

    public static String projectDirectoryRequest() {
        return PROJECT_DIRECTORY.render(Map.of());
    }

    public static String projectDomainSearchRequest(String domain) {
        return PROJECT_DOMAIN_SEARCH.render(Map.of(
                "domain", domain == null ? "" : domain.trim()));
    }

    public static UnsupportedOperationException notImplemented(
            String feature) {

        return new UnsupportedOperationException(
                "WikiProject query not implemented yet: " + feature);
    }
}