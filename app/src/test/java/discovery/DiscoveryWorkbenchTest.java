package discovery;

import domain.DomainModel;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wikipedia and Commons discovery were separate applications, each selecting its own
 * instances: moving between sources meant choosing the same instances again. One
 * workbench now holds the selection and every source tab reads it, counting the selected
 * instances it cannot read rather than refusing them for everyone.
 */
class DiscoveryWorkbenchTest {

    @Test void aSelectionMadeOnceIsWhatEverySourceReads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            WikidataDynamicObject withoutArticle = deity("Q2831", "Ra", "");
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain(maat,
                    withoutArticle), new DiscoveryWorkbench.InitialSelection(
                    "EgyptianDeity", List.of(maat, withoutArticle), "a test"));
            try {
                assertEquals(2, workbench.selection().size());
                assertEquals("Fetch 1 selected article",
                        field(workbench.wikipedia(), "fetch", JButton.class).getText(),
                        "the Wikipedia tab reads only instances with an article");
                assertEquals("Fetch 2 selected entities",
                        field(workbench.commons(), "fetch", JButton.class).getText(),
                        "Commons reads every selected QID");
                assertEquals("Read views of 1 selected article",
                        field(workbench.pageviews(), "fetch", JButton.class).getText());
            } finally {
                workbench.close();
            }
        });
    }

    @Test void oneSourceFinishingDoesNotReleaseTheSelectionUnderAnotherThatRuns()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = deity("Q170471", "Maat", "Maat");
            DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain(maat), null);
            try {
                JComboBox<?> type = field(workbench.selection(), "type", JComboBox.class);
                Object wikipedia = new Object();
                Object commons = new Object();
                workbench.selection().setRunning(wikipedia, true);
                workbench.selection().setRunning(commons, true);
                workbench.selection().setRunning(wikipedia, false);
                assertFalse(type.isEnabled(), "Commons is still reading the selection");
                workbench.selection().setRunning(commons, false);
                assertTrue(type.isEnabled());
            } finally {
                workbench.close();
            }
        });
    }

    private static WikidataDynamicObject deity(String qid, String name, String article) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, name);
        value.type("EgyptianDeity");
        value.enwikiTitle(article);
        return value;
    }

    private static DomainModel domain(WikidataDynamicObject... instances) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("EgyptianDeity"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return () -> objectview.field.FieldSet.of(instances[0]).fields();
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(instances);
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
