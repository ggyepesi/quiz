package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.query.result.TableQueryResult;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubclassDiscoveryPanelTest {

    @Test void inspectionIsSeparateFromExplicitlyAddingAMembershipTarget()
            throws Exception {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), true));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(position);
        });

        panel.accept(new TableQueryResult(
                List.of("subclassLabel", "newCount", "examples", "subclass"),
                List.of(List.of("historical position", "84320",
                        "king; mayor; governor", "Q189290"))));
        flushEdt();

        assertEquals(1, panel.resultCount());
        assertTrue(panel.statusText().contains("1 direct subclasses found"));
        assertTrue(added.isEmpty(), "finding subclasses must not edit the model");

        onEdt(() -> {
            panel.selectRows(0);
            panel.addSelectedRows();
        });
        assertEquals(List.of("Q189290"), added);

        render(panel, "target/ui-artifacts/subclass-explorer.png");
    }

    @Test void aClassWithNoMembershipRelationSaysWhyNothingCanBeAdded()
            throws Exception {
        // Add writes into a membership RELATION. Before a class has one the action
        // could only have answered a click with silence, which directive 9 forbids:
        // a command produces a visible result or explains why it had none.
        GeneratedClassModel unbounded = new GeneratedClassModel("Position");
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(unbounded);
        });
        assertTrue(panel.statusText().contains("configure the class's triple"),
                "selecting the class already says what it is missing: "
                        + panel.statusText());

        panel.accept(new TableQueryResult(
                List.of("subclassLabel", "newCount", "examples", "subclass"),
                List.of(List.of("public office", "26002", "mayor", "Q294414"))));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.addEnabled(),
                "a class with no membership relation has nothing to add a target to");
        assertTrue(panel.addRefusal().contains("configure the class's triple"),
                "the disabled action says what is missing: " + panel.addRefusal());
        assertTrue(added.isEmpty());
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void render(Component component, String path) throws IOException {
        component.setSize(940, 500);
        layout(component);
        File artifact = new File(path);
        assertTrue(artifact.getParentFile().mkdirs() || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }
}
