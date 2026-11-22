package quiz.app;

import aux.Constants;
import quiz.QuizFactory;

import javax.swing.SwingUtilities;

/** Desktop composition root for choosing a built domain and starting a quiz. */
public final class QuizApp {
    private QuizApp() { }

    public static void main(String[] args) {
        Constants.setFontSizeMultiplier(1.5f);
        SwingUtilities.invokeLater(QuizFactory::showQuizSelector);
    }
}
