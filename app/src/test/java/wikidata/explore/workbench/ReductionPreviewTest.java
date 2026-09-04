package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.transform.WikidataCandidates;

import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a key change would do, before it is made.
 *
 * <p>A key decides which instances exist, and until now the only way to find out what a
 * different one produced was to regenerate and compare counts. History's 179 office
 * holdings over 173 subject/object pairs is the case: drop the dates from the key and six
 * records stop existing, which is invisible in the editor and obvious in the preview.
 *
 * <p>The instances it tries are already reduced, which is the point rather than a
 * limitation — each is its own partition under the key that made it, so the preview reads
 * "nothing would change" until the key is edited.
 */
class ReductionPreviewTest {

    private static List<canonical.Candidate> holdings() throws Exception {
        List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore().loadAll(
                new File("../data/wikidata/history/history.snapshot.json"));
        List<WikidataDynamicObject> holdings = new ArrayList<>();
        for (WikidataDynamicObject object : all) {
            if (object != null && "OfficeHolding".equals(object.typeKey())) {
                holdings.add(object);
            }
        }
        return WikidataCandidates.of(holdings);
    }

    private static String previewText(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area
                    && area.getText() != null && !area.getText().isBlank()) {
                return area.getText();
            }
            if (child instanceof Container container) {
                String found = previewText(container);
                if (!found.isBlank()) return found;
            }
        }
        return "";
    }

    @Test void theConfiguredKeyIsShownToChangeNothing() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/history/history.model.json"));
        GeneratedClassModel holding = project.findClass("OfficeHolding");

        ClassIdentityEditor editor = new ClassIdentityEditor();
        editor.show(holding);
        editor.previewAgainst(holdings());

        String shown = previewText(editor);
        assertTrue(shown.contains("Nothing is combined"),
                "the key that produced these instances still tells them apart: " + shown);
    }

    /**
     * And a coarser one is shown to lose records, in the editor, before applying.
     *
     * <p>Six people held the same office twice; only the dates separate those records.
     * Removing them is a legitimate choice and a destructive one, and this is where the
     * difference becomes visible.
     */
    @Test void droppingAKeyComponentIsShownToCombineRecords() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/history/history.model.json"));
        GeneratedClassModel holding = project.findClass("OfficeHolding");
        holding.canonical().keyFields().removeIf(
                field -> field.equals("startDate") || field.equals("endDate"));

        ClassIdentityEditor editor = new ClassIdentityEditor();
        editor.show(holding);
        editor.previewAgainst(holdings());

        String shown = previewText(editor);
        assertTrue(shown.contains("combined more than one"),
                "a coarser key merges records, and says how many: " + shown);
    }

    /** Inspecting a consequence must not be how a configuration gets made. */
    @Test void previewingChangesNothingInTheModel() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/history/history.model.json"));
        GeneratedClassModel holding = project.findClass("OfficeHolding");
        List<String> before = List.copyOf(holding.canonical().keyFields());

        ClassIdentityEditor editor = new ClassIdentityEditor();
        editor.show(holding);
        editor.previewAgainst(holdings());
        editor.previewAgainst(holdings());

        assertTrue(before.equals(holding.canonical().keyFields()),
                "the model is untouched by looking at it");
    }
}
