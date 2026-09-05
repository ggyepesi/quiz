package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledCanonical;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JComboBox;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What becomes of a candidate whose key cannot be computed is one question.
 *
 * <p>It had two enums with opposite defaults — {@code AggregateClassSource.EXCLUDE} and
 * {@code canonical.INCOMPLETE_GROUP} — and a translation between them in the transform
 * layer. Nobel's NobelPrize carried both at once and answered them differently. The one
 * on the canonical spec, beside the key it is about, is the one that survives; the only
 * editor for the other was the aggregate panel, so every other class held an answer
 * nothing could reach.
 */
class OneAnswerForAMissingKeyTest {

    @Test void anAggregateRecipeHasNoPolicyOfItsOwn() {
        for (var field : AggregateClassSource.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase().contains("missingkey"),
                    "the key's policy belongs to the key: " + field.getName());
        }
        for (var nested : AggregateClassSource.class.getDeclaredClasses()) {
            assertNotEquals("MissingKeyPolicy", nested.getSimpleName(),
                    "a second enum for one question");
        }
    }

    /** The compiled form used to drop it, so a round trip forgot what was authored. */
    @Test void compilingASpecKeepsItsPolicy() {
        CanonicalSpec spec = new CanonicalSpec();
        spec.missingKeyPolicy(canonical.MissingKeyPolicy.REJECT_CANDIDATE);

        assertEquals(canonical.MissingKeyPolicy.REJECT_CANDIDATE,
                CompiledCanonical.from(spec).missingKeyPolicy());
        assertEquals(canonical.MissingKeyPolicy.REJECT_CANDIDATE,
                CompiledCanonical.from(spec).toSpec().missingKeyPolicy());
    }

    /** Beside the key, in every kind's editor, and in words. */
    @Test void everyClassCanBeAskedItWhereTheKeyIsEdited() {
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.aggregateSource(new AggregateClassSource("Award", "awards"));
        prize.canonical().missingKeyPolicy(canonical.MissingKeyPolicy.REJECT_CANDIDATE);
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(prize);

        AggregateClassPanel panel = new AggregateClassPanel(project);
        panel.edit(prize);

        ClassIdentityEditor identity = find(panel, ClassIdentityEditor.class);
        assertNotNull(identity);
        List<JComboBox<?>> combos = new ArrayList<>();
        collect(identity, JComboBox.class, combos);
        assertTrue(combos.stream().anyMatch(combo ->
                        combo.getSelectedItem()
                                == canonical.MissingKeyPolicy.REJECT_CANDIDATE),
                "the class's own answer is what the control shows");
        assertTrue(combos.stream().noneMatch(combo ->
                        String.valueOf(combo.getSelectedItem()).contains("_")),
                "and it reads as what it does, not as the name of a constant");
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
