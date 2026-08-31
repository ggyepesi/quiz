package wikidata.explore.codegen;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.viewconfig.ViewConfig;
import objectview.demo.MultiView;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.lang.reflect.Field;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the generated Oscars snapshot at the owned-name rendering boundary.
 *
 * <p>The snapshot is regenerable and therefore not in version control, so a fresh clone
 * does not have one. The test SKIPS then rather than failing: a missing local artifact
 * says nothing about the rendering this pins, and a suite that is red on first checkout
 * teaches a reader to ignore red. The model beside it IS committed, so only the snapshot
 * decides whether this can run.
 */
class OscarsSavedNameRenderingTest {

    private static final File OSCARS = new File("../data/wikidata/oscarnominations");

    static boolean oscarsSnapshotPresent() {
        return new File(OSCARS, "oscarnominations.snapshot.json").isFile();
    }

    @org.junit.jupiter.api.condition.EnabledIf("oscarsSnapshotPresent")
    @Test void structuredNameMapsAsAnInlineValueWithItsOwnFields() throws Exception {
        File dir = OSCARS;
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File(dir, "oscarnominations.model.json"));
        List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore().loadAll(
                new File(dir, "oscarnominations.snapshot.json"));
        WikidataDynamicObject person = all.stream()
                .filter(o -> "Q72717".equals(o.qid()) && "Person".equals(o.typeName()))
                .findFirst().orElseThrow();
        WikidataDynamicObject name = (WikidataDynamicObject) person.get("structuredName");
        assertNotNull(name);
        assertFalse(name.dynamicFields().isEmpty());

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(model)) {
            Viewable mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(person)).getFirst();
            Field site = mapped.getClass().getDeclaredField("structuredName");
            assertTrue(ViewableAdapter.isInline(site));
            site.setAccessible(true);
            Viewable mappedName = (Viewable) site.get(mapped);
            assertNotNull(mappedName);
            assertTrue(mappedName.fields().has("givenName"));
            assertNotNull(mappedName.fields().read("givenName"));

            ViewConfig personOnly = ViewConfig.leaf();
            personOnly.setCls((Class<? extends Viewable>) mapped.getClass());
            personOnly.addField("structuredName", ViewConfig.leaf());
            RenderContext context = new RenderContext(List.of(mapped));
            // Reproduce ModelBuilder's shared multi-type context: the standalone Name
            // section may have a deliberately restrictive view, but that must not empty
            // the owned Name value opened through Person.structuredName.
            context.putClassConfig(mappedName.getClass(), ViewConfig.leaf());
            Card[] collapsed = new Card[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> collapsed[0] = new Card(
                    mapped, personOnly, context, false));
            assertTrue(containsComponent(collapsed[0], objectview.render.ReferenceRow.class),
                    "the structured value has an explicit disclosure control");

            context.setExpanded(mappedName, true);
            Card[] rendered = new Card[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> rendered[0] = new Card(
                    mapped, personOnly, context, false));
            String visible = componentText(rendered[0]);
            assertTrue(visible.contains("familyName") || visible.contains("givenName"),
                    () -> "structuredName rendered no nested name fields: " + visible);

            MultiView multi = new MultiView();
            multi.addSection("Person", mapped.getClass(), List.of(mapped));
            multi.addSection("Name", mappedName.getClass(), List.of(mappedName));
            javax.swing.SwingUtilities.invokeAndWait(() -> multi.build(1));
            assertTrue(multi.context().revealPath(mapped,
                    objectview.field.FieldPath.of("structuredName", "givenName",
                            objectview.field.ViewableContractFieldSet.DISPLAY_KEY)),
                    "search reveals the owned value even though it is also top-level");
            assertTrue(multi.context().isExpanded(mappedName),
                    "structuredName is expanded until the nested hit can be rendered");
            javax.swing.SwingUtilities.invokeAndWait(() -> multi.context().focusTopLevel(mapped));
            Card actual = findRenderedCard(multi, mapped);
            assertNotNull(actual, "the virtual Person section materializes Elia Kazan");
            String actualVisible = componentText(actual);
            assertTrue(actualVisible.contains("familyName")
                            || actualVisible.contains("givenName"),
                    () -> "ModelBuilder-style MultiView rendered no nested fields: "
                            + actualVisible);
        }
    }

    private static String componentText(Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof javax.swing.JLabel label && label.getText() != null) {
            text.append(label.getText()).append('\n');
        }
        if (component instanceof javax.swing.JComponent jc
                && jc.getBorder() instanceof javax.swing.border.TitledBorder title) {
            text.append(title.getTitle()).append('\n');
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                text.append(componentText(child));
            }
        }
        return text.toString();
    }

    private static boolean containsComponent(Component component, Class<?> type) {
        if (type.isInstance(component)) return true;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsComponent(child, type)) return true;
            }
        }
        return false;
    }

    private static Card findRenderedCard(Component component, Viewable target) {
        if (component instanceof Card card && card.renderedInstance() == target) return card;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Card found = findRenderedCard(child, target);
                if (found != null) return found;
            }
        }
        return null;
    }
}
