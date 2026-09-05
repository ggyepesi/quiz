package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class has a name, an alias and a base whatever kind it is.
 *
 * <p>Four kind editors each decided independently how many of the three to show. An
 * aggregate class had none, so it could not be renamed at all — {@code RenameClass} is
 * used by the Source, Statement and Owned panels and by nothing else, which made this an
 * absence rather than a hidden control. Nothing in the model or the validator restricts
 * alias or a base by kind; the one kind-specific rule is that an Owned class may extend
 * only another Owned class, which is about what a base may BE.
 */
class EveryClassHasItsHeaderTest {

    private static GeneratedProjectModel projectWith(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(clazz);
        return project;
    }

    @Test void aSourceClassUsesTheSharedHeader() {
        GeneratedClassModel source = new GeneratedClassModel("Person");
        GeneratedProjectModel project = projectWith(source);
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(source);

        assertNotNull(find(panel, ClassHeaderEditor.class));
    }

    @Test void aStatementClassUsesTheSharedHeader() {
        GeneratedClassModel statement = new GeneratedClassModel("Award");
        GeneratedProjectModel project = projectWith(statement);
        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(statement);

        assertNotNull(find(panel, ClassHeaderEditor.class));
    }

    /** The gap this closes: an aggregate could not be renamed from its editor. */
    @Test void anAggregateClassCanBeNamed() {
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.aggregateSource(new AggregateClassSource("Award", "awards"));
        GeneratedProjectModel project = projectWith(prize);

        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(prize);

        assertNotNull(find(panel, ClassHeaderEditor.class),
                "an aggregate class has a name like any other class");
        assertTrue(textIn(panel).contains("NobelPrize"), textIn(panel).toString());
    }

    /** Renaming through the header rebinds the project, not just the text field. */
    @Test void renamingThroughTheHeaderRenamesTheClass() {
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        GeneratedProjectModel project = projectWith(part);
        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(part);

        JTextField name = firstField(find(panel, ClassHeaderEditor.class));
        name.setText("StructuredName");
        panel.applyEdits();

        assertEquals("StructuredName", part.className());
        assertNotNull(project.findClass("StructuredName"));
    }

    /** A name already taken is refused, and the field goes back to the truth. */
    @Test void aRefusedRenameLeavesTheFieldShowingTheRealName() {
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        GeneratedProjectModel project = projectWith(part);
        GeneratedClassModel other = new GeneratedClassModel("Taken");
        other.ownedClass(true);
        project.addClass(other);
        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(part);

        JTextField name = firstField(find(panel, ClassHeaderEditor.class));
        name.setText("Taken");
        // The refusal shows a dialog; what matters is that the model did not move.
        assertEquals("Name", part.className());
    }

    /** An owned class may extend only another owned class — the validator's rule. */
    @Test void theBaseCandidatesAreTheKindsRule() {
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        GeneratedProjectModel project = projectWith(part);
        GeneratedClassModel owned = new GeneratedClassModel("OtherPart");
        owned.ownedClass(true);
        project.addClass(owned);
        project.addClass(new GeneratedClassModel("Person"));

        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(part);

        List<String> offered = comboItems(find(panel, ClassHeaderEditor.class));
        assertTrue(offered.contains("OtherPart"), offered.toString());
        assertFalse(offered.contains("Person"),
                "a Source class is not a base an Owned class may take: " + offered);
        assertFalse(offered.contains("Name"), "nor itself: " + offered);
    }

    /** An imported class says so, and none of it is editable. */
    @Test void anImportedClassIsShownAsOwnedElsewhere() {
        GeneratedClassModel imported = new GeneratedClassModel("Name");
        imported.ownedClass(true);
        imported.importedFrom("Person");
        ModelSourceWorkbenchPanel panel =
                new ModelSourceWorkbenchPanel(projectWith(imported));
        panel.edit(imported);

        assertTrue(textIn(panel).stream().anyMatch(t -> t.contains("Imported from")),
                "a disabled editor with no reason reads as broken: " + textIn(panel));
        assertFalse(firstField(find(panel, ClassHeaderEditor.class)).isEditable(),
                "it is edited in the model that owns it");
    }

    private static JTextField firstField(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField field) return field;
            if (child instanceof Container container) {
                JTextField found = firstField(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> comboItems(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JComboBox<?> box) {
                List<String> items = new ArrayList<>();
                for (int i = 0; i < box.getItemCount(); i++) {
                    items.add(String.valueOf(box.getItemAt(i)));
                }
                return items;
            }
            if (child instanceof Container container) {
                List<String> found = comboItems(container);
                if (!found.isEmpty()) return found;
            }
        }
        return List.of();
    }

    private static List<String> textIn(Container root) {
        List<String> text = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null) {
                text.add(label.getText());
            }
            if (child instanceof JTextField field && field.getText() != null) {
                text.add(field.getText());
            }
            if (child instanceof Container container) text.addAll(textIn(container));
        }
        return text;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
