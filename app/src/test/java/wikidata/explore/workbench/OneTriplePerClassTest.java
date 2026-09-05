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

        List<String> text = labels(find(panel, TripleEditor.class));
        assertTrue(text.stream().anyMatch(t -> t.contains("Person.structuredName")),
                "the site is the property: " + text);
        assertTrue(text.stream().anyMatch(t -> t.contains("Subject:")), text.toString());
        assertTrue(text.stream().anyMatch(t -> t.contains("Object:")), text.toString());
    }

    /** The property row by name: both ends hold text fields of their own. */
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
