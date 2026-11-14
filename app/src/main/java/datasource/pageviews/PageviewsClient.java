package datasource.pageviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one way this application reads Wikimedia Pageviews: monthly views of one English
 * Wikipedia article by human readers, summed over a window of complete months.
 *
 * <p>The service does not follow redirects and answers 404 when it holds no views for a
 * title in the window. Both come back as "no record", never as zero: an article nobody
 * read and an article the service cannot report on are different facts.
 */
public final class PageviewsClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** Returns the response body, or null when the service has no record. */
    @FunctionalInterface public interface Fetcher { String fetch(URI uri) throws Exception; }

    /** Complete calendar months, oldest first. */
    public record Window(YearMonth first, YearMonth last) {
        public Window {
            if (first == null || last == null || first.isAfter(last)) {
                throw new IllegalArgumentException("A window needs first <= last");
            }
        }

        /** The {@code months} complete months ending with last month, in UTC. */
        public static Window lastCompleteMonths(int months, Clock clock) {
            YearMonth last = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
            return new Window(last.minusMonths(months - 1L), last);
        }

        @Override public String toString() { return first + " to " + last; }
    }

    private final Fetcher fetcher;

    public PageviewsClient() {
        this(PageviewsClient::fetchRemote);
    }

    public PageviewsClient(Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
    }

    /** Total views of {@code title} over {@code window}, or null when unrecorded. */
    public Long views(String title, Window window) throws Exception {
        String body = fetcher.fetch(URI.create(url(title, window)));
        return body == null ? null : total(JSON.readTree(body));
    }

    public static String url(String title, Window window) {
        return "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/"
                + "en.wikipedia/all-access/user/"
                + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
                        .replace("+", "%20")
                + "/monthly/" + window.first().atDay(1).format(DAY) + "00/"
                + window.last().atEndOfMonth().format(DAY) + "00";
    }

    static Long total(JsonNode root) {
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return null;
        long total = 0;
        for (JsonNode item : items) total += item.path("views").asLong(0);
        return total;
    }

    private static String fetchRemote(URI uri) throws Exception {
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (objectview.utils.UrlOpener.HttpStatusException status) {
            if (status.status() == 404) return null;
            throw status;
        }
    }
}
