package quiz.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs every HTTP request the server handles — which client, what they asked
 * for, the response status and how long it took. Attached to each context in
 * {@link ViewableHttpServer}. Useful for seeing phone vs laptop traffic, slow
 * image/chart builds, and 401s on the gated builder routes.
 */
public class RequestLogFilter extends Filter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    @Override
    public String description() {
        return "Request logging";
    }

    @Override
    public void doFilter(HttpExchange ex, Chain chain) throws IOException {
        long start = System.nanoTime();
        String client = clientAddress(ex);
        String method = ex.getRequestMethod();
        String uri = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();

        try {
            chain.doFilter(ex);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = ex.getResponseCode(); // -1 if the handler never replied
            System.out.printf(
                    "%s  %-15s  %-4s %s%s -> %s  (%d ms)%n",
                    LocalTime.now().format(TIME),
                    client,
                    method,
                    uri,
                    query == null ? "" : "?" + query,
                    status < 0 ? "—" : Integer.toString(status),
                    ms);
        }
    }

    // The real client when behind the Vite/ngrok proxy (X-Forwarded-For),
    // otherwise the direct peer address.
    private static String clientAddress(HttpExchange ex) {
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        java.net.InetSocketAddress addr = ex.getRemoteAddress();
        return addr == null || addr.getAddress() == null
                ? "?"
                : addr.getAddress().getHostAddress();
    }
}
