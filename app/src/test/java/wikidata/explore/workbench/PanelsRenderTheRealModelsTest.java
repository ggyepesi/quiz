package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import javax.swing.JComboBox;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every editing panel opens on the real shipped models and shows what they say.
 *
 * <p>The engine's correctness has been checked against 30,000 instances; none of that
 * says whether the panel a modeller uses can be opened. This builds each construct's
 * editor on a class from a shipped model and reads back what is on screen — which is
 * what caught a reducer combo displaying UNION_DISTINCT, two nested boxes both titled
 * for identity, and a control that had stopped affecting anything while still looking
 * live.
 */
class PanelsRenderTheRealModelsTest {

    private static GeneratedProjectModel model(String domain) throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
    }

    private static Container editorFor(GeneratedProjectModel project,
                                       GeneratedClassModel clazz) {
        if (clazz.reifiesStatements()) {
            StatementSourcePanel panel = new StatementSourcePanel();
            panel.setProjectModel(project);
            panel.edit(clazz);
            return panel;
        }
        if (clazz.classKind() == ClassKind.AGGREGATE) {
            AggregateClassPanel panel = new AggregateClassPanel(project);
            panel.edit(clazz);
            return panel;
        }
        ClassSourcePanel panel = new ClassSourcePanel();
        panel.setProjectModel(project);
        panel.edit(clazz);
        return panel;
    }

    /** Nothing throws, and the identity editor is there, for every construct. */
    @Test void everyConstructOpensOnAShippedClass() throws Exception {
        for (String domain : List.of("history", "nobelprizes", "oscarnominations")) {
            GeneratedProjectModel project = model(domain);
            for (GeneratedClassModel clazz : project.classes()) {
                Container editor = editorFor(project, clazz);
                assertTrue(find(editor, ClassIdentityEditor.class) != null,
                        domain + "/" + clazz.className() + " has no identity editor");
            }
        }
    }

    /** The key on screen is the key in the model, in the model's order. */
    @Test void theKeyOnScreenIsTheOneTheModelHolds() throws Exception {
        GeneratedProjectModel project = model("nobelprizes");
        GeneratedClassModel award = project.findClass("LaureatesWithMotivation");

        JList<?> keyList = find(editorFor(project, award), JList.class);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < keyList.getModel().getSize(); i++) {
            shown.add(String.valueOf(keyList.getModel().getElementAt(i)));
        }
        assertEquals(award.canonical().keyFields(), shown);
    }

    /**
     * A reducer reads as what it does. A combo showing UNION_DISTINCT shows the name of
     * a constant, which is not the same as showing what will happen.
     */
    @Test void aReducerIsOfferedInWords() throws Exception {
        GeneratedProjectModel project = model("nobelprizes");
        List<JComboBox<?>> combos = new ArrayList<>();
        collect(editorFor(project, project.findClass("LaureatesWithMotivation")),
                JComboBox.class, combos);

        assertTrue(combos.stream().anyMatch(combo ->
                        "Combine them".equals(String.valueOf(combo.getSelectedItem()))),
                "laureates unions, and the control says so in words");
        assertTrue(combos.stream().noneMatch(combo ->
                        String.valueOf(combo.getSelectedItem()).contains("_")),
                "no enum constant reaches the screen");
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
