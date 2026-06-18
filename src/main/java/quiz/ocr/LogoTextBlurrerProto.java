package quiz.ocr;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;

import java.io.File;
import java.util.List;

/** Runs {@link LogoTextBlurrer} on sample logos and writes before/after
 *  montages to /tmp so detection quality can be eyeballed. */
public class LogoTextBlurrerProto {

    public static void main(String[] args) throws Exception {
        String dir = "src/main/resources/flag/logos/svg/";
        String[] logos = args.length > 0 ? args : new String[]{
                "Boston Celtics", "Chicago Bulls", "Los Angeles Lakers",
                "New York Yankees", "Dallas Cowboys", "Green Bay Packers"
        };

        LogoTextBlurrer blurrer = new LogoTextBlurrer();

        for (String logo : logos) {
            File f = new File(dir + logo + ".svg");
            if (!f.exists()) {
                System.out.println("MISSING " + logo);
                continue;
            }
            Mat src = LogoTextBlurrer.readImage(f, 1000);
            List<LogoTextBlurrer.Region> regions = blurrer.detect(src);
            Mat covered = blurrer.cover(src, regions, 6);

            // Draw detection boxes on a copy of the original for visibility.
            Mat boxed = src.clone();
            for (LogoTextBlurrer.Region r : regions) {
                Imgproc.rectangle(boxed, new Point(r.x(), r.y()),
                        new Point(r.x() + r.w(), r.y() + r.h()),
                        new Scalar(0, 0, 255), 4);
            }

            Mat montage = new Mat();
            Core.hconcat(List.of(boxed, covered), montage);
            String outPath = "/tmp/blur_" + logo.replace(' ', '_') + ".png";
            Imgcodecs.imwrite(outPath, montage);
            System.out.println(logo + ": " + regions.size() + " regions -> " + outPath);
        }
    }
}
