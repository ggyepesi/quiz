package quiz.ocr;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Blurs occurrences of a <i>known</i> word (e.g. a constellation name) in a
 * chart image, so the chart can be a quiz prompt without giving the answer
 * away. Unlike the failed logo experiment, this works because chart labels are
 * clean horizontal text: grayscale → 3× upscale → fixed-brightness threshold →
 * Tesseract sparse mode reads the label, and we keep only words that match the
 * supplied name (closed dictionary), then mosaic those boxes.
 *
 * <p>Best-effort: any failure (no native Tesseract, no match, unreadable image)
 * returns the input bytes unchanged, so quizzes never break.
 */
public class ChartTextBlurrer {

    static {
        // JNA needs the native tesseract/leptonica libs; default to Homebrew,
        // overridable. Must be set before tess4j loads them.
        if (System.getProperty("jna.library.path") == null) {
            System.setProperty("jna.library.path",
                    System.getProperty("tess.lib.path",
                            "/usr/local/opt/tesseract/lib:/usr/local/opt/leptonica/lib"));
        }
        try {
            OpenCV.loadLocally();
        } catch (Throwable ignored) {
        }
    }

    private static final int UPSCALE = 3;

    private final Tesseract tess;
    private final boolean available;

    public ChartTextBlurrer(String tessdataPath) {
        Tesseract t = null;
        boolean ok = false;
        try {
            t = new Tesseract();
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                t.setDatapath(tessdataPath);
            }
            t.setLanguage("eng");
            t.setPageSegMode(11); // sparse text
            t.setOcrEngineMode(1);
            ok = true;
        } catch (Throwable e) {
            System.out.println("ChartTextBlurrer: Tesseract unavailable (" + e + ")");
        }
        this.tess = t;
        this.available = ok;
    }

    /** Returns PNG bytes with {@code name} mosaicked out, or the input unchanged. */
    public byte[] blurName(byte[] imageBytes, String name) {
        return blurName(imageBytes, name, null);
    }

    /** As {@link #blurName(byte[], String)}, but records an OCR miss (no name
     *  found to blur) against {@code context} so it can be fixed by hand. */
    public byte[] blurName(byte[] imageBytes, String name, String context) {
        if (!available || imageBytes == null || name == null || name.isBlank()) {
            return imageBytes;
        }
        try {
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
            if (src.empty()) {
                return imageBytes;
            }
            List<Rect> boxes = detect(src, name);
            if (boxes.isEmpty()) {
                OcrMissLog.record(context != null ? context : name);
                return imageBytes;
            }
            Mat out = mosaic(src, boxes);
            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".png", out, buf);
            return buf.toArray();
        } catch (Throwable e) {
            return imageBytes;
        }
    }

    private List<Rect> detect(Mat src, String name) throws Exception {
        Set<String> nameWords = nameWords(name);
        if (nameWords.isEmpty()) {
            return List.of();
        }

        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
        Mat up = new Mat();
        Imgproc.resize(gray, up, new Size(gray.cols() * UPSCALE, gray.rows() * UPSCALE),
                0, 0, Imgproc.INTER_CUBIC);

        // Sweep a few brightness thresholds and both polarities (light-on-dark
        // and dark-on-light charts). Early-exit on the first match keeps the
        // common case fast; only misses pay for the whole sweep.
        for (int thr : new int[]{120, 95, 150, 175}) {
            for (int type : new int[]{Imgproc.THRESH_BINARY, Imgproc.THRESH_BINARY_INV}) {
                Mat bw = new Mat();
                Imgproc.threshold(up, bw, thr, 255, type);
                List<Rect> boxes = matchWords(bw, nameWords);
                if (!boxes.isEmpty()) {
                    return boxes;
                }
            }
        }
        return List.of();
    }

    private synchronized List<Rect> matchWords(Mat bw, Set<String> nameWords) throws Exception {
        BufferedImage bi = toImage(bw);
        List<Word> words = tess.getWords(bi, ITessAPI.TessPageIteratorLevel.RIL_WORD);

        List<Rect> boxes = new ArrayList<>();
        for (Word w : words) {
            String norm = normalize(w.getText());
            if (norm.length() < 3) {
                continue;
            }
            for (String nw : nameWords) {
                if (matches(norm, nw)) {
                    Rectangle r = w.getBoundingBox();
                    boxes.add(new Rect(r.x / UPSCALE, r.y / UPSCALE,
                            Math.max(1, r.width / UPSCALE), Math.max(1, r.height / UPSCALE)));
                    break;
                }
            }
        }
        return boxes;
    }

    private static boolean matches(String ocr, String nameWord) {
        if (ocr.equals(nameWord)) {
            return true;
        }
        int max = Math.max(ocr.length(), nameWord.length());
        if (max < 4) {
            return false; // short words must match exactly (avoid false positives)
        }
        return levenshtein(ocr, nameWord) <= (max >= 7 ? 2 : 1);
    }

    private static Set<String> nameWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String n = normalize(part);
            if (n.length() >= 3) {
                out.add(n);
            }
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        // Fold diacritics (Boötes -> bootes) so accented names still match.
        String folded = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Mat mosaic(Mat src, List<Rect> boxes) {
        Mat out = src.clone();
        int pad = Math.max(3, src.rows() / 120);
        for (Rect b : boxes) {
            int x = Math.max(0, b.x - pad);
            int y = Math.max(0, b.y - pad);
            int w = Math.min(src.cols() - x, b.width + 2 * pad);
            int h = Math.min(src.rows() - y, b.height + 2 * pad);
            if (w <= 0 || h <= 0) {
                continue;
            }
            Mat roi = out.submat(new Rect(x, y, w, h));
            Mat small = new Mat();
            Imgproc.resize(roi, small, new Size(Math.max(1, w / 12), Math.max(1, h / 6)),
                    0, 0, Imgproc.INTER_LINEAR);
            Imgproc.resize(small, roi, roi.size(), 0, 0, Imgproc.INTER_NEAREST);
        }
        return out;
    }

    private static BufferedImage toImage(Mat mat) throws Exception {
        MatOfByte buf = new MatOfByte();
        Imgcodecs.imencode(".png", mat, buf);
        return ImageIO.read(new ByteArrayInputStream(buf.toArray()));
    }

    private static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            dp[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = dp[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j] + 1, dp[j - 1] + 1), prev + cost);
                prev = tmp;
            }
        }
        return dp[b.length()];
    }
}
