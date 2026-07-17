package aux;

import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.TranscoderInput;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import nu.pattern.OpenCV;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

public class SwingPreview {

    public static void main(String[] args) throws Exception {
        OpenCV.loadLocally();

        System.setProperty(
                "jna.library.path",
                "/usr/local/opt/tesseract/lib:/usr/local/opt/leptonica/lib"
        );

        String dir = "/Users/gyorgygyepesi/IdeaProjects/quiz/src/main/resources/flag/logos/svg/";
        String filename = dir + "Atlanta Braves.svg";
        BufferedImage original = SvgConverter.convert(filename);

        Mat image = bufferedImageToMat(original);

        BufferedImage ocrImage = makeOcrImage(image);

        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath("/usr/local/opt/tesseract/share/tessdata");
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);

        List<Word> words = tesseract.getWords(
                ocrImage,
                ITessAPI.TessPageIteratorLevel.RIL_WORD
        );

        Mat mask = Mat.zeros(image.size(), CvType.CV_8UC1);

        int padding = 8;

        for (Word word : words) {
            Rectangle r = word.getBoundingBox();

            int x = Math.max(r.x - padding, 0);
            int y = Math.max(r.y - padding, 0);
            int w = Math.min(r.width + padding * 2, image.cols() - x);
            int h = Math.min(r.height + padding * 2, image.rows() - y);

            Imgproc.rectangle(
                    mask,
                    new Point(x, y),
                    new Point(x + w, y + h),
                    new Scalar(255),
                    -1
            );
        }

        Mat result = new Mat();

        Photo.inpaint(
                image,
                mask,
                result,
                3.0,
                Photo.INPAINT_TELEA
        );

        BufferedImage cleaned = matToBufferedImage(result);

        SwingUtilities.invokeLater(() ->
                createUI(original, ocrImage, cleaned)
        );
    }

    private static BufferedImage makeOcrImage(Mat colorImage) {
        Mat gray = new Mat();
        Imgproc.cvtColor(colorImage, gray, Imgproc.COLOR_BGR2GRAY);

        Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

        Mat binary = new Mat();
        Imgproc.adaptiveThreshold(
                gray,
                binary,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                31,
                10
        );

        return matToBufferedImage(binary);
    }

    private static void createUI(
            BufferedImage original,
            BufferedImage ocrImage,
            BufferedImage cleaned
    ) {
        JFrame frame = new JFrame("OCR Debug / Text Removal");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new GridLayout(1, 3));

        frame.add(wrap("Original", original));
        frame.add(wrap("OCR Image", ocrImage));
        frame.add(wrap("Cleaned", cleaned));

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JPanel wrap(String title, BufferedImage image) {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        JLabel imageLabel = new JLabel(new ImageIcon(image));

        panel.add(titleLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(imageLabel), BorderLayout.CENTER);

        return panel;
    }

    private static Mat bufferedImageToMat(BufferedImage image) {
        BufferedImage converted = new BufferedImage(
                image.getWidth(),
                image.getHeight(),
                BufferedImage.TYPE_3BYTE_BGR
        );

        Graphics2D g = converted.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        byte[] pixels = ((DataBufferByte) converted
                .getRaster()
                .getDataBuffer())
                .getData();

        Mat mat = new Mat(
                converted.getHeight(),
                converted.getWidth(),
                CvType.CV_8UC3
        );

        mat.put(0, 0, pixels);

        return mat;
    }

    private static BufferedImage matToBufferedImage(Mat mat) {
        int type = BufferedImage.TYPE_BYTE_GRAY;

        if (mat.channels() == 3) {
            type = BufferedImage.TYPE_3BYTE_BGR;
        }

        byte[] data = new byte[
                mat.rows() * mat.cols() * (int) mat.elemSize()
                ];

        mat.get(0, 0, data);

        BufferedImage image = new BufferedImage(
                mat.cols(),
                mat.rows(),
                type
        );

        image.getRaster().setDataElements(
                0,
                0,
                mat.cols(),
                mat.rows(),
                data
        );

        return image;
    }
}

class SvgConverter {

    public static BufferedImage convert(String svgPath) throws Exception {

        TranscoderInput input = new TranscoderInput(new FileInputStream(svgPath));

        BufferedImageTranscoder transcoder = new BufferedImageTranscoder();
        transcoder.transcode(input, null);

        return transcoder.getImage();
    }

    static class BufferedImageTranscoder extends ImageTranscoder {
        private BufferedImage image;

        @Override
        public BufferedImage createImage(int w, int h) {
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }

        @Override
        public void writeImage(BufferedImage img, TranscoderOutput out) {
            this.image = img;
        }

        public BufferedImage getImage() {
            return image;
        }
    }
}