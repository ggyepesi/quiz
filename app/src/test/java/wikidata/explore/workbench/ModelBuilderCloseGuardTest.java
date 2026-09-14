package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBuilderCloseGuardTest {
    @Test void cleanIdleWindowClosesWithoutTeachingTheReaderToDismissWarnings() {
        var state = new ModelBuilderCloseGuard.State(false, 0, false);
        assertFalse(state.needsAttention());
    }

    @Test void mainWindowCannotBypassTheGuardThroughItsDefaultCloseOperation()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/wikidata/explore/workbench/ModelBuilderFrame.java"));
        int close = source.indexOf("private void requestApplicationClose()");
        int nextMethod = source.indexOf("\n    private ", close + 1);
        String closeBody = source.substring(close, nextMethod);

        assertTrue(source.contains("setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)"));
        assertTrue(source.contains("requestApplicationClose();"));
        assertTrue(closeBody.contains("sourceWorkbench.applyEdits();"));
        assertTrue(closeBody.contains("hasUnsavedGeneratedInstances() ? lastRun.size() : 0"));
        assertTrue(closeBody.contains("querySession.runner().cancel();"));
        assertTrue(closeBody.contains("processRunner.cancel();"));
    }

    @Test void unsavedConfigurationAndInstancesCanBeSavedOrExplicitlyDiscarded() {
        var state = new ModelBuilderCloseGuard.State(true, 153, false);

        assertEquals(List.of("Keep working", "Save and close", "Close without saving"),
                List.of(ModelBuilderCloseGuard.choices(state)));
        assertEquals(ModelBuilderCloseGuard.Decision.KEEP_OPEN,
                ModelBuilderCloseGuard.decision(state, 0));
        assertEquals(ModelBuilderCloseGuard.Decision.SAVE_AND_CLOSE,
                ModelBuilderCloseGuard.decision(state, 1));
        assertEquals(ModelBuilderCloseGuard.Decision.CLOSE_WITHOUT_SAVING,
                ModelBuilderCloseGuard.decision(state, 2));
    }

    @Test void runningWorkCanOnlyBeKeptOrExplicitlyCancelledBeforeClose() {
        var state = new ModelBuilderCloseGuard.State(true, 153, true);

        assertEquals(List.of("Keep working", "Cancel work and close"),
                List.of(ModelBuilderCloseGuard.choices(state)));
        assertEquals(ModelBuilderCloseGuard.Decision.KEEP_OPEN,
                ModelBuilderCloseGuard.decision(state, -1));
        assertEquals(ModelBuilderCloseGuard.Decision.CANCEL_AND_CLOSE,
                ModelBuilderCloseGuard.decision(state, 1));
    }

    @Test void visibleWarningNamesEveryAtRiskItem() throws Exception {
        var state = new ModelBuilderCloseGuard.State(true, 153, true);
        JComponent content = ModelBuilderCloseGuard.content(state);
        String text = componentText(content);

        assertTrue(text.contains("process is still running"));
        assertTrue(text.contains("Configuration changes"));
        assertTrue(text.contains("153 generated instances"));
        assertTrue(text.contains("cannot be saved until it finishes"));

        content.setSize(content.getPreferredSize());
        content.doLayout();
        BufferedImage image = new BufferedImage(
                Math.max(1, content.getWidth()), Math.max(1, content.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        content.printAll(graphics);
        graphics.dispose();
        Path artifact = Path.of("target/ui-artifacts/modelbuilder-close-warning.png");
        Files.createDirectories(artifact.getParent());
        ImageIO.write(image, "png", artifact.toFile());
    }

    private static String componentText(java.awt.Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof javax.swing.JLabel label) {
            text.append(label.getText()).append('\n');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                text.append(componentText(child));
            }
        }
        return text.toString();
    }
}
