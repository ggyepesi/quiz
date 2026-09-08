package wikidata.explore.workbench;

import objectview.field.FieldSet;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import wikidata.explore.WikidataProperty;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The property catalogue links to its curated P1855 examples without caching a
 * second copy of Wikidata's example data. */
class WikidataPropertyViewableTest {

    @Test void everyPropertyLinksToItsQualifiedP1855Examples() throws Exception {
        WikidataPropertyViewable property = new WikidataPropertyViewable(
                new WikidataProperty("P39", "position held", "", "WikibaseItem", "AUTO"));

        String url = property.examples();
        assertNotNull(url);
        String query = URLDecoder.decode(url.substring(url.indexOf('#') + 1),
                StandardCharsets.UTF_8);
        assertTrue(query.contains("wd:P39 p:P1855 ?statement"), query);
        assertTrue(query.contains("OPTIONAL { ?statement pq:P39 ?value. }"), query);
        assertEquals("Examples", FieldSet.of(property).field("examples").linkText());

        SearchableView view = SearchableView.builder(List.of(property))
                .type(WikidataPropertyViewable.class)
                .mode(RenderingMode.TABLE)
                .build();
        JComponent component = view;
        component.setSize(1000, 180);
        layoutTree(component);
        File artifact = new File("target/ui-artifacts/property-examples-link.png");
        assertTrue(artifact.getParentFile().mkdirs() || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) layoutTree(nested);
        }
    }
}
