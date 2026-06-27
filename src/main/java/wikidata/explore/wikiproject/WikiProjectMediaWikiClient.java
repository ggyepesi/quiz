package wikidata.explore.wikiproject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WikiProjectMediaWikiClient {
    private static final String ENDPOINT =
            "https://en.wikipedia.org/w/api.php";

    private final String userAgent;
    private final long sleepMillis;
    private boolean debug;

    public WikiProjectMediaWikiClient() {
        this("QuizProject/1.0 (ggyepesi@gmail.com)",
             1000);
    }

    public WikiProjectMediaWikiClient(String userAgent, long sleepMillis) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "QuizProject/1.0"
                : userAgent;
        this.sleepMillis = Math.max(0, sleepMillis);
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    public String get(String queryString) throws Exception {
        if (sleepMillis > 0) {
            Thread.sleep(sleepMillis);
        }

        if (debug) {
            System.out.println("WikiProjectMediaWikiClient query |"
                    + queryString + "|");
        }

        URL url = new URI(ENDPOINT + "?" + queryString).toURL();
        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");

        int code = connection.getResponseCode();

        if (debug) {
            System.out.println("WikiProjectMediaWikiClient responseCode "
                    + code + " for " + url);
        }

        InputStream in = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        String body = readAll(in);

        if (code < 200 || code >= 300) {
            throw new RuntimeException(
                    "HTTP " + code + " from MediaWiki API: " + body);
        }

        return body;
    }

    /** The full GET URL for a query string — so a request can be logged as a
     *  runnable link (open it in a browser to see the JSON). */
    public static String url(String queryString) {
        return ENDPOINT + "?" + (queryString == null ? "" : queryString);
    }

    public static String enc(String s) {
        return URLEncoder.encode(
                s == null ? "" : s,
                StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";

        StringBuilder sb = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;

            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }

        return sb.toString();
    }
}
