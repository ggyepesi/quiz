package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SingleRootClassModelPanelTest {

    private static GeneratedProjectModel modelWithEdition() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE);
        p.addClass(nom);
        p.rootClass(nom);
        return p;
    }

    // Regression: after copyContentsFrom (a load/reload) swaps in FRESH model
    // objects, refresh() must re-bind the selection to the LIVE new field — not
    // silently fail on the old identity and leave the editor on an orphan (whose
    // edits are then lost on save). This is the persistence bug behind edition's
    // Subject-default/Required not sticking.
    @Test void refreshRebindsSelectionToLiveObjectAfterModelSwap() {
        GeneratedProjectModel project = modelWithEdition();
        SingleRootClassModelPanel panel = new SingleRootClassModelPanel(project);

        GeneratedFieldModel oldEdition = project.rootClass().fields().get(0);
        panel.selectField(oldEdition);
        assertSame(oldEdition, panel.selectedUserObject());

        // A fresh model with the SAME names but NEW object instances (what load does).
        GeneratedProjectModel reloaded = modelWithEdition();
        GeneratedFieldModel newEdition = reloaded.rootClass().fields().get(0);
        assertNotNull(newEdition);

        project.copyContentsFrom(reloaded);   // project now holds the NEW objects
        panel.refresh();

        Object nowSelected = panel.selectedUserObject();
        assertSame(newEdition, nowSelected,
                "editor must re-bind to the live (new) edition field, not the orphan");
    }
}
