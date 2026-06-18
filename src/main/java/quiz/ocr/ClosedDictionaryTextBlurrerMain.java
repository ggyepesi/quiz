package quiz.ocr;

import java.io.File;

public class ClosedDictionaryTextBlurrerMain {
    static final String leptonicaPath = "/usr/local/opt" +
            "/tesseract/lib:/usr/local/opt/leptonica/lib";

    static final String logoSvgDirectory = "/Users/gyorgygyepesi/IdeaProjects" +
            "/quiz/src/main/resources/flag/logos/svg/";

    public static void main(String[] args) throws Exception {
        System.setProperty("jna.library.path", leptonicaPath);
        ClosedDictionaryTextBlurrer recognizer =
                new ClosedDictionaryTextBlurrer(
                        "/usr/local/share/tessdata",
                        "eng"
                );

        final String filename = "Boston Celtics";
        File input = new File(logoSvgDirectory + filename + ".svg");
        ClosedDictionaryTextBlurrer.RecognitionResult result = recognizer.recognize(
                input,
                "Boston Celtics");

        System.out.println("Score: " + result.score());
        System.out.println("Missing: " + result.missingWords());

        for (ClosedDictionaryTextBlurrer.MatchedWord m : result.matches()) {
            System.out.println(
                    m.expectedWord()
                            + " <- OCR: "
                            + m.ocrText()
                            + " box="
                            + m.box()
                            + " conf="
                            + m.confidence()
                              );
        }

        recognizer.blurExpectedTextToFile(
                input,
                "Boston Celtics",
                new File(logoSvgDirectory + filename +
                        " Blurred.png"),
                10);
    }
}
