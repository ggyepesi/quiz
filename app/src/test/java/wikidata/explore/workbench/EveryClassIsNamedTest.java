package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a class's instances are named is one question, asked the same way for every kind.
 *
 * <p>It was asked four ways: whole for a source class; as a field box for a statement
 * class, which showed a template as an uneditable string because it had no control for
 * one; as field checkboxes for an aggregate, composed INTO a template and read back out
 * of one by substring; and not at all for an owned class.
 */
class EveryClassIsNamedTest {

    private static GeneratedProjectModel projectWith(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(clazz);
        return project;
    }

    private static GeneratedClassModel aggregate(String name) {
        GeneratedClassModel clazz = new GeneratedClassModel(name);
        clazz.aggregateSource(new AggregateClassSource("Award", "awards"));
        return clazz;
    }

    @Test void aSourceClassIsNamedThroughTheSharedEditor() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(projectWith(person));
        panel.edit(person);

        assertNotNull(find(panel, DisplayNameEditor.class));
    }

    @Test void aStatementClassIsNamedThroughTheSharedEditor() {
        GeneratedClassModel award = new GeneratedClassModel("Award");
        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(projectWith(award));
        panel.edit(award);

        assertNotNull(find(panel, DisplayNameEditor.class));
    }

    @Test void anAggregateClassIsNamedThroughTheSharedEditor() {
        GeneratedClassModel prize = aggregate("NobelPrize");
        AggregateClassPanel panel = new AggregateClassPanel(projectWith(prize));
        panel.edit(prize);

        assertNotNull(find(panel, DisplayNameEditor.class));
    }

    /**
     * The gap this closes. A part is an instance of its class like any other and is
     * told apart by that class; ownership says how the data is produced, not what the
     * instances are called.
     */
    @Test void anOwnedClassIsNamedThroughTheSharedEditor() {
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        OwnedClassPanel panel = new OwnedClassPanel(projectWith(part));
        panel.edit(part);

        assertNotNull(find(panel, DisplayNameEditor.class),
                "an owned class names its instances like any other class");
    }

    /**
     * The aggregate editor's checkboxes could only ever compose {@code {a} — {b}}, and
     * read a template back by asking which field names it contained — so any other
     * separator, and any word in it, was silently dropped the next time the class was
     * applied.
     */
    @Test void aTemplateSurvivesBeingShownAndAppliedUnchanged() {
        GeneratedClassModel prize = aggregate("NobelPrize");
        prize.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        prize.canonical().displayNameTemplate("Best {category}, {year}");

        AggregateClassPanel panel = new AggregateClassPanel(projectWith(prize));
        panel.edit(prize);
        panel.applyEdits();

        assertEquals("Best {category}, {year}",
                prize.canonical().displayNameTemplate());
        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                prize.canonical().displayNameMode());
    }

    /** Several fields are one name, joined in the order the list shows them. */
    @Test void severalFieldsAreOneNameInTheOrderChosen() {
        GeneratedClassModel prize = aggregate("NobelPrize");
        prize.addField("category", datasource.schema.FieldType.STRING,
                wikidata.explore.model.FieldCardinality.SINGLE);
        prize.addField("year", datasource.schema.FieldType.STRING,
                wikidata.explore.model.FieldCardinality.SINGLE);
        prize.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        prize.canonical().displayNameTemplate("{category} — {year}");

        AggregateClassPanel panel = new AggregateClassPanel(projectWith(prize));
        panel.edit(prize);
        panel.applyEdits();

        assertEquals("{category} — {year}", prize.canonical().displayNameTemplate(),
                "read as two fields and written back as the same two");
    }

    /**
     * A name is the list of fields, so a template naming none is no fields — and for a
     * kind whose instances have no name of their own, that is what the reader is told.
     *
     * <p>This used to assert that an empty TEMPLATE stayed TEMPLATE, because the mode
     * was a separate control that could be set before its value was typed. The list is
     * both: empty means "the name it already has", which an aggregate does not have.
     */
    @Test void aNameWithNoFieldsIsTheNameItAlreadyHas() {
        GeneratedClassModel prize = aggregate("NobelPrize");
        prize.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        prize.canonical().displayNameTemplate("");

        AggregateClassPanel panel = new AggregateClassPanel(projectWith(prize));
        panel.edit(prize);
        panel.applyEdits();

        assertEquals(CanonicalSpec.DisplayNameMode.LABEL,
                prize.canonical().displayNameMode());
        assertFalse(find(panel, DisplayNameEditor.class).warning().isBlank(),
                "and an aggregate has no name of its own, so it is told so");
    }

    /**
     * A template the list cannot express is shown, not rewritten.
     *
     * <p>The aggregate editor's field checkboxes composed a template and read it back by
     * substring, so "Best {category}" came back as "{category}" — a modeller's answer
     * quietly replaced by the editor's idea of it.
     */
    @Test void aTemplateWithWordsInItIsNotRewrittenByTheList() {
        GeneratedClassModel prize = aggregate("NobelPrize");
        prize.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        prize.canonical().displayNameTemplate("Best {category}, {year}");

        AggregateClassPanel panel = new AggregateClassPanel(projectWith(prize));
        panel.edit(prize);
        panel.applyEdits();

        assertEquals("Best {category}, {year}",
                prize.canonical().displayNameTemplate());
        assertTrue(find(panel, DisplayNameEditor.class).warning()
                        .contains("cannot express"),
                "and the reader is told why the list is not showing it");
    }

    /** A statement class can be named by a template here, and not only shown one. */
    @Test void aStatementTemplateIsEditableWhereItIsShown() {
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.canonical().displayNameMode(CanonicalSpec.DisplayNameMode.TEMPLATE);
        award.canonical().displayNameTemplate("{laureates} — {category}");

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(projectWith(award));
        panel.edit(award);
        panel.applyEdits();

        assertEquals("{laureates} — {category}",
                award.canonical().displayNameTemplate());
        assertEquals(CanonicalSpec.DisplayNameMode.TEMPLATE,
                award.canonical().displayNameMode());
    }

    /**
     * What LABEL resolves to differs by kind, and for an owned class it resolves: a
     * part is produced on its owner's QID, so it has no label of its own to take and is
     * given the owner and the site that produced it instead.
     */
    @Test void labelModeResolvesForTheKindsThatHaveAName() {
        assertFalse(CanonicalEditorPolicy.labelSource(ClassKind.SOURCE).isBlank());
        assertFalse(CanonicalEditorPolicy.labelSource(ClassKind.OWNED).isBlank());
        assertTrue(CanonicalEditorPolicy.labelSource(ClassKind.STATEMENT).isBlank());
        assertTrue(CanonicalEditorPolicy.labelSource(ClassKind.AGGREGATE).isBlank());
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
