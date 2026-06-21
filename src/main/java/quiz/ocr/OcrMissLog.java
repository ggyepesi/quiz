package quiz.ocr;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records charts whose answer text the OCR blurrer could not find/blur, so they
 * can be fixed by hand (a mask in {@code LogoMaskBlurEditor}). Appends to
 * {@code data/masks/ocr-misses.txt} and prints once per item per run.
 */
public final class OcrMissLog {

    private static final File FILE =
            new File(QuizImageBlurrer.MASK_DIR + File.separator + "ocr-misses.txt");

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // De-dupe within a run so a chart fetched repeatedly logs only once.
    private static final Set<String> seen = ConcurrentHashMap.newKeySet();

    private OcrMissLog() {}

    /** @param context e.g. "Constellation / Ara — https://.../AraCC.jpg" */
    public static void record(String context) {
        if (context == null || context.isBlank() || !seen.add(context)) {
            return;
        }
        System.err.println("[OCR-miss] no answer text blurred — needs a hand mask: "
                + context);
        try {
            File parent = FILE.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileWriter w = new FileWriter(FILE, true)) {
                w.write(LocalDateTime.now().format(TS) + "\t" + context + "\n");
            }
        } catch (IOException ignored) {
        }
    }
}
