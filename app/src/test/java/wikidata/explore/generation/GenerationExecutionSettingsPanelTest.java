package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The execution panel shared by class and domain generation is a visible contract. */
class GenerationExecutionSettingsPanelTest {

    @Test
    void networkGenerationShowsEveryRunScopedControl() throws Exception {
        GenerationExecutionSettingsPanel panel = new GenerationExecutionSettingsPanel(
                new GenerationExecutionSettings(false));
        List<String> labels = labels(panel);

        for (String expected : List.of("Memory/cache profile", "Network intensity",
                "Require a complete result", "Checkpoint/resume: unavailable",
                "Log detail: phase summaries and individual requests")) {
            assertTrue(labels.stream().anyMatch(label -> label.contains(expected)),
                    () -> "missing execution control: " + expected + " in " + labels);
        }

        panel.setSize(760, 260);
        layout(panel);
        File artifact = new File("target/ui-artifacts/generation-execution-settings.png");
        assertTrue(artifact.getParentFile().mkdirs()
                || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static List<String> labels(Component root) {
        List<String> result = new ArrayList<>();
        if (root instanceof JLabel label) result.add(label.getText());
        if (root instanceof javax.swing.AbstractButton button) result.add(button.getText());
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) result.addAll(labels(child));
        }
        return result;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
