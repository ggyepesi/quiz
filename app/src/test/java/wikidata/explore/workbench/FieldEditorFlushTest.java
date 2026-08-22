package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving flushes the field editor without moving the reader.
 *
 * <p>The editor keeps pending Swing values after another tree node is selected, so a save has
 * to flush it or the edits are lost — that is the bug this file's rule comes from, where field
 * flags set and Applied never reached model.json. But flushing used to go through the same
 * path as pressing Apply, which fires {@code afterApplyField}: the class tree re-selects the
 * edited field. Saving while reading a class node therefore jumped the selection to whichever
 * field had last been touched, which is a strange thing for a save to do.
 *
 * <p>So the write and the announcement are separate. Both paths write; only an explicit Apply
 * says so.
 */
class FieldEditorFlushTest {

    @Test void flushingBeforeASaveStillWritesThePendingValues() {
        GeneratedFieldModel field = field();
        FieldSourcePanel panel = editorFor(field);
        panel.pendingPropertyField().setText("P840");

        panel.applyEdits();

        assertEquals("P840", field.mapping().propertyPid(),
                "an unflushed editor is how the edits were lost in the first place");
    }

    @Test void flushingBeforeASaveDoesNotReSelectTheEditedField() {
        List<GeneratedFieldModel> reSelected = new ArrayList<>();
        GeneratedFieldModel field = field();
        FieldSourcePanel panel = editorFor(field);
        panel.afterApplyField(reSelected::add);
        panel.pendingPropertyField().setText("P840");

        panel.applyEdits();

        assertEquals(List.of(), reSelected,
                "a save is not a reason to move the reader to another node");
    }

    /** The model still changed, so whatever tracks that has to hear about it. */
    @Test void flushingBeforeASaveStillReportsThatTheModelChanged() {
        List<Object> changes = new ArrayList<>();
        FieldSourcePanel panel = editorFor(field());
        panel.afterChange(changes::add);
        panel.pendingPropertyField().setText("P840");

        panel.applyEdits();

        assertEquals(1, changes.size());
    }

    /** An explicit interaction still announces itself — that is what the hook is for. */
    @Test void anExplicitEditStillReSelectsTheFieldItEdited() {
        List<GeneratedFieldModel> reSelected = new ArrayList<>();
        GeneratedFieldModel field = field();
        FieldSourcePanel panel = editorFor(field);
        panel.afterApplyField(reSelected::add);

        panel.useProperty("P840", "narrative location");

        assertEquals(List.of(field), reSelected);
        assertEquals("P840", field.mapping().propertyPid());
    }

    @Test void flushingAnEditorWithNoFieldLoadedDoesNothingAtAll() {
        List<Object> anything = new ArrayList<>();
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.afterChange(anything::add);
        panel.afterApplyField(anything::add);

        panel.applyEdits();

        assertTrue(anything.isEmpty(), "there is nothing to flush and nothing to announce");
    }

    private static GeneratedFieldModel field() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().sourceQid("Q11424");
        movie.instanceMapping().propertyPid("P31");
        model.rootClass(movie);
        return movie.addField("location", FieldType.TEXT, FieldCardinality.SINGLE);
    }

    private static FieldSourcePanel editorFor(GeneratedFieldModel field) {
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.edit(field);
        return panel;
    }
}
