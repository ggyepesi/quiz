package wikidata.explore.workbench;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelValidator;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An aggregate's fields are not configured on the aggregate: they are configured by the
 * class it groups.
 *
 * <p>A key field takes its values from the source field named in the grouped-from pair,
 * so it holds that field's type and target class and groups ONE value; the members field
 * receives the grouped records themselves. Nothing is fetched for any of them. The field
 * editor offered the full acquisition and shape controls anyway, so a field that cannot
 * be configured looked like one that had not been configured yet — and every one of
 * those choices was a way to make the class invalid from an editor that cannot see the
 * recipe.
 */
class AnAggregateFieldIsShapedByItsRecipeTest {

    private static GeneratedProjectModel nobel() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Category");
        project.addClass(award);
        project.addClass(new GeneratedClassModel("Category"));

        GeneratedClassModel prize = new GeneratedClassModel("Prize");
        AggregateClassSource recipe = new AggregateClassSource("Award", "awards");
        recipe.keys().add(new AggregateClassSource.Key("category", "category"));
        prize.aggregateSource(recipe);
        prize.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Category");
        prize.addField("awards", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Award");
        prize.addField("note", FieldType.STRING, FieldCardinality.SINGLE);
        project.addClass(prize);
        project.rootClass(prize);
        return project;
    }

    private static FieldSourcePanel editing(GeneratedProjectModel project, String field) {
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(project.findClass("Prize").fields().stream()
                .filter(candidate -> candidate.name().equals(field))
                .findFirst().orElseThrow());
        return panel;
    }

    @Test void aGroupedFieldSaysWhereItsValuesComeFrom() {
        List<String> text = labels(editing(nobel(), "category"));

        assertTrue(text.stream().anyMatch(line -> line.contains("grouped from")
                        && line.contains("Award.category")),
                "the source field is named: " + text);
    }

    @Test void theMembersFieldSaysItReceivesTheRecords() {
        List<String> text = labels(editing(nobel(), "awards"));

        assertTrue(text.stream().anyMatch(line -> line.contains("grouped")
                        && line.contains("records")),
                "the members field says what it holds: " + text);
    }

    /** The case that used to look identical to a configured one. */
    @Test void aFieldOutsideTheRecipeSaysNothingFillsIt() {
        List<String> text = labels(editing(nobel(), "note"));

        assertTrue(text.stream().anyMatch(line -> line.contains("Nothing fills")),
                "a field in neither half of the recipe is filled by nothing: " + text);
    }

    @Test void whatTheRecipeShapesIsNotOfferedAsAChoice() {
        FieldSourcePanel panel = editing(nobel(), "category");

        for (JComboBox<?> combo : combos(panel)) {
            Object selected = combo.getSelectedItem();
            if (selected instanceof FieldType || selected instanceof FieldCardinality) {
                assertFalse(combo.isEnabled(),
                        "a grouped field's shape is the source field's: " + selected);
            }
        }
    }

    /**
     * And the model refuses the divergence the editor used to allow. The pair chooser
     * offers only matching types, which is not a guarantee once either side can be
     * edited afterwards — an offer standing in for a rule.
     */
    @Test void aGroupedFieldThatCannotHoldItsSourcesValuesIsRefused() {
        GeneratedProjectModel project = nobel();
        project.findClass("Prize").fields().stream()
                .filter(field -> field.name().equals("category"))
                .forEach(field -> field.type(FieldType.STRING));

        var result = GeneratedProjectModelValidator.validate(project);

        assertTrue(result.errors().stream().anyMatch(problem ->
                        problem.message().contains("takes its values from")),
                result.format());
    }

    private static List<String> labels(Container root) {
        List<String> text = new ArrayList<>();
        collect(root, JLabel.class, text, label -> ((JLabel) label).getText());
        return text;
    }

    private static List<JComboBox<?>> combos(Container root) {
        List<JComboBox<?>> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo) found.add(combo);
            if (child instanceof Container nested) found.addAll(combos(nested));
        }
        return found;
    }

    private static void collect(Container root, Class<?> type, List<String> into,
            java.util.function.Function<Component, String> text) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                String value = text.apply(child);
                if (value != null && !value.isBlank()) into.add(value);
            }
            if (child instanceof Container nested) collect(nested, type, into, text);
        }
    }
}
