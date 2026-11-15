package datasource.configured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** One JSON HTTP reader shared by every configuration-defined REST operation. */
public final class ConfiguredRestClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface public interface Fetcher {
        String fetch(URI uri) throws Exception;
    }

    private final Fetcher fetcher;

    public ConfiguredRestClient() {
        this(ConfiguredRestClient::fetchRemote);
    }

    public ConfiguredRestClient(Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
    }

    public JsonNode get(String url) throws Exception {
        String body = fetcher.fetch(URI.create(url));
        if (body == null || body.isBlank()) {
            throw new java.io.EOFException("Empty JSON response from " + url);
        }
        return JSON.readTree(body);
    }

    private static String fetchRemote(URI uri) throws Exception {
        try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
