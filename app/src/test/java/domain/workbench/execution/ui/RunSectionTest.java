package domain.workbench.execution.ui;

import domain.build.GenerationRun;
import domain.compile.transform.SelfReferenceLedger;
import domain.config.GeneratedProjectModel;
import domain.runtime.WikidataDynamicObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the actual controls used by ModelBuilder, not a copy of its button rows. */
class RunSectionTest {
    @Test void generationReuseAndInspectionKeepTheirRowsAndAllControlsFit() throws Exception {
        BufferedImage[] artifact = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            var section = new Fixture().section;
            assertEquals(List.of(
                    List.of("Generate class", "Generate domain", "Build project", "Depth:", "spinner"),
                    List.of("Remap (no download)", "Enrich (declared fields)"),
                    List.of("Show instances", "Run report", "Statements…")),
                    Arrays.stream(section.getComponents()).map(row -> children(row).stream()
                            .map(RunSectionTest::label).toList()).toList());
            var grid = assertInstanceOf(GridLayout.class, section.getLayout());
            assertEquals(1, grid.getColumns());
            assertEquals(0, grid.getHgap());
            assertEquals(0, grid.getVgap());

            var spinner = depthSpinner(section);
            var model = assertInstanceOf(SpinnerNumberModel.class, spinner.getModel());
            assertEquals(1, section.depth());
            assertEquals(0, model.getMinimum());
            assertEquals(5, model.getMaximum());
            assertEquals(1, model.getStepSize());
            var depthLabel = assertInstanceOf(JLabel.class, children(section.getComponent(0)).get(3));
            assertEquals(depthLabel.getToolTipText(), spinner.getToolTipText());

            for (String kind : List.of("domain", "model")) {
                section.showProject(kind.equals("model"), true);
                // The default 1750px frame gives this pane 34% of the width, less its border.
                section.setSize(new Dimension(575, section.getPreferredSize().height));
                section.doLayout();
                for (Component component : section.getComponents()) {
                    JPanel row = assertInstanceOf(JPanel.class, component);
                    var flow = assertInstanceOf(FlowLayout.class, row.getLayout());
                    assertEquals(FlowLayout.LEFT, flow.getAlignment());
                    assertEquals(4, flow.getHgap());
                    assertEquals(2, flow.getVgap());
                    row.doLayout();
                    for (Component control : row.getComponents()) {
                        assertTrue(row.getVisibleRect().contains(control.getBounds()),
                                () -> kind + ": " + label(control) + " is clipped: " + control.getBounds());
                    }
                }
            }
            layout(spinner);
            artifact[0] = new BufferedImage(section.getWidth(), section.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = artifact[0].createGraphics();
            try {
                section.printAll(graphics);
            } finally {
                graphics.dispose();
            }
        });
        Path image = Path.of("target/ui-artifacts/modelbuilder-run-section.png");
        Files.createDirectories(image.getParent());
        ImageIO.write(artifact[0], "png", image.toFile());
    }

    @Test void onlyExplicitButtonsStartActionsAndSyncingDepthDoesNotEditAClass() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var fixture = new Fixture();
            var section = fixture.section;
            section.showProject(true, false);
            assertFalse(section.isVisible());
            assertNotNull(button(section, "Generate model instances"));
            section.showProject(true, true);
            assertTrue(section.isVisible());
            section.syncDepth(4);
            assertEquals(4, section.depth());
            assertTrue(fixture.depths.isEmpty());
            depthSpinner(section).setValue(3);
            assertEquals(List.of(3), fixture.depths);
            assertTrue(fixture.actions.isEmpty());

            section.updateRunReport(run(SelfReferenceLedger.EMPTY));
            section.setRunLocked(false, true);
            for (String caption : List.of("Generate class", "Generate model instances", "Build project",
                    "Remap (no download)", "Enrich (declared fields)", "Show instances",
                    "Run report", "Statements…")) {
                button(section, caption).doClick(0);
            }
            section.requestDomainGeneration();
            assertEquals(List.of("class", "domain", "build", "remap", "enrich", "instances",
                    "report", "statements", "domain"), fixture.actions);
        });
    }

    @Test void aHostWithItsOwnBuildInstancesAndStatementsActionsGetsNoSecondButton() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var section = new RunSection(new RunSection.Actions(() -> { }, () -> { }, null,
                    () -> { }, () -> { }, null, () -> { }, null), ignored -> { });
            assertEquals(List.of(
                    List.of("Generate class", "Generate domain", "Depth:", "spinner"),
                    List.of("Remap (no download)", "Enrich (declared fields)"),
                    List.of("Run report")),
                    Arrays.stream(section.getComponents()).map(row -> children(row).stream()
                            .map(RunSectionTest::label).toList()).toList());
        });
    }

    @Test void aRunningOperationLocksMutationButStillAllowsTheCurrentRunToBeInspected()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var fixture = new Fixture();
            var section = fixture.section;
            assertFalse(button(section, "Run report").isEnabled());

            var ledger = SelfReferenceLedger.ran(List.of(),
                    List.of(new WikidataDynamicObject("Q1", "One")));
            section.updateRunReport(run(ledger));
            section.setRunLocked(true, true);
            assertFalse(depthSpinner(section).isEnabled());
            for (String caption : List.of("Generate class", "Generate domain", "Build project",
                    "Remap (no download)", "Enrich (declared fields)")) {
                assertFalse(button(section, caption).isEnabled(), caption);
            }
            section.requestDomainGeneration();
            assertTrue(fixture.actions.isEmpty(), "graph follow-up obeys the same disabled button");
            for (String caption : List.of("Show instances", "Self-reference decisions (1)", "Statements…")) {
                assertTrue(button(section, caption).isEnabled(), caption);
            }

            section.updateRunReport(null);
            section.setRunLocked(false, false);
            assertTrue(depthSpinner(section).isEnabled());
            for (String caption : List.of("Generate class", "Generate domain", "Build project")) {
                assertTrue(button(section, caption).isEnabled(), caption);
            }
            for (String caption : List.of("Run report", "Remap (no download)", "Enrich (declared fields)")) {
                assertFalse(button(section, caption).isEnabled(), caption);
            }
            section.updateRunReport(run(SelfReferenceLedger.EMPTY));
            section.setRunLocked(false, true);
            assertTrue(button(section, "Run report").isEnabled());
            assertTrue(button(section, "Remap (no download)").isEnabled());
            assertTrue(button(section, "Enrich (declared fields)").isEnabled());
        });
    }

    private static GenerationRun run(SelfReferenceLedger ledger) {
        return new GenerationRun(new GeneratedProjectModel(), 1, null, List.of(), null, List.of(),
                null, List.of(), null, List.of(), GenerationRun.SelfReferenceAudit.restored(ledger),
                null, null, null);
    }

    private static JSpinner depthSpinner(RunSection section) {
        return controls(section).stream().filter(JSpinner.class::isInstance).map(JSpinner.class::cast)
                .findFirst().orElseThrow();
    }

    private static JButton button(RunSection section, String caption) {
        return controls(section).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> caption.equals(button.getText())).findFirst().orElseThrow();
    }

    private static List<Component> controls(RunSection section) {
        return Arrays.stream(section.getComponents()).flatMap(row -> children(row).stream()).toList();
    }

    private static List<Component> children(Component row) {
        return List.of(assertInstanceOf(JPanel.class, row).getComponents());
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }

    private static String label(Component control) {
        return switch (control) {
            case JButton button -> button.getText();
            case JLabel label -> label.getText();
            case JSpinner ignored -> "spinner";
            default -> control.getClass().getSimpleName();
        };
    }

    private static final class Fixture {
        final List<String> actions = new ArrayList<>();
        final List<Integer> depths = new ArrayList<>();
        final RunSection section = new RunSection(new RunSection.Actions(
                () -> actions.add("class"), () -> actions.add("domain"), () -> actions.add("build"),
                () -> actions.add("remap"), () -> actions.add("enrich"), () -> actions.add("instances"),
                () -> actions.add("report"), () -> actions.add("statements")), depths::add);
    }
}
