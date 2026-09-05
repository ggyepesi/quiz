package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import javax.swing.JLabel;
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
        assertTrue(text.stream().anyMatch(t -> t.contains("Person")),
                "and the owner is the object: " + text);
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
        triple.objectQids(List.of("Q523", "Q6243"), null);
        panel.applyEdits();

        assertEquals(EntityBound.relation("P31", List.of("Q523", "Q6243"), false),
                star.membership());
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
            if (type.isInstance(child)) into.add((T) child);
            if (child instanceof Container container) collect(container, type, into);
        }
    }
}
