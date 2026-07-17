package quiz.ocr;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.*;

/**
 * Closed-dictionary logo text recognizer.
 *
 * Input:
 *   - image file: svg/png/jpg/jpeg
 *   - expected text: e.g. "Boston Red Sox"
 *
 * Output:
 *   - matched OCR words + rectangles
 *   - optional blurred image
 */
public class ClosedDictionaryTextBlurrer {

    private final Tesseract tesseract;

    public ClosedDictionaryTextBlurrer(
            String tessdataPath,
            String language
                                      ) {
        this.tesseract = new Tesseract();

        if (tessdataPath != null && !tessdataPath.isBlank()) {
            tesseract.setDatapath(tessdataPath);
        }

        tesseract.setLanguage(
                language == null || language.isBlank()
                        ? "eng"
                        : language
                             );

        // Single uniform block. Good first default for logos.
        tesseract.setPageSegMode(6);

        // Treat input as normal LSTM OCR.
        tesseract.setOcrEngineMode(1);
    }

    public RecognitionResult recognize(File imageFile, String expectedText)
            throws Exception {

        BufferedImage image = readImage(imageFile);

        List<Double> scales = List.of(1.0, 1.5, 2.0, 3.0, 4.0);
        List<Integer> psms = List.of(6, 7, 8, 11, 13);

        RecognitionResult best = null;

        for (int psm : psms) {
            tesseract.setPageSegMode(psm);

            for (double scale : scales) {
                BufferedImage scaled = preprocessForOcr(
                        scaleImage(image, scale));

                List<Word> words = tesseract.getWords(
                        scaled,
                        ITessAPI.TessPageIteratorLevel.RIL_WORD);

                for (Word w : words) {
                    System.out.println(
                            "[" + w.getText() + "] "
                                    + w.getBoundingBox());
                }

                RecognitionResult result = matchWords(
                        words,
                        expectedText,
                        scale,
                        image.getWidth(),
                        image.getHeight()
                                                     );

                if (best == null || result.score() > best.score()) {
                    best = result;
                }
            }
        }

        return best;
    }

    public BufferedImage blurExpectedText(
            File imageFile,
            String expectedText,
            int blurRadius
                                         ) throws Exception {

        BufferedImage image = readImage(imageFile);
        RecognitionResult result = recognize(imageFile, expectedText);

        BufferedImage output = deepCopy(image);

        for (MatchedWord match : result.matches()) {
            Rectangle r = expand(match.box(), 4, image.getWidth(), image.getHeight());
            blurRectangle(output, r, blurRadius);
        }

        return output;
    }

    public void blurExpectedTextToFile(
            File input,
            String expectedText,
            File output,
            int blurRadius
                                      ) throws Exception {

        BufferedImage blurred = blurExpectedText(
                input,
                expectedText,
                blurRadius
                                                );

        ImageIO.write(blurred, "png", output);
    }

    private static BufferedImage preprocessForOcr(BufferedImage src) {
        BufferedImage gray = new BufferedImage(
                src.getWidth(),
                src.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY
        );

        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        BufferedImage out = new BufferedImage(
                gray.getWidth(),
                gray.getHeight(),
                BufferedImage.TYPE_BYTE_BINARY
        );

        for (int y = 0; y < gray.getHeight(); y++) {
            for (int x = 0; x < gray.getWidth(); x++) {
                int rgb = gray.getRGB(x, y);
                int v = rgb & 0xff;

                // Adaptive-ish simple threshold.
                int bw = v > 150 ? 0xffffffff : 0xff000000;
                out.setRGB(x, y, bw);
            }
        }

        return out;
    }

    private RecognitionResult matchWords(
            List<Word> words,
            String expectedText,
            double scale,
            int originalWidth,
            int originalHeight
                                        ) {
        List<String> expectedWords = splitExpectedWords(expectedText);

        List<MatchedWord> matches = new ArrayList<>();
        List<String> missing = new ArrayList<>(expectedWords);

        for (Word word : words) {
            String raw = word.getText();
            String normalizedOcr = normalize(raw);

            if (normalizedOcr.isBlank()) {
                continue;
            }

            CandidateMatch best = null;

            for (String expected : expectedWords) {
                CandidateMatch candidate = compare(normalizedOcr, expected);

                if (best == null || candidate.score > best.score) {
                    best = candidate;
                }
            }

            if (best != null && best.accepted()) {
                Rectangle box = scaleBack(
                        word.getBoundingBox(),
                        scale,
                        originalWidth,
                        originalHeight
                                         );

                matches.add(new MatchedWord(
                        raw,
                        best.expected,
                        best.score,
                        box
                ));

                missing.remove(best.expected);
            }
        }

        double score = expectedWords.isEmpty()
                ? 0.0
                : Math.min(1.0, matches.size() / (double) expectedWords.size());

        return new RecognitionResult(
                expectedText,
                matches,
                missing,
                score
        );
    }

    private static CandidateMatch compare(String ocr, String expected) {
        if (ocr.equals(expected)) {
            return new CandidateMatch(expected, 1.0);
        }

        if (ocr.contains(expected) || expected.contains(ocr)) {
            double ratio =
                    Math.min(ocr.length(), expected.length())
                            / (double) Math.max(ocr.length(), expected.length());
            return new CandidateMatch(expected, 0.75 + 0.2 * ratio);
        }

        int d = levenshtein(ocr, expected);
        int max = Math.max(ocr.length(), expected.length());

        double similarity = max == 0 ? 0.0 : 1.0 - d / (double) max;

        return new CandidateMatch(expected, similarity);
    }

