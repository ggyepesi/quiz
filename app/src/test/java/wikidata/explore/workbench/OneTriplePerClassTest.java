package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source, Statement and Owned classes all describe a triple; only which of its tags are
 * authored differs. They were spelled three ways — {@code subjectBound}/{@code
 * propertyPid}/{@code objectBound} on a statement, {@code propertyPid}/{@code
 * sourceQid}/{@code additionalTypeQids} on a source, and on an owned class not spelled at
 * all, which showed a line of producing fields instead.
 */
class OneTriplePerClassTest {

    /**
     * The subject's POPULATION was outside the box named after the triple. Naming the
     * class whose members are the subjects is a way of bounding the subject end.
     */
    @Test void theStatementTripleHoldsAllThreeTagsAndTheSubjectPopulation() {
        GeneratedClassModel award = new GeneratedClassModel("Award");
        StatementClassSource source = new StatementClassSource("Person", "P166");
        source.objectBound(EntityBound.explicit(List.of("Q35637")));
        award.statementSource(source);
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(award);
        project.addClass(new GeneratedClassModel("Person"));

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(award);

        TripleEditor triple = find(panel, TripleEditor.class);
        assertNotNull(triple, "the triple is one component");
        assertEquals("P166", triple.propertyPid());
        assertEquals("Person", triple.subjectPopulation(),
                "the subject's population is part of the subject end, not a row beside it");
        assertEquals(EntityBound.Kind.EXPLICIT, triple.objectBound().kind());
    }

    /** What the reader edits in the box is what applyEdits writes. */
    @Test void editingTheTripleWritesIt() {
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.statementSource(new StatementClassSource("", "P166"));
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(award);
        project.addClass(new GeneratedClassModel("Person"));

        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(award);

        property(find(panel, TripleEditor.class)).setText("P39");
        panel.applyEdits();

        assertEquals("P39", award.statementSource().propertyPid());
    }

    /**
     * An owned class is SHOWN its triples: its property and object are settled by which
     * field, on which class, declares the ownership, so they are authored there.
     */
    @Test void anOwnedClassReadsItsTripleInTheSameWords() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        project.addClass(part);
        project.addClass(person);

        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(part);

