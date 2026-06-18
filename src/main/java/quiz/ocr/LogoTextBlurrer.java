package quiz.ocr;

import nu.pattern.OpenCV;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Model-free text-region detector for logos, built on the classical
 * "morphological gradient → horizontal close → contour filtering" recipe (pure
 * OpenCV, no Tesseract, no DNN model). The intent is to <i>cover</i> the
 * wordmark — not read it — since for quiz prompts we already know the answer.
 *
 * <p>Covers detected regions with a mosaic (more reliable at hiding text than a
 * blur). Recognition is deliberately avoided: it's the brittle part that the
 * earlier Tesseract experiment failed on (logo text is outlined vector art).
 */
public class LogoTextBlurrer {

    static { OpenCV.loadLocally(); }

    /** A detected (text-like) region in image pixels. */
    public record Region(int x, int y, int w, int h) {}

    public List<Region> detect(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        int W = bgr.cols();
        int H = bgr.rows();

        // Stroke response: morphological gradient highlights character edges.
        Mat grad = new Mat();
        Imgproc.morphologyEx(gray, grad, Imgproc.MORPH_GRADIENT,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3)));

        Mat bw = new Mat();
        Imgproc.threshold(grad, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

        // Merge neighbouring characters into word/line blobs with a wide,
        // short horizontal kernel.
        Mat connected = new Mat();
        Size k = new Size(Math.max(9, W * 0.030), Math.max(2, H * 0.010));
        Imgproc.morphologyEx(bw, connected, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, k));

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(connected, contours, new Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        boolean debug = Boolean.getBoolean("logo.debug");
        if (debug) {
            System.out.println("  src " + W + "x" + H + ", raw contours " + contours.size());
        }

        List<Region> regions = new ArrayList<>();
        for (MatOfPoint c : contours) {
            Rect r = Imgproc.boundingRect(c);

            // Geometry of a text line: wider than tall, not a hairline, not the
            // whole image, and a decent stroke-fill inside.
            double aspect = r.width / (double) r.height;
            double fill = Core.countNonZero(bw.submat(r)) / (double) (r.width * r.height);

            boolean textLike =
                    r.height >= 0.025 * H && r.height <= 0.45 * H
                            && r.width >= 0.06 * W
                            && aspect >= 1.3 && aspect <= 25
                            && fill >= 0.25;

            if (debug && r.width * r.height > 0.005 * W * H) {
                System.out.printf("    rect %dx%d@(%d,%d) h/H=%.2f aspect=%.1f fill=%.2f -> %s%n",
                        r.width, r.height, r.x, r.y, r.height / (double) H, aspect, fill,
                        textLike ? "KEEP" : "drop");
            }

            if (textLike) {
                regions.add(new Region(r.x, r.y, r.width, r.height));
            }
        }
        return regions;
    }

    /** Cover each region with a mosaic; returns a new image. */
    public Mat cover(Mat bgr, List<Region> regions, int pad) {
        Mat out = bgr.clone();
        for (Region rg : regions) {
            int x = Math.max(0, rg.x() - pad);
            int y = Math.max(0, rg.y() - pad);
            int w = Math.min(bgr.cols() - x, rg.w() + 2 * pad);
            int h = Math.min(bgr.rows() - y, rg.h() + 2 * pad);
            if (w <= 0 || h <= 0) {
                continue;
            }
            Rect rect = new Rect(x, y, w, h);
            Mat roi = out.submat(rect);
            Mat small = new Mat();
            int sw = Math.max(1, w / 14);
            int sh = Math.max(1, h / 8);
            Imgproc.resize(roi, small, new Size(sw, sh), 0, 0, Imgproc.INTER_LINEAR);
            Imgproc.resize(small, roi, roi.size(), 0, 0, Imgproc.INTER_NEAREST);
        }
        return out;
    }

    /** Render an SVG (or read a raster) to a BGR Mat at the given width. */
    public static Mat readImage(File file, int width) throws Exception {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".svg")) {
            PNGTranscoder t = new PNGTranscoder();
            t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(file)) {
                t.transcode(new TranscoderInput(in), new TranscoderOutput(out));
            }
            File tmp = File.createTempFile("logo", ".png");
            tmp.deleteOnExit();
            try (var os = new java.io.FileOutputStream(tmp);
                 var is = new ByteArrayInputStream(out.toByteArray())) {
                is.transferTo(os);
            }
            Mat m = Imgcodecs.imread(tmp.getAbsolutePath());
            tmp.delete();
            return m;
        }
        return Imgcodecs.imread(file.getAbsolutePath());
    }
}
