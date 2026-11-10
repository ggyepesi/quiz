package wikipedia.discovery;

import domain.DomainModel;
import objectview.Viewable;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.JButton;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Selection must immediately make the action that consumes it available. */
class WikipediaDiscoveryPanelTest {

    @Test void infoboxDiscoveryIsVisibleBeforeTheFirstFetch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain(maat));
            try {
                JTabbedPane results = field(panel, "resultTabs", JTabbedPane.class);
                assertEquals(List.of("Articles — 0", "Sections — 0", "Infobox values — 0",
                                "Categories — 0", "Links by section — 0", "Block kinds — 0"),
                        java.util.stream.IntStream.range(0, results.getTabCount())
                                .mapToObj(results::getTitleAt).toList());
            } finally {
                panel.close();
            }
        });
    }

    @Test void selectingAnInstanceEnablesAddingItAndBuildsTheFirstSelectedCard()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
            maat.type("EgyptianDeity");
            maat.enwikiTitle("Maat");
            WikipediaDiscoveryPanel panel = new WikipediaDiscoveryPanel(domain(maat));
            try {
                SearchableView instances = field(panel, "instancesView", SearchableView.class);
                JButton add = field(panel, "addSelection", JButton.class);

                assertFalse(add.isEnabled());
                instances.renderContext().select(maat);
                assertTrue(add.isEnabled(),
                        "the action consuming the selected card must become available");

                add.doClick();

                SearchableView selected = field(panel, "selectedView", SearchableView.class);
                assertNotNull(selected.cardList(),
                        "the first addition must create the initially empty cards panel");
                assertEquals(List.of(maat),
                        selected.cardList().getVirtualList().items());

                JButton remove = field(panel, "removeSelection", JButton.class);
                assertFalse(remove.isEnabled());
                selected.renderContext().select(maat);
                assertTrue(remove.isEnabled(),
                        "the action consuming a selected sample card must become available");
            } finally {
                panel.close();
            }
        });
    }

    private static DomainModel domain(WikidataDynamicObject instance) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("EgyptianDeity"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return () -> objectview.field.FieldSet.of(instance).fields();
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(instance);
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
