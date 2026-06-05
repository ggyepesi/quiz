package aux;

import nu.pattern.OpenCV;
import net.sourceforge.tess4j.*;
import net.sourceforge.tess4j.util.LoadLibs;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

public class TextInpaintRemover {
    public static void main(String[] args) throws Exception {
        OpenCV.loadLocally();

        String inputPath = "input.png";
        String outputPath = "output.png";

        BufferedImage bufferedImage = ImageIO.read(new File(inputPath));

        ITesseract tesseract = new Tesseract();
        File tessDataFolder = LoadLibs.extractTessResources("tessdata");
        tesseract.setDatapath(tessDataFolder.getAbsolutePath());
        tesseract.setLanguage("eng");

        List<Word> words = tesseract.getWords(
                bufferedImage,
                ITessAPI.TessPageIteratorLevel.RIL_WORD
        );

        Mat image = Imgcodecs.imread(inputPath);
        Mat mask = Mat.zeros(image.size(), CvType.CV_8UC1);

        int padding = 4;

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

        Imgcodecs.imwrite(outputPath, result);

        System.out.println("Saved cleaned image to " + outputPath);
    }
}