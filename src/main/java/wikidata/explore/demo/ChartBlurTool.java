package wikidata.explore.demo;

import quiz.ocr.ChartTextBlurrer;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;

/**
 * Offline batch tool: downloads each constellation's sky chart, mosaics out its
 * name (via {@link ChartTextBlurrer}), and stores the result under
 * {@code data/wikidata/constellations/charts/{qid}.png}. The server then serves
 * these pre-built files instantly instead of blurring on demand (which was too
 * slow per-request). Charts where no name is found are stored unchanged, so the
 * server always has a local file to serve.
 *
 * <p>Run once after (re)generating the snapshot:
 * {@code mvn -o exec:java -Dexec.mainClass=wikidata.explore.demo.ChartBlurTool}
 */
public final class ChartBlurTool {

    public static void main(String[] args) throws Exception {
        File snapshot = new File(aux.Constants.constellationsDataDirectory
                + "constellations.snapshot.json");
        File chartsDir = new File(aux.Constants.constellationsDataDirectory + "charts");
        chartsDir.mkdirs();

        List<WikidataDynamicObject> objects =
                new WikidataDynamicObjectJsonStore().load(snapshot);

        ChartTextBlurrer blurrer = new ChartTextBlurrer(
                System.getProperty("tessdata.path", "/usr/local/share/tessdata"));

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        int total = 0;
        int blurred = 0;
        for (WikidataDynamicObject o : objects) {
            Object chart = o.get("chart");
            if (!(chart instanceof String s)) {
                continue;
            }
            String url = s.startsWith("http://") ? "https://" + s.substring(7) : s;
            total++;

            byte[] raw = download(http, url);
            if (raw == null) {
                System.out.println("  download failed: " + o.getName());
                continue;
            }

            byte[] out = blurrer.blurName(raw, o.getName());
            boolean didBlur = out != raw; // blurName returns a new array only when it changed
            if (didBlur) {
                blurred++;
            } else {
                System.out.println("  no name found: " + o.getName());
            }

            Files.write(new File(chartsDir, o.qid() + ".png").toPath(), out);
        }

        System.out.println("Stored " + total + " charts (" + blurred
                + " name-blurred) in " + chartsDir);
    }

    private static byte[] download(HttpClient http, String url) {
        try {
            HttpResponse<byte[]> r = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("User-Agent", "QuizProject/1.0 (ggyepesi@gmail.com)")
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ChartBlurTool() {}
}
