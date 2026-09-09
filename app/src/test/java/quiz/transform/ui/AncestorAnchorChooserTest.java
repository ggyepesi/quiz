package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AncestorAnchorChooserTest {

    @Test void searchingDoesNotChooseAnAnchorUntilAddIsExplicit() throws Exception {
        DynamicViewable king = value("Q12097", "King");
        DynamicViewable writer = value("Q49757", "Writer");
        AncestorAnchorChooser[] chooser = new AncestorAnchorChooser[1];
        SwingUtilities.invokeAndWait(() -> {
            chooser[0] = new AncestorAnchorChooser(List.of(
                    new TransformController.AncestorCandidate(king, 12, 48175),
                    new TransformController.AncestorCandidate(writer, 4, 9)));
            chooser[0].search("king");
        });
        assertEquals(List.of(), chooser[0].selectedAnchors(),
                "finding/inspecting a row is not a configuration change");

        SwingUtilities.invokeAndWait(() -> {
            chooser[0].selectFound(0);
            chooser[0].addSelected();
        });
        assertEquals(List.of(king), chooser[0].selectedAnchors());
        assertEquals("King — Q12097", AncestorAnchorChooser.candidateLabel(king));
        assertEquals("King — Q12097  ·  48,175 below it, 12 direct",
                AncestorAnchorChooser.candidateChoiceLabel(
                        new TransformController.AncestorCandidate(king, 12, 48175)),
                "an anchor is chosen on what it would classify, not on its fan-out");

        render(chooser[0], "nearest-ancestor-anchor-chooser.png");
    }

    @Test void blankQueryOffersRankedCandidatesAndReportsTruncation() throws Exception {
        List<TransformController.AncestorCandidate> candidates =
                new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) {
            candidates.add(new TransformController.AncestorCandidate(
                    value("Q" + i, "Position " + i), 250 - i, 250 - i));
        }
        AncestorAnchorChooser[] chooser = new AncestorAnchorChooser[1];
        SwingUtilities.invokeAndWait(() ->
                chooser[0] = new AncestorAnchorChooser(candidates));

        assertEquals(200, chooser[0].shownMatchCount());
        assertEquals("Showing 200 of 250 matching ancestors.",
                chooser[0].matchStatusText());
        render(chooser[0], "nearest-ancestor-ranked-initial.png");
    }

    private static DynamicViewable value(String id, String name) {
        DynamicViewable value = new DynamicViewable(id, name);
        value.type("Position");
        return value;
    }

    private static void render(AncestorAnchorChooser chooser, String filename)
            throws Exception {
        File artifact = new File("target/ui-artifacts/" + filename);
        assertEquals(true, artifact.getParentFile().isDirectory()
                || artifact.getParentFile().mkdirs());
        BufferedImage image = new BufferedImage(560, 460, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        SwingUtilities.invokeAndWait(() -> {
            chooser.setSize(560, 460);
            layout(chooser);
            chooser.paint(graphics);
        });
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
