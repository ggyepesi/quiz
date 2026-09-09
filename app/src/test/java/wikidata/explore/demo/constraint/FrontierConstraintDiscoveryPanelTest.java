package wikidata.explore.demo.constraint;

import org.junit.jupiter.api.Test;
import wikidata.explore.workbench.CachedPropertyViewablePanel;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontierConstraintDiscoveryPanelTest {
    @Test void existingDemoComposesPropertyExplorerAndExplicitFrontierActions() throws Exception {
        FrontierConstraintDiscoveryPanel[] panel = new FrontierConstraintDiscoveryPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new FrontierConstraintDiscoveryPanel());

        assertSame(panel[0].propertyPanel(), descendants(panel[0]).stream()
                .filter(CachedPropertyViewablePanel.class::isInstance).findFirst().orElseThrow());
        List<String> buttons = descendants(panel[0]).stream()
                .filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .map(AbstractButton::getText).toList();
        assertTrue(buttons.contains("Preview frontier step"));
        assertTrue(buttons.contains("Discover constraints"));
        assertTrue(buttons.contains("Use selected constraint"));
        assertTrue(buttons.contains("Use selected as next frontier"));
        assertTrue(panel[0].helpText().contains("nothing computes a closure"));
    }

    @Test void rendersTheObservableDiscoveryLayout() throws Exception {
        FrontierConstraintDiscoveryPanel[] panel = new FrontierConstraintDiscoveryPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new FrontierConstraintDiscoveryPanel();
            panel[0].setSize(1400, 850);
            layout(panel[0]);
        });
        File artifact = new File("target/ui-artifacts/frontier-constraint-discovery.png");
        assertTrue(artifact.getParentFile().isDirectory() || artifact.getParentFile().mkdirs());
        BufferedImage image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        SwingUtilities.invokeAndWait(() -> panel[0].paint(graphics));
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
        assertTrue(artifact.isFile());
    }

    private static List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) result.addAll(descendants(nested));
        }
        return result;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
