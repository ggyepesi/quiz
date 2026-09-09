package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.model.GeneratedProjectModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reported failure was ordering-specific: Load instances opened its window
 * before the accepted run reached the EDT, leaving a count-less title behind. */
class LoadedInstancesCountVisibilityTest {

    @Test void acceptingAnyRunRefreshesAnAlreadyOpenInstancesWindow() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/wikidata/explore/workbench/ModelBuilderFrame.java"));
        int accept = source.indexOf("private void acceptGenerationRun(GenerationRun run)");
        int nextMethod = source.indexOf("\n    private ", accept + 1);
        String body = source.substring(accept, nextMethod);

        assertTrue(body.contains("refreshInstancesWindowTitle();"),
                "installing a loaded/generated run must refresh the visible count");
    }

    @Test void loadedRunTitleShowsDistinctPerClassCount() throws Exception {
        WikidataDynamicObject first = position("Q1");
        WikidataDynamicObject duplicate = position("Q1");
        WikidataDynamicObject second = position("Q2");
        GenerationRun loaded = new GenerationRun(new GeneratedProjectModel(), 0, null,
                java.util.List.of(first, duplicate, second), null, java.util.List.of());

        String title = ModelBuilderFrame.instancesTitle(loaded, loaded.modelSnapshot());
        assertEquals("Generated instances — Position 2  (2 distinct)", title);

        JPanel visibleTitle = new JPanel(new BorderLayout());
        visibleTitle.add(new JLabel(title), BorderLayout.CENTER);
        visibleTitle.setSize(700, 44);
        visibleTitle.doLayout();
        BufferedImage image = new BufferedImage(700, 44, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        visibleTitle.printAll(graphics);
        graphics.dispose();
        Path artifact = Path.of("target/ui-artifacts/loaded-instances-count.png");
        Files.createDirectories(artifact.getParent());
        ImageIO.write(image, "png", artifact.toFile());
    }

    private static WikidataDynamicObject position(String qid) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, qid);
        object.type("Position");
        return object;
    }
}
