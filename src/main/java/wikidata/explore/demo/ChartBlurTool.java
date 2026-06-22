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

        // Charts whose name OCR couldn't find/blur are staged here (ORIGINAL,
        // unblurred — so you can see the text) for a hand mask in
        // LogoMaskBlurEditor. Named by the entity name so the mask the editor
        // saves resolves to the same key the server uses at serve time.
        File needsMaskDir = new File(quiz.ocr.QuizImageBlurrer.MASK_DIR, "needs-mask");
        needsMaskDir.mkdirs();
        StringBuilder needsList = new StringBuilder();
        int needMask = 0;

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
                // OCR found no name to blur — stage the ORIGINAL for a hand mask.
                System.out.println("  no name found: " + o.getName());
                File staged = new File(needsMaskDir,
                        quiz.ocr.QuizImageBlurrer.maskKey(o.getName()) + ".png");
                Files.write(staged.toPath(), raw);
                needsList.append(o.getName()).append('\t')
                        .append(o.qid()).append('\t').append(url).append('\n');
                needMask++;
            }

            Files.write(new File(chartsDir, o.qid() + ".png").toPath(), out);
        }

        if (needMask > 0) {
            Files.writeString(new File(needsMaskDir, "needs-mask.txt").toPath(),
                    needsList.toString());
        }

        System.out.println("Stored " + total + " charts (" + blurred
                + " name-blurred) in " + chartsDir);
        System.out.println(needMask + " chart(s) need a hand mask -> " + needsMaskDir
                + " (open each in LogoMaskBlurEditor, set type \"Constellation\", "
                + "mask the name, Save mask).");
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
