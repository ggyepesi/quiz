package aux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikiRawPageReader {
    private static final Pattern REDIRECT_PATTERN =
            Pattern.compile("(?is)#REDIRECT\\s*\\[\\[(.+?)(?:#.*?)?(?:\\|.*?)?]]");

    private WikiRawPageReader() {
    }

    public static String readRawPage(String title) throws Exception {
        String encoded = URLEncoder
                .encode(title.trim().replace(" ", "_"), StandardCharsets.UTF_8.name())
                .replace("+", "_");

        String url = "https://en.wikipedia.org/wiki/" + encoded + "?action=raw";

        return new UrlReader<>(new StringCollectingLineProcessor()).read(url);
    }

    public static ResolvedPage readResolvedRawPage(String title) throws Exception {
        String normalizedTitle = normalizeTitle(title);
        String text = readRawPage(normalizedTitle);

        String redirect = findRedirectTarget(text);
        if (redirect != null) {
            normalizedTitle = normalizeTitle(redirect);
            text = readRawPage(normalizedTitle);
        }

        return new ResolvedPage(normalizedTitle, text);
    }

    public static String findRedirectTarget(String text) {
        if (text == null) return null;

        Matcher m = REDIRECT_PATTERN.matcher(text);
        if (!m.find()) return null;

        return m.group(1).trim();
    }

    public static String normalizeTitle(String title) {
        return title.trim().replace(" ", "_");
    }

    public static class ResolvedPage {
        private final String title;
        private final String text;

        public ResolvedPage(String title, String text) {
            this.title = title;
            this.text = text;
        }

        public String getTitle() {
            return title;
        }

        public String getText() {
            return text;
        }

        public String getUrl() {
            return "https://en.wikipedia.org/wiki/" + title;
        }
    }
}