    private static List<String> splitExpectedWords(String expectedText) {
        String normalized = normalizeWithSpaces(expectedText);

        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> words = new ArrayList<>();

        for (String word : normalized.split("\\s+")) {
            if (word.length() >= 2) {
                words.add(word);
            }
        }

        return words;
    }

    private static String normalize(String s) {
        return s == null
                ? ""
                : s.toLowerCase(Locale.ROOT)
                   .replace('0', 'o')
                   .replace('1', 'i')
                   .replace('5', 's')
                   .replaceAll("[^a-z0-9]", "");
    }

    private static String normalizeWithSpaces(String s) {
        return s == null
                ? ""
                : s.toLowerCase(Locale.ROOT)
                   .replace('0', 'o')
                   .replace('1', 'i')
                   .replace('5', 's')
                   .replaceAll("[^a-z0-9]+", " ")
                   .trim();
    }

    private static BufferedImage readImage(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);

        if (name.endsWith(".svg")) {
            return renderSvgToImage(file, 1600);
        }

        BufferedImage image = ImageIO.read(file);

        if (image == null) {
            throw new IOException("Unsupported image file: " + file);
        }

        return toArgb(image);
    }

    private static BufferedImage renderSvgToImage(File svgFile, int width)
            throws Exception {

        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(
                PNGTranscoder.KEY_WIDTH,
                (float) width
                                     );

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (InputStream in = new FileInputStream(svgFile)) {
            transcoder.transcode(
                    new TranscoderInput(in),
                    new TranscoderOutput(out)
                                );
        }

        try (InputStream pngIn =
                     new ByteArrayInputStream(out.toByteArray())) {
            BufferedImage image = ImageIO.read(pngIn);

            if (image == null) {
                throw new IOException("Could not render SVG: " + svgFile);
            }

            return toArgb(image);
        }
    }

    private static BufferedImage scaleImage(BufferedImage src, double scale) {
        if (scale == 1.0) {
            return src;
        }

        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));

        BufferedImage out = new BufferedImage(
                w,
                h,
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = out.createGraphics();
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
                          );
        g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
                          );
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        return out;
    }

    private static Rectangle scaleBack(
            Rectangle r,
            double scale,
            int maxWidth,
            int maxHeight
                                      ) {
        int x = (int) Math.floor(r.x / scale);
        int y = (int) Math.floor(r.y / scale);
        int w = (int) Math.ceil(r.width / scale);
        int h = (int) Math.ceil(r.height / scale);

        return clamp(new Rectangle(x, y, w, h), maxWidth, maxHeight);
    }

    private static Rectangle expand(
            Rectangle r,
            int padding,
            int maxWidth,
            int maxHeight
                                   ) {
        return clamp(
                new Rectangle(
                        r.x - padding,
                        r.y - padding,
                        r.width + 2 * padding,
                        r.height + 2 * padding
                ),
                maxWidth,
                maxHeight
                    );
    }

    private static Rectangle clamp(Rectangle r, int maxWidth, int maxHeight) {
        int x1 = Math.max(0, r.x);
        int y1 = Math.max(0, r.y);
        int x2 = Math.min(maxWidth, r.x + r.width);
        int y2 = Math.min(maxHeight, r.y + r.height);

        return new Rectangle(
                x1,
                y1,
                Math.max(0, x2 - x1),
                Math.max(0, y2 - y1)
        );
    }

    private static void blurRectangle(
            BufferedImage image,
            Rectangle rect,
            int radius
                                     ) {
        if (rect.width <= 0 || rect.height <= 0) {
            return;
        }

        BufferedImage copy = deepCopy(image);

        int x1 = rect.x;
        int y1 = rect.y;
        int x2 = rect.x + rect.width;
        int y2 = rect.y + rect.height;

        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {

                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;

                for (int yy = y - radius; yy <= y + radius; yy++) {
                    for (int xx = x - radius; xx <= x + radius; xx++) {
                        if (xx < 0 || yy < 0
                                || xx >= copy.getWidth()
                                || yy >= copy.getHeight()) {
                            continue;
                        }

                        int argb = copy.getRGB(xx, yy);

                        a += (argb >>> 24) & 0xff;
                        r += (argb >>> 16) & 0xff;
                        g += (argb >>> 8) & 0xff;
                        b += argb & 0xff;
                        count++;
                    }
                }

                int aa = (int) (a / count);
                int rr = (int) (r / count);
                int gg = (int) (g / count);
                int bb = (int) (b / count);

                image.setRGB(
                        x,
                        y,
                        (aa << 24) | (rr << 16) | (gg << 8) | bb
                            );
            }
        }
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(),
                src.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }

        BufferedImage out = new BufferedImage(
                src.getWidth(),
                src.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        return out;
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }

        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;

                dp[i][j] = Math.min(
                        Math.min(
                                dp[i - 1][j] + 1,
                                dp[i][j - 1] + 1
                                ),
                        dp[i - 1][j - 1] + cost
                                   );
            }
        }

        return dp[a.length()][b.length()];
    }

    private record CandidateMatch(String expected, double score) {
        boolean accepted() {
            return score >= 0.50;
        }
    }

    public record MatchedWord(
            String ocrText,
            String expectedWord,
            double confidence,
            Rectangle box
    ) {
    }

    public record RecognitionResult(
            String expectedText,
            List<MatchedWord> matches,
            List<String> missingWords,
            double score
    ) {
        static RecognitionResult empty(String expectedText) {
            return new RecognitionResult(
                    expectedText,
                    List.of(),
                    List.of(),
                    0.0
            );
        }
    }
}