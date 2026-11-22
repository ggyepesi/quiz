package quiz.ocr.app;

import quiz.ocr.LogoMaskBlurEditor;

import javax.swing.SwingUtilities;

/** Desktop composition root for authoring quiz image masks. */
public final class LogoMaskBlurEditorApp {
    private LogoMaskBlurEditorApp() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LogoMaskBlurEditor().setVisible(true));
    }
}
