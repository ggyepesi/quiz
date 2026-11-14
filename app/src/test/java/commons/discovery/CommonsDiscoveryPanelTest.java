package commons.discovery;

import domain.DomainModel;
import objectview.Viewable;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonsDiscoveryPanelTest {

    @Test void openingTheWorkbenchDoesNotFetchAndSelectionControlsTheExactAction()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = entity("Q170471", "Maat", "EgyptianDeity");
            WikidataDynamicObject ra = entity("Q2831", "Ra", "EgyptianDeity");
            CommonsDiscoveryPanel panel = panel(
                    domain(List.of(maat, ra), List.of("EgyptianDeity")));
            try {
                JButton fetch = field(panel, "fetch", JButton.class);
                SearchableView instances = field(field(panel, "selection", Object.class), "instancesView", SearchableView.class);
                JTabbedPane results = field(panel, "resultTabs", JTabbedPane.class);
                assertEquals("Connected objects — 0", results.getTitleAt(0));
                assertFalse(fetch.isEnabled());
                assertEquals("Fetch 0 selected entities", fetch.getText());

                instances.renderContext().select(maat);
                JButton add = field(field(panel, "selection", Object.class), "addSelection", JButton.class);
                assertTrue(add.isEnabled());
                add.doClick();

                assertTrue(fetch.isEnabled());
                assertEquals("Fetch 1 selected entity", fetch.getText());
                assertTrue(fetch.getToolTipText().contains("exactly 1 selected QID"));
                assertTrue(fetch.getToolTipText().contains("project is not changed"));
            } finally {
                panel.close();
            }
        });
    }

    @Test void classesWithoutQidMembersAreNotOffered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WikidataDynamicObject maat = entity("Q170471", "Maat", "EgyptianDeity");
            WikidataDynamicObject local = entity("local-id", "Local", "LocalNote");
            CommonsDiscoveryPanel panel = panel(domain(
                    List.of(maat, local), List.of("EgyptianDeity", "LocalNote")));
            try {
                JComboBox<?> types = field(field(panel, "selection", Object.class), "type", JComboBox.class);
                assertEquals(1, types.getItemCount());
                assertEquals("EgyptianDeity", types.getItemAt(0));
            } finally {
                panel.close();
            }
        });
    }

    private static WikidataDynamicObject entity(
            String id, String name, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, name);
        value.type(type);
        return value;
    }

    private static DomainModel domain(
            List<WikidataDynamicObject> instances, List<String> types) {
        return new DomainModel() {
            @Override public List<String> types() { return types; }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                WikidataDynamicObject sample = instances.stream()
                        .filter(value -> value.directClassNames().contains(type)).findFirst()
                        .orElse(instances.getFirst());
                return () -> objectview.field.FieldSet.of(sample).fields();
            }
            @Override public Collection<? extends Viewable> instances() {
                return instances;
            }
            @Override public Class<? extends Viewable> universe() {
                return WikidataDynamicObject.class;
            }
        };
    }

    /** Instances may share a label; a depicted-entity bucket found by its name merged
     *  their files, so it is named by QID too. */
    @Test void sameNamedEntitiesKeepSeparateDepictionGroups() throws Exception {
        WikidataDynamicObject horus = entity("Q1", "Horus", "EgyptianDeity");
        WikidataDynamicObject constellation = entity("Q1", "Horus", "Constellation");
        MediaRepresentation first = new MediaRepresentation("M1", "First",
                "https://commons.wikimedia.org/wiki/Special:FilePath/A.jpg",
                List.of(horus), List.of(), "", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        MediaRepresentation second = new MediaRepresentation("M2", "Second",
                "https://commons.wikimedia.org/wiki/Special:FilePath/B.jpg",
                List.of(constellation), List.of(), "", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        java.lang.reflect.Method groups = CommonsDiscoveryPanel.class.getDeclaredMethod(
                "groups", List.class, List.class);
        groups.setAccessible(true);
        objectview.group.ViewableGroup<?> root = (objectview.group.ViewableGroup<?>)
                groups.invoke(null, List.of(first, second), List.of(horus, constellation));

        var bySubject = root.getChildren().stream()
                .filter(child -> child.getDisplayName().equals("Selected entity"))
                .findFirst().orElseThrow();
        assertEquals(2, bySubject.getChildren().size(),
                "each instance keeps its own bucket: " + bySubject.getChildren());
        for (var bucket : bySubject.getChildren()) {
            assertEquals(1, bucket.getMembers().size(), bucket.getDisplayName());
        }
        assertTrue(bySubject.getChildren().stream().anyMatch(group ->
                group.getDisplayName().contains("EgyptianDeity")));
        assertTrue(bySubject.getChildren().stream().anyMatch(group ->
                group.getDisplayName().contains("Constellation")));
    }

    @Test void sceneGroupsExposeCoDepictedEntitiesAndQualifierValues() throws Exception {
        WikidataDynamicObject horus = entity("Q1", "Horus", "EgyptianDeity");
        DepictionQualifier color = new DepictionQualifier("S2", "P462", "color",
                "http://www.wikidata.org/entity/Q23445", "Q23445", "black");
        DepictionEvidence selected = new DepictionEvidence(
                "S1", "Q1", "Horus", true, List.of());
        DepictionEvidence falcon = new DepictionEvidence(
                "S2", "Q2", "falcon", false, List.of(color));
        MediaRepresentation media = new MediaRepresentation("M1", "Horus scene",
                "https://commons.wikimedia.org/wiki/Special:FilePath/Horus.jpg",
                List.of(horus), List.of(selected, falcon), "", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        java.lang.reflect.Method groups = CommonsDiscoveryPanel.class.getDeclaredMethod(
                "groups", List.class, List.class);
        groups.setAccessible(true);
        objectview.group.ViewableGroup<?> root = (objectview.group.ViewableGroup<?>)
                groups.invoke(null, List.of(media), List.of(horus));

        var coDepicted = root.getChildren().stream()
                .filter(child -> child.getDisplayName().equals("Co-depicted entity"))
                .findFirst().orElseThrow();
        assertEquals(List.of("falcon (Q2) — 1 file(s)"), coDepicted.getChildren().stream()
                .map(objectview.Viewable::getDisplayName).toList());
        assertEquals(1, coDepicted.getChildren().iterator().next().getMembers().size());

        var qualifiers = root.getChildren().stream()
                .filter(child -> child.getDisplayName().equals("Depiction qualifier"))
                .findFirst().orElseThrow();
        assertEquals(List.of("color: black (P462, Q23445) — 1 file(s)"),
                qualifiers.getChildren().stream()
                        .map(objectview.Viewable::getDisplayName).toList());
    }

    /** The Commons tab, built the one way it exists: inside the Discovery workbench. */
    private static CommonsDiscoveryPanel panel(DomainModel domain) {
        return new discovery.DiscoveryWorkbench(domain, null).commons();
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
