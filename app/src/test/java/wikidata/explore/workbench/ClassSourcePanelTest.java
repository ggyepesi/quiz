package wikidata.explore.workbench;

import datasource.graph.GraphExpansionPolicy;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.ClassSourceBindings;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.GeneratedProjectModel;
import datasource.api.SourceBindingSlot;

import javax.swing.JCheckBox;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        aliases.setSelected(false);
        panel.applyEdits();

        assertNull(ClassSourceBindings.binding(
                person, SourceBindingSlot.CLASS_ALIASES));
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

    private static ClassSourcePanel panelFor(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(clazz);
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        return panel;
    }
}
