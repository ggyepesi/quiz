package quiz.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import quiz.ocr.LogoMaskBlurEditor.MaskFile;
import quiz.ocr.LogoMaskBlurEditor.RelativePolygon;
import quiz.ocr.LogoMaskBlurEditor.RelativeRect;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Applies a {@link LogoMaskBlurEditor.MaskFile} (rectangles + polygons authored
 * in the editor) to an image — blurring inside the shapes, or outside them when
 * the mask's {@code blurComplement} flag is set. The headless counterpart to
 * the editor's live preview, used by the quiz blur resolver.
 */
public final class MaskBlur {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MaskBlur() {}

    public static MaskFile load(File json) throws Exception {
        return MAPPER.readValue(json, MaskFile.class);
    }

    /** Blur the masked regions of {@code src}; returns a new image, or the
     *  original unchanged if the mask is empty/null. */
    public static BufferedImage apply(BufferedImage src, MaskFile mask, int radius) {
        if (mask == null) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();

        Area area = new Area();
        addRects(area, mask.rectangles, w, h);
        addRects(area, mask.regions, w, h); // backward-compatible old field name
        if (mask.polygons != null) {
            for (RelativePolygon rp : mask.polygons) {
                if (rp != null) {
                    area.add(new Area(rp.toPixels(w, h)));
                }
            }
        }
        if (area.isEmpty()) {
            return src;
        }

        BufferedImage out = deepCopy(src);
        Shape region = area;
        if (mask.blurComplement) {
            Area complement = new Area(new Rectangle(0, 0, w, h));
            complement.subtract(area);
            region = complement;
        }
        blurShape(out, region, radius);
        return out;
    }

    private static void addRects(Area area, List<RelativeRect> rects, int w, int h) {
        if (rects == null) {
            return;
        }
        for (RelativeRect rr : rects) {
            if (rr != null) {
                area.add(new Area(clamp(rr.toPixels(w, h), w, h)));
            }
        }
    }

    private static void blurShape(BufferedImage image, Shape shape, int radius) {
        Rectangle bounds = clamp(shape.getBounds(), image.getWidth(), image.getHeight());
        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }
        BufferedImage copy = deepCopy(image);

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (!shape.contains(x, y)) {
                    continue;
                }
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int yy = y - radius; yy <= y + radius; yy++) {
                    for (int xx = x - radius; xx <= x + radius; xx++) {
                        if (xx < 0 || yy < 0 || xx >= copy.getWidth() || yy >= copy.getHeight()) {
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
                image.setRGB(x, y, ((int) (a / count) << 24) | ((int) (r / count) << 16)
                        | ((int) (g / count) << 8) | (int) (b / count));
            }
        }
    }

    private static Rectangle clamp(Rectangle r, int maxW, int maxH) {
        int x1 = Math.max(0, r.x);
        int y1 = Math.max(0, r.y);
        int x2 = Math.min(maxW, r.x + r.width);
        int y2 = Math.min(maxH, r.y + r.height);
        return new Rectangle(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
