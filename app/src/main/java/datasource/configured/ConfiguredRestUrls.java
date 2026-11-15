package datasource.configured;

import datasource.api.discovery.RelatedRecordSeed;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** URL templates supported by the bounded configured-REST operation. */
final class ConfiguredRestUrls {
    private ConfiguredRestUrls() { }

    static String search(ConfiguredDatasourceDefinition.Search search,
            RelatedRecordSeed seed, int limit) {
        StringBuilder url = new StringBuilder(search.url());
        boolean first = !search.url().contains("?");
        for (Map.Entry<String, String> parameter : search.query().entrySet()) {
            url.append(first ? '?' : '&');
            first = false;
            url.append(encode(parameter.getKey())).append('=')
                    .append(encode(expand(parameter.getValue(), seed, limit, null)));
        }
        return url.toString();
    }

    static String detail(ConfiguredDatasourceDefinition.Detail detail, String resultId) {
        return detail.url().replace("${result.id}", encodePath(resultId));
    }

    private static String expand(String value, RelatedRecordSeed seed,
            int limit, String resultId) {
        String expanded = value == null ? "" : value;
        if (seed != null) {
            expanded = expanded.replace("${seed.id}", seed.entity().id())
                    .replace("${seed.label}", seed.displayName());
        }
        expanded = expanded.replace("${limit}", Integer.toString(limit));
        if (resultId != null) expanded = expanded.replace("${result.id}", resultId);
        return expanded;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String encodePath(String value) {
        return encode(value);
    }
}
