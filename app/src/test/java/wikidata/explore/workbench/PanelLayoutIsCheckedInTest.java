package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.RuleDirection;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the four class editors put on screen, in order, checked in.
 *
 * <p>A change to a panel is a diff of {@code docs/panel-layout.txt} before it is
 * anything else. Nothing else in this suite can see the properties these editors are
 * designed around — which rows come first, whether a control is offered twice, whether
 * four kinds are laid out the same way — so a change to them used to be reported as done
 * on the strength of a green suite that could not perceive it. The panels drifted for a
 * day that way: the triple ended up seventh on one editor and last on another while
 * every test passed.
 *
 * <p>This is to the editors what {@code counts.tsv} is to generation: the artifact that
 * can see the change. Regenerate it by running with {@code -Dpanel.layout.write=true}
 * and READ the diff — the file is the agreement about what these panels look like.
 */
class PanelLayoutIsCheckedInTest {

    private static final Path GOLDEN = Path.of("../docs/panel-layout.txt");

    @Test void theEditorsLookLikeTheCheckedInLayout() throws IOException {
        GeneratedProjectModel project = project();
        StringBuilder actual = new StringBuilder();

        ClassSourcePanel source = new ClassSourcePanel();
        source.setProjectModel(project);
        source.edit(project.findClass("Person"));
        describe(actual, "Source class - ClassSourcePanel", source);

        StatementSourcePanel statement = new StatementSourcePanel();
        statement.setProjectModel(project);
        statement.edit(project.findClass("Award"));
        describe(actual, "Statement class - StatementSourcePanel", statement);

        OwnedClassPanel owned = new OwnedClassPanel(project);
        owned.edit(project.findClass("Name"));
        describe(actual, "Owned class - OwnedClassPanel", owned);

        AggregateClassPanel aggregate = new AggregateClassPanel(project);
        aggregate.edit(project.findClass("Prize"));
        describe(actual, "Aggregate class - AggregateClassPanel", aggregate);

        FieldSourcePanel field = new FieldSourcePanel();
        field.setProjectModel(project);
        field.edit(project.findClass("Person").fields().get(0));
        describe(actual, "Field - FieldSourcePanel", field);

        GeneratedProjectModel graphProject = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedFieldModel jurisdiction = position.addField(
                "jurisdiction", FieldType.ENTITY, FieldCardinality.COLLECTION);
        jurisdiction.mapping().propertyPid("P1001");
        jurisdiction.mapping().propertyLabel("jurisdiction");
        jurisdiction.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        graphProject.rootClass(position);
        GraphConstraintsPanel graph = new GraphConstraintsPanel(graphProject);
        graph.refresh();
        describe(actual, "Graph constraints - GraphConstraintsPanel", graph);

        Path golden = Files.isRegularFile(GOLDEN) ? GOLDEN : Path.of("docs/panel-layout.txt");
        if (Boolean.getBoolean("panel.layout.write")) {
            Files.writeString(golden, actual.toString());
        }
        String checkedIn = Files.readString(golden);
        if (!checkedIn.equals(actual.toString())) {
            // The diff is the point, and an assertion message is not where anyone can
            // read a 300-line one.
            Path seen = golden.resolveSibling("panel-layout.actual.txt");
            Files.writeString(seen, actual.toString());
            assertEquals(checkedIn, actual.toString(),
                    "the editors no longer look like " + golden + "; diff it against "
                            + seen + ", then re-run with -Dpanel.layout.write=true to "
                            + "accept the new layout");
        }
    }

    // --- the walk ---

    private static void describe(StringBuilder out, String title, Container panel) {
        out.append("== ").append(title).append(" ==\n");
        walk(out, panel, 1);
        out.append('\n');
    }

    private static void walk(StringBuilder out, Container container, int depth) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) continue;
            String line = describe(child);
            int next = depth;
            if (line != null) {
                out.append("  ".repeat(depth)).append(line).append('\n');
                next = depth + 1;
            }
            if (child instanceof Container nested) walk(out, nested, next);
        }
    }

    /** The assembled pieces, named — the thing the four editors are made OF. */
    private static final List<String> PIECES = List.of(
            "ClassHeaderEditor", "TripleEditor", "EntityEndEditor",
            "ClassIdentityEditor", "DisplayNameEditor", "OrderedChoiceList");

    private static String describe(Component component) {
        String piece = component.getClass().getSimpleName();
        if (PIECES.contains(piece)) {
            String box = titledBorder(component);
            return "piece: " + piece + (box == null ? "" : "  [" + box + "]");
        }
        String box = titledBorder(component);
        if (box != null) return "box: " + box;
        if (component instanceof JLabel label) {
            String text = plain(label.getText());
            return text.isBlank() ? null : "label: " + text;
        }
        if (component instanceof AbstractButton button) {
            // A combo's own arrow is not a control anyone configures.
            String text = plain(button.getText());
            if (text.isBlank()) return null;
            return (button.getClass().getSimpleName().equals("JCheckBox")
                    ? "check: " : "button: ") + text
                    + (button.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JComboBox<?> combo) {
            return "combo: " + combo.getItemCount() + " item(s)"
                    + (combo.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JTextField field) {
            return "text: [" + plain(field.getText()) + "]"
                    + (field.isEnabled() ? "" : "  (disabled)");
        }
        if (component instanceof JTextArea) return "textarea";
        if (component instanceof JList<?>) return "list";
        return null;
    }

    private static String titledBorder(Component component) {
        if (!(component instanceof javax.swing.JComponent widget)) return null;
        if (widget.getBorder() instanceof TitledBorder titled) {
            String title = plain(titled.getTitle());
            return title.isBlank() ? null : title;
        }
        return null;
    }

    /** Swing labels carry HTML; the layout is about the words, not the markup. */
    private static String plain(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]*>", " ").replaceAll("&mdash;", "-")
                .replaceAll("&rarr;", "->").replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    // --- one class of every kind ---

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();

        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        GeneratedFieldModel birthDate = person.addField(
                "birthDate", FieldType.DATE, FieldCardinality.SINGLE);
        birthDate.mapping().propertyPid("P569");
        birthDate.mapping().propertyLabel("date of birth");
        birthDate.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        project.addClass(person);

        GeneratedClassModel award = new GeneratedClassModel("Award");
        StatementClassSource statement = new StatementClassSource("Person", "P166");
        statement.objectBound(EntityBound.explicit(List.of("Q35637")));
        award.statementSource(statement);
        award.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_OBJECT);
        project.addClass(award);

        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        name.addField("given", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(name);
        GeneratedFieldModel site = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel prize = new GeneratedClassModel("Prize");
        prize.aggregateSource(new AggregateClassSource("Award", "awards"));
        prize.addField("awards", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Award");
        project.addClass(prize);

        project.rootClass(person);
        return project;
    }
}
