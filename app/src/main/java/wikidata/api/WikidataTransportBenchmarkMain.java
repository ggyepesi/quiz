package wikidata.api;

import datasource.http.SharedHttpTransport;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays a small, representative concurrent wave from a saved query log.
 *
 * <p>This isolates transport from extraction, fact retention, JSON interpretation and
 * semantic convergence. It deliberately uses six existing request URLs: no cache-bust
 * parameter, no larger population, and no more concurrency than the balanced profile.
 */
public final class WikidataTransportBenchmarkMain {
    private static final String UA = WikidataApiClient.DEFAULT_USER_AGENT;
    private static final int WAVE = 6;
    private static final Pattern GET = Pattern.compile(
            "\\[API (\\d+)] GET (https://www\\.wikidata\\.org/[^\\n]+)");
    private static final Pattern OK = Pattern.compile(
            "\\[API (\\d+)] OK headersMs=\\d+ timeMs=\\d+ bytes=(\\d+)");

    private WikidataTransportBenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Path log = Path.of(args.length == 0
                ? "data/query-log-oscars17.txt" : args[0]);
        int rounds = args.length < 2 ? 2 : Math.max(1, Integer.parseInt(args[1]));
        List<URI> requests = representativeRequests(Files.readString(log), WAVE);
        if (requests.size() < WAVE) {
            throw new IllegalArgumentException("Need " + WAVE
                    + " measured claims requests in " + log + ", found " + requests.size());
        }

        Map<Mode, SharedHttpTransport> transports = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
            transports.put(mode, new SharedHttpTransport(HttpClient.newBuilder()
                    .version(mode.version)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10)).build()));
        }

        System.out.println("source\t" + log + "\trequests\t" + requests.size()
                + "\trounds\t" + rounds);
        System.out.println("round\tmode\twallMs\theadersMedianMs\ttotalMedianMs"
                + "\ttotalMaxMs\tbytes\thttp\tcache");
        for (int round = 0; round < rounds; round++) {
            List<Mode> order = rotatedModes(round);
            for (Mode mode : order) {
                Wave result = runWave(transports.get(mode), mode, requests);
                System.out.println((round + 1) + "\t" + mode.label + "\t"
                        + result.wallMillis + "\t" + median(result.headersMillis) + "\t"
                        + median(result.totalMillis) + "\t"
                        + result.totalMillis.stream().mapToLong(Long::longValue).max().orElse(0)
                        + "\t" + result.bytes + "\t" + result.versions + "\t"
                        + result.caches);
                // Be polite between waves and avoid measuring only one transient burst.
                Thread.sleep(2_000);
            }
        }
    }

    static List<URI> representativeRequests(String log, int count) {
        Map<Long, String> urls = new LinkedHashMap<>();
        Matcher gets = GET.matcher(log == null ? "" : log);
        while (gets.find()) urls.put(Long.parseLong(gets.group(1)), gets.group(2).trim());
        List<Measured> measured = new ArrayList<>();
        Matcher oks = OK.matcher(log == null ? "" : log);
        while (oks.find()) {
            long id = Long.parseLong(oks.group(1));
            String url = urls.get(id);
            if (url != null && url.contains("props=labels%7Cclaims%7Caliases")) {
                measured.add(new Measured(URI.create(url), Long.parseLong(oks.group(2))));
            }
        }
        measured.sort(Comparator.comparingLong(Measured::bytes));
        if (measured.size() <= count) return measured.stream().map(Measured::uri).toList();
        List<URI> selected = new ArrayList<>();
        // Interior quantiles avoid constructing a wave made only of either tiny or
        // pathological responses while still covering its observed size distribution.
        for (int i = 0; i < count; i++) {
            double quantile = (i + 1.0) / (count + 1.0);
            int index = (int) Math.round(quantile * (measured.size() - 1));
            selected.add(measured.get(index).uri());
        }
        return List.copyOf(selected);
    }

    private static Wave runWave(SharedHttpTransport transport, Mode mode,
            List<URI> requests) throws Exception {
        try (var workers = Executors.newFixedThreadPool(requests.size())) {
            List<Callable<SharedHttpTransport.Response>> calls = requests.stream()
                    .<Callable<SharedHttpTransport.Response>>map(uri -> () -> transport.get(
                            uri, Map.of("User-Agent", UA, "Accept", "application/json",
                                    "Accept-Encoding", mode.encoding),
                            Duration.ofSeconds(60))).toList();
            long started = System.nanoTime();
            var futures = workers.invokeAll(calls);
            List<SharedHttpTransport.Response> responses = new ArrayList<>();
            for (var future : futures) {
                SharedHttpTransport.Response response = future.get();
                if (response.status() < 200 || response.status() >= 300) {
                    throw new IllegalStateException("HTTP " + response.status());
                }
                responses.add(response);
            }
            long wall = (System.nanoTime() - started) / 1_000_000;
            List<Long> headers = responses.stream()
                    .map(SharedHttpTransport.Response::headersMillis).sorted().toList();
            List<Long> totals = responses.stream()
                    .map(SharedHttpTransport.Response::totalMillis).sorted().toList();
            long bytes = responses.stream()
                    .mapToLong(SharedHttpTransport.Response::transferredBytes).sum();
            String versions = responses.stream().map(r -> r.version().toString()).distinct()
                    .sorted().reduce((a, b) -> a + "," + b).orElse("");
            String caches = responses.stream().map(r -> cache(r.header("X-Cache"))).distinct()
                    .sorted().reduce((a, b) -> a + "," + b).orElse("");
            return new Wave(wall, headers, totals, bytes, versions, caches);
        }
    }

    private static List<Mode> rotatedModes(int round) {
        List<Mode> base = List.of(Mode.values());
        List<Mode> result = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) result.add(base.get((i + round) % base.size()));
        return result;
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) return 0;
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
    }

    private static String cache(String value) {
        if (value == null || value.isBlank()) return "none";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains(" hit")) return "hit";
        if (lower.contains("miss")) return "miss";
        if (lower.contains("pass")) return "pass";
        return value.replace('\t', ' ').replace('\n', ' ');
    }

    private enum Mode {
        HTTP1_GZIP("http1-gzip", HttpClient.Version.HTTP_1_1, "gzip"),
        HTTP2_GZIP("http2-gzip", HttpClient.Version.HTTP_2, "gzip"),
        HTTP1_IDENTITY("http1-identity", HttpClient.Version.HTTP_1_1, "identity");

        private final String label;
        private final HttpClient.Version version;
        private final String encoding;
        Mode(String label, HttpClient.Version version, String encoding) {
            this.label = label;
            this.version = version;
            this.encoding = encoding;
        }
    }

    private record Measured(URI uri, long bytes) { }
    private record Wave(long wallMillis, List<Long> headersMillis,
            List<Long> totalMillis, long bytes, String versions, String caches) { }
}
