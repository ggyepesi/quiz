package domain.workbench.ui;

import domain.config.ConfigurationNode;
import domain.config.ConfigurationReferenceInventory;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forces the shared semantic behavior used by every configuration editor. */
class ConfigurationTreePanelTest {

    @Test void replacingAProjectionRestoresTheSelectedReferenceWithoutOpeningIt()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ConfigurationReferenceInventory.Site target =
                    new ConfigurationReferenceInventory.Site(
                            ConfigurationReferenceInventory.SiteKind.CLASS,
                            "target-id", "Target", "", "");
            ConfigurationNode root = tree(target);
            ConfigurationNode.Id referenceId = root.children().getFirst().id();
            ConfigurationTreePanel panel =
                    new ConfigurationTreePanel("test.configuration", "Configuration");
            List<ConfigurationReferenceInventory.Site> opened = new ArrayList<>();
            panel.onNavigate(opened::add);
            panel.show(root, ignored -> new JPanel());

            panel.select(referenceId);
            assertEquals(List.of(), opened, "selection is inspection only");
            assertTrue(panel.openReferenceButton().isEnabled());

            panel.show(tree(target), ignored -> new JPanel());
            assertEquals(referenceId, panel.selectedNode().id());
            assertTrue(panel.openReferenceButton().isEnabled(),
                    "a stable selection keeps its explicit Open action after refresh");
            panel.openReferenceButton().doClick();
            assertEquals(List.of(target), opened);
        });
    }

    private static ConfigurationNode tree(ConfigurationReferenceInventory.Site target) {
        return new ConfigurationNode(new ConfigurationNode.Id("owner-id", "", "root"),
                ConfigurationNode.Kind.STORED_CONFIGURATION, "Owner", "owner",
                List.of(new ConfigurationNode(
                        new ConfigurationNode.Id("owner-id", "", "reference"),
                        ConfigurationNode.Kind.REFERENCE, "Target", "reference",
                        target, List.of())));
    }
}