        TripleEditor triple = find(panel, TripleEditor.class);
        assertNotNull(triple);
        JTextField shown = property(triple);
        assertEquals("Person.structuredName", shown.getText(),
                "the production site IS the property of this triple");
        assertFalse(shown.isEnabled(), "and it is given, so it is not editable here");
        List<String> text = labels(triple);
        assertTrue(text.stream().anyMatch(t -> t.contains("authored on Person")),
                "the rows say where they come from: " + text);
        assertTrue(text.stream().noneMatch(t -> t.contains("Modelled as")
                        || t.contains("Goes into field")),
                "field structure belongs to the field editor: " + text);
    }

    /**
     * One component, not several behind one name.
     *
     * <p>It was a CardLayout over three cards — authored, membership, produced — with
     * the property field declared twice and the object end three times, a different
     * border title per kind, and a public entry point per kind so that every panel had
     * to know which one it was. Everything about that compiled and passed.
     */
    @Test void theTripleIsOneComponentWithOneControlPerElement() {
        long ends = java.util.Arrays.stream(TripleEditor.class.getDeclaredFields())
                .filter(field -> field.getType() == EntityEndEditor.class)
                .count();
        assertEquals(2, ends, "one subject end and one object end, no more");

        long propertyFields = java.util.Arrays.stream(
                        TripleEditor.class.getDeclaredFields())
                .filter(field -> field.getType() == JTextField.class)
                .count();
        assertEquals(1, propertyFields, "one property row");

        assertFalse(new TripleEditor().getLayout() instanceof java.awt.CardLayout,
                "a card per caller is a component per caller");

        assertEquals(1, TripleEditor.class.getDeclaredConstructors().length);
        assertEquals(0, TripleEditor.class.getDeclaredConstructors()[0]
                        .getParameterCount(),
                "one title and one border for every kind, so no panel can choose one");
    }

    @Test void subjectPropertyAndObjectAreVerticalSectionsOfTheSharedTriple() {
        TripleEditor triple = new TripleEditor();
        assertTrue(triple.getLayout() instanceof java.awt.GridBagLayout);
        assertTrue(java.util.Arrays.stream(triple.getComponents())
                        .filter(JPanel.class::isInstance)
                        .map(JPanel.class::cast)
                        .noneMatch(component -> component.getLayout()
                                instanceof java.awt.GridLayout grid
                                && grid.getColumns() == 3),
                "the relation reads vertically as Subject, Property, Object");
    }

    @Test void aSourceTripleShowsOnlyItsPopulationQuestion() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        project.addClass(position);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(position);
        List<String> text = labels(find(panel, TripleEditor.class));

        assertTrue(text.stream().anyMatch(value -> value.contains(
                "Instances produced:") && value.contains("Position")));
        assertTrue(text.contains("Restrict to these subject QIDs:"));
        assertEquals(1, text.stream().filter("Entities allowed:"::equals).count(),
                "only the object's constraint is shown; the Source subject is the output");
        assertFalse(text.contains("Modelled as:"));
        assertFalse(text.contains("Goes into field:"));
    }

    /** Every kind is shown and asked the same way. */
    @Test void everyPanelUsesTheSameTwoCalls() {
        List<String> shows = new ArrayList<>();
        for (Class<?> panel : List.of(ClassSourcePanel.class, StatementSourcePanel.class,
                OwnedClassPanel.class)) {
            assertTrue(java.util.Arrays.stream(panel.getDeclaredFields())
                            .anyMatch(field -> field.getType() == TripleEditor.class),
                    panel.getSimpleName() + " holds the shared triple");
            shows.add(panel.getSimpleName());
        }
        assertEquals(List.of("ClassSourcePanel", "StatementSourcePanel",
                "OwnedClassPanel"), shows);
    }

    @Test void theSharedTripleStatesWhetherItsInstanceConfigurationIsReady() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        project.addClass(position);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(position);
        TripleEditor triple = find(panel, TripleEditor.class);

        assertTrue(labels(triple).stream().anyMatch(text -> text.contains(
                        "Incomplete — add explicit QIDs, or choose both a property and object")),
                "an empty source configuration says exactly what completes it");

        position.seedQids().add("Q11696");
        triple.show(position, project);
        assertTrue(labels(triple).stream().anyMatch(text -> text.contains(
                        "Ready — 1 explicit QID")),
                "an explicit population is visibly ready");

        position.membership(EntityBound.relation("P279", List.of("Q4164871"), false));
        triple.show(position, project);
        assertTrue(labels(triple).stream().anyMatch(text -> text.contains(
                        "Ready — property + object, restricted to 1 explicit QID")),
                "the same indicator explains the combined semantics");
    }

    @Test void theSameIndicatorUsesTheStatementTripleRule() {
        GeneratedClassModel holding = new GeneratedClassModel("Holding");
        holding.classKind(ClassKind.STATEMENT);
        holding.statementSource(new StatementClassSource("Position", ""));
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(holding);

        TripleEditor triple = new TripleEditor();
        triple.show(holding, project);
        assertTrue(labels(triple).stream().anyMatch(text -> text.contains(
                "Incomplete — choose a statement property")));

        holding.statementSource().propertyPid("P39");
        triple.show(holding, project);
        assertTrue(labels(triple).stream().anyMatch(text -> text.contains(
                "Ready — statement property P39")));
    }

    /**
     * Every kind assembles the same pieces in the same order.
     *
     * <p>Four editors used to lay them out four ways — the triple third on one, last on
     * another, absent from a fourth — so learning one taught you nothing about the next.
     * The order is the triple, then what identifies an instance, then what names it;
     * what only one kind has comes after. An aggregate has no triple, which is the one
     * gap, and it is a fact about the kind rather than a layout choice.
     */
    @Test void everyKindAssemblesTheSamePiecesInTheSameOrder() {
        GeneratedProjectModel project = project();

        assertEquals(List.of("ClassHeaderEditor", "TripleEditor",
                        "ClassIdentityEditor", "DisplayNameEditor"),
                pieces(sourcePanel(project)));
        assertEquals(List.of("ClassHeaderEditor", "TripleEditor",
                        "ClassIdentityEditor", "DisplayNameEditor"),
                pieces(statementPanel(project)));
        assertEquals(List.of("ClassHeaderEditor", "TripleEditor",
                        "ClassIdentityEditor", "DisplayNameEditor"),
                pieces(ownedPanel(project)));
        assertEquals(List.of("ClassHeaderEditor",
                        "ClassIdentityEditor", "DisplayNameEditor"),
                pieces(aggregatePanel(project)));
    }

    /** The top-level pieces a panel is assembled from, in the order they appear. */
    private static List<String> pieces(Container panel) {
        List<String> named = new ArrayList<>();
        collectPieces(panel, named);
        return named;
    }

    private static void collectPieces(Container container, List<String> into) {
        for (Component child : container.getComponents()) {
            String name = child.getClass().getSimpleName();
            if (List.of("ClassHeaderEditor", "TripleEditor", "ClassIdentityEditor",
                    "DisplayNameEditor").contains(name)) {
                into.add(name);
                continue;   // a piece's own contents are its business
            }
            if (child instanceof Container nested) collectPieces(nested, into);
        }
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.membership(EntityBound.relation("P31", List.of("Q5"), false));
        project.addClass(person);
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.statementSource(new StatementClassSource("Person", "P166"));
        project.addClass(award);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        project.addClass(name);
        GeneratedClassModel prize = new GeneratedClassModel("Prize");
        prize.aggregateSource(new wikidata.explore.model.AggregateClassSource(
                "Award", "awards"));
        project.addClass(prize);
        return project;
    }

    private static Container sourcePanel(GeneratedProjectModel project) {
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(project.findClass("Person"));
        return panel;
    }

    private static Container statementPanel(GeneratedProjectModel project) {
        StatementSourcePanel panel = new StatementSourcePanel();
        panel.setProjectModel(project);
        panel.edit(project.findClass("Award"));
        return panel;
    }

    private static Container ownedPanel(GeneratedProjectModel project) {
        OwnedClassPanel panel = new OwnedClassPanel(project);
        panel.edit(project.findClass("Name"));
        return panel;
    }

    private static Container aggregatePanel(GeneratedProjectModel project) {
        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(project.findClass("Prize"));
        return panel;
    }

    /** The property row by name: both ends hold text fields of their own. */
    /**
     * A source class occupies one END of its triple: its members are the subject, and
     * it authors the property and the objects.
     *
     * <p>Those were three controls — "Relation property", "Wikidata type/class" and
     * "Also include types" — over one ordered list, and the second box was labelled by
     * asking whether the property was P31, so the same list read as a type plus extras
     * or as a relation target plus extras depending on a literal.
     */
    @Test void aSourceClassAuthorsItsTripleInTheSameComponent() {
        GeneratedClassModel constellation = new GeneratedClassModel("Constellation");
        constellation.membership(EntityBound.relation(
                "P31", List.of("Q8928", "Q1053464"), false));
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(constellation);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(constellation);

        TripleEditor triple = find(panel, TripleEditor.class);
        assertNotNull(triple, "a source class describes a triple like the others");
        assertEquals("P31", triple.propertyPid());
        assertEquals(List.of("Q8928", "Q1053464"), triple.objectQids(),
                "one row, one list — not a leading type and a set of extras");
    }

    /** What the reader typed comes back as it was typed, order included. */
    @Test void editingTheObjectsRowWritesTheMembership() {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.relation("P31", List.of("Q523"), false));
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(star);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(star);
        TripleEditor triple = find(panel, TripleEditor.class);
        triple.objectQids(List.of("Q523", "Q6243"));
        panel.applyEdits();

        assertEquals(EntityBound.relation("P31", List.of("Q523", "Q6243"), false),
                star.membership());
    }

    @Test void sourceObjectOffersAndStoresSubclassMembership() {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), false));
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(position);

        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(position);
        TripleEditor triple = find(panel, TripleEditor.class);
        triple.includeMembershipDescendants(true);
        panel.applyEdits();

        assertTrue(position.membership().includeDescendants());
    }

    private static JTextField property(TripleEditor triple) {
        try {
            java.lang.reflect.Field field =
                    TripleEditor.class.getDeclaredField("property");
            field.setAccessible(true);
            return (JTextField) field.get(triple);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<String> labels(Container root) {
        List<JLabel> found = new ArrayList<>();
        collect(root, JLabel.class, found);
        return found.stream().map(JLabel::getText).filter(t -> t != null).toList();
    }

    private static <T> T find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found.isEmpty() ? null : found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(Container root, Class<?> type, List<T> into) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (type.isInstance(child)) into.add((T) child);
            if (child instanceof Container container) collect(container, type, into);
        }
    }
}
