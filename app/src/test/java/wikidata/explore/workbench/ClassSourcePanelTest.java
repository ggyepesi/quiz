package wikidata.explore.workbench;

import datasource.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.ClassSourceBindings;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.GeneratedProjectModel;
import datasource.api.SourceBindingSlot;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The Class editor shows the "Reify from" source class and nothing else about a
// statement source. Everything it cannot see must survive its apply, because the
// Statement editor is the single editor of those declarations.
class ClassSourcePanelTest {

    @Test void aliasesAreAnExplicitEditableClassSourceChoice() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        ClassSourcePanel panel = panelFor(person);
        panel.edit(person);

        JCheckBox aliases = checkBox(panel, "Add aliases (Also known as)");
        assertNotNull(aliases, "the automatic datasource field must be visible in config");
        assertFalse(aliases.isSelected(),
                "a new class must not silently opt into an optional field");
        panel.applyEdits();

        ClassSourceBindings.synchronize(person);

        assertNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_ALIASES));
    }

    @Test void applyingAPopulationNeverDeclaresInstanceFields() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(wikidata.explore.model.EntityBound.relation(
                "P279", List.of("Q12097"), false));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        panel.applyEdits();

        assertTrue(position.fields().isEmpty(),
                "P31/type and P279/target are field choices, not population side effects");
    }

    @Test void applyingExplicitSubjectQidsRefreshesTheReadinessIndicator() throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);
        find(panel, JTextArea.class).setText("Q4164871");

        panel.applyEdits();

        assertTrue(labels(panel).stream().anyMatch(text -> text.contains(
                "Ready — 1 explicit QID")), labels(panel).toString());
        render(panel, "target/ui-artifacts/class-source-ready-after-apply.png");
    }

    @Test void aPropertyWithoutAnObjectIsVisiblyAnUnappliedDraft() throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);
        TripleEditor triple = find(panel, TripleEditor.class);
        triple.propertyPid("P279", "subclass of");

        panel.applyEdits();

        assertFalse(position.membership().bounded());
        assertEquals("P279", triple.propertyPid(), "the draft stays available to finish");
        assertTrue(labels(panel).stream().anyMatch(text -> text.contains(
                "property P279 was not saved — add an object")), labels(panel).toString());
        render(panel, "target/ui-artifacts/class-source-unapplied-property.png");
    }

    @Test void descendantMembershipIsAVisibleSourceConfigurationChoice()
            throws IOException {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(wikidata.explore.model.EntityBound.relation(
                "P31", List.of("Q4164871"), true));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        JCheckBox descendants = visibleCheckBox(
                panel, "include subclasses of these QIDs (P279*)");
        assertNotNull(descendants);
        assertTrue(descendants.isVisible());
        assertTrue(descendants.isSelected());

        render(panel, "target/ui-artifacts/class-source-descendant-membership.png");
    }

    @Test void applyingTwiceKeepsTheSubclassClosureTheReaderChose() {
        // Apply re-shows the object QIDs it has just written, and an explicit bound
        // has nowhere to carry the closure — so the redisplay cleared the checkbox
        // and the next Apply stored the cleared value. A saved position model came
        // back with includeDescendants false for a class configured with it on.
        GeneratedClassModel position = new GeneratedClassModel("PositionKind");
        position.membership(wikidata.explore.model.EntityBound.relation(
                "P279", List.of("Q4164871"), true));
        ClassSourcePanel panel = panelFor(position);
        panel.edit(position);

        panel.applyEdits();
        assertTrue(position.membership().includeDescendants(),
                "the first apply stores what the reader configured");
        assertTrue(visibleCheckBox(panel,
                        "include subclasses of these QIDs (P279*)").isSelected(),
                "and leaves the control showing it");

        panel.applyEdits();
        assertTrue(position.membership().includeDescendants(),
                "a second apply must not store a choice nobody made");
        assertEquals(List.of("Q4164871"), position.membership().qids());
        assertEquals("P279", position.membership().relationPid());
    }

    @Test void theInheritedPopulationFilterExistsOnlyWhenAClassExtendsAnother() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        ClassSourcePanel rootPanel = panelFor(position);
        rootPanel.edit(position);
        assertNull(visibleLabel(rootPanel, "Inherited population filter:"),
                "a root class has no inherited population to filter");

        GeneratedClassModel king = new GeneratedClassModel("King");
        king.baseClassName("Position");
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(position);
        project.addClass(king);
        ClassSourcePanel subclassPanel = new ClassSourcePanel();
        subclassPanel.setProjectModel(project);
        subclassPanel.edit(king);

        assertNotNull(visibleLabel(subclassPanel, "Inherited population filter:"));
    }

    @Test void applyEditsKeepsDeclarationsThisEditorCannotSee() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("Person", "P39");
        source.valueSelectionName("Positions");
        source.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        holding.statementSource(source);

        ClassSourcePanel panel = panelFor(holding);
        panel.edit(holding);
        panel.applyEdits();

        assertNotNull(holding.statementSource());
        assertEquals("Positions", holding.statementSource().valueSelectionName(),
                "the value Selection is not editable here and must survive");
        assertEquals(GraphExpansionPolicy.CURATED,
                holding.statementSource().graphExpansionPolicy(),
                "the graph policy is not editable here and must survive");
    }

    // Regression: every statement class in the shipped models (History OfficeHolding,
    // Oscars Nomination) discovers its subjects from the property and so has a BLANK
    // source class. Applying the Class editor to one must not null its statement
    // source — a set property alone is enough to make it a statement class.
    @Test void applyEditsKeepsASourceClasslessStatementClass() {
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        StatementClassSource source = new StatementClassSource("P39");
        source.graphExpansionPolicy(GraphExpansionPolicy.CURATED);
        holding.statementSource(source);

        ClassSourcePanel panel = panelFor(holding);
        panel.edit(holding);
        panel.applyEdits();

        assertNotNull(holding.statementSource(),
                "a discovered-subject statement class must survive the Class editor");
        assertEquals("P39", holding.statementSource().propertyPid());
        assertEquals(GraphExpansionPolicy.CURATED,
                holding.statementSource().graphExpansionPolicy());
    }

    private static JCheckBox checkBox(Component component, String text) {
        if (component instanceof JCheckBox box && text.equals(box.getText())) return box;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JCheckBox found = checkBox(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JCheckBox visibleCheckBox(Component component, String text) {
        if (component instanceof JCheckBox box && box.isVisible()
                && text.equals(box.getText())) return box;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JCheckBox found = visibleCheckBox(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel visibleLabel(Component component, String text) {
        if (component instanceof JLabel label && label.isVisible()
                && text.equals(label.getText())) return label;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = visibleLabel(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findOrNull(child, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("No " + type.getSimpleName());
    }

    private static <T extends Component> T findOrNull(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = findOrNull(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> labels(Component component) {
        List<String> labels = new java.util.ArrayList<>();
        collectLabels(component, labels);
        return labels;
    }

    private static void collectLabels(Component component, List<String> labels) {
        if (component instanceof JLabel label && label.getText() != null) {
            labels.add(label.getText().replaceAll("<[^>]+>", ""));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectLabels(child, labels);
        }
    }

    private static ClassSourcePanel panelFor(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(clazz);
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        return panel;
    }

    private static void render(Component component, String path) throws IOException {
        component.setSize(900, Math.max(720, component.getPreferredSize().height));
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
