package quiz.curation.ui;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.transform.ui.DomainModel;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurationOverviewPanelTest {

    @TempDir Path temp;

    @Test void inventoriesAllPersistedDirectiveKinds() throws Exception {
        File sidecar = new File(temp.toFile(), "states.curation.json");
        ManualCuration curation = new ManualCuration(sidecar);
        curation.put("State", "AL", "capital", "Montgomery", "manual", null);
        curation.putFieldDeclaration("State", objectview.field.FieldRef.of(
                "admissionDate", objectview.field.FieldKind.ORDERED,
                "Date", false, false, false));
        curation.putMerge("State", "AL", "Alabama-old", java.util.Map.of(
                "flag", "primary"));
        curation.putIdentityLink(new IdentityLink(
                "State", "AL", "Wikidata", "Q173",
                "https://www.wikidata.org/wiki/Q173", "Alabama", "wikidata"));
        curation.save();

        CurationOverviewPanel panel = new CurationOverviewPanel(
                new TestDomain(List.of(new Item("AL", "Alabama"))),
                new ManualCuration(sidecar).load());

        assertEquals(4, panel.operationCount());
        assertTrue(panel.navigationLabels().contains("Field definitions (1)"));
        assertTrue(panel.navigationLabels().contains("Corrections (1)"));
        assertTrue(panel.navigationLabels().contains("Merges (1)"));
        assertTrue(panel.navigationLabels().contains("Identity links (1)"));
    }

    private record TestDomain(List<? extends Viewable> values) implements DomainModel {
        @Override public List<String> types() { return List.of("State"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
        @Override public Collection<? extends Viewable> instances() { return values; }
        @Override public Class<? extends Viewable> universe() { return Item.class; }
    }

    private static final class Item extends ViewableAdapter {
        private final String id;
        private final String name;

        private Item(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return "State"; }
    }
}
