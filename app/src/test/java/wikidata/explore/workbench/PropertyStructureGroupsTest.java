package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.PropertyStructuralHints;
import wikidata.explore.WikidataProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyStructureGroupsTest {
    @Test void onePropertyCanBelongToSeveralStructuralGroups() {
        WikidataProperty partOf = new WikidataProperty(
                "P361", "part of", "", "WikibaseItem", "COLLECTION",
                "P1647", "P527");
        WikidataPropertyViewable view = new WikidataPropertyViewable(partOf);

        PropertyStructureGroups.PropertyGroup root = PropertyStructureGroups.build(
                List.of(partOf), Map.of("P361", view));

        assertEquals(1, root.getMembers().size());
        for (String group : List.of("Entity relations", "Specialized relations",
                "Paired directions", "Branching values")) {
            assertTrue(root.getChild(group).getMembers().contains(view), group);
        }
    }

    @Test void oldCatalogueRowsStillReceiveIntrinsicHints() {
        WikidataProperty entity = new WikidataProperty(
                "P39", "position held", "", "WikibaseItem", "AUTO");

        assertEquals(List.of("Entity relations"), PropertyStructuralHints.of(entity));
    }
}
