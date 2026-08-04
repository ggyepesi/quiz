package quiz.transform.app;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.IdentitySources;
import quiz.curation.ManualCuration;
import quiz.source.ManualEntity;
import quiz.transform.ui.ReflectionDomain;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFieldIntegrationTest {

    @Test void manualEntityDeclaresItsAnchorAsAnOrdinaryProvenanceField() {
        ManualItem item = new ManualItem("manual-1");
        ReflectionDomain domain = new ReflectionDomain(List.of(item));

        assertTrue(domain.fieldTypes("ManualItem").fieldNames().contains("anchor"));
        assertFalse(domain.structuralFields("ManualItem").contains("anchor"));
        assertFalse(domain.types().contains("Source"));
        assertTrue(item.fields().read("anchor") instanceof quiz.source.ManualViewable);
    }

    @Test void resolvingIdentityReAnchorsWithoutChangingIdentity(
            @TempDir Path dir) {
        WikidataDynamicObject item =
                new WikidataDynamicObject("manual-1", "manual-1");
        item.type("ManualItem");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");
        ManualCuration curation = new ManualCuration(dir.resolve("x.curation.json").toFile());
        curation.putIdentityLink(link);

        java.util.Set<WikidataDynamicObject> membership = new java.util.HashSet<>();
        membership.add(item);

        IdentitySources.apply(List.of(item), curation.identityLinks());

        // Identity is STABLE — re-anchoring never re-keys the object.
        assertEquals("manual-1", item.getIdentifier());
        // The resolved qid is REMEMBERED in the anchor (for enrichment), not adopted
        // as identity.
        assertTrue(item.anchor() instanceof quiz.source.WikidataViewable);
        quiz.source.WikidataViewable src = (quiz.source.WikidataViewable) item.anchor();
        assertEquals("Q42", src.qid());
        assertEquals("Douglas Adams", src.getDisplayName());
        assertTrue(membership.contains(item),
                "stable identity keeps the object valid in membership collections");
    }

    @Test void statementAnchorSurvivesSnapshotRoundTrip(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject item = new WikidataDynamicObject(
                "Q42$statement-guid", "statement fact");
        item.type("StatementFact");
        item.anchor(new quiz.source.WikidataStatementViewable(
                "Q42$statement-guid", "P31", "statement fact"));

        java.io.File snapshot = dir.resolve("statement.snapshot.json").toFile();
        wikidata.explore.extract.WikidataDynamicObjectJsonStore store =
                new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        store.save(List.of(item), snapshot);

        WikidataDynamicObject loaded = store.load(snapshot).getFirst();
        assertEquals("Q42$statement-guid", loaded.getIdentifier());
        assertTrue(loaded.anchor() instanceof quiz.source.WikidataStatementViewable);
        quiz.source.WikidataStatementViewable statement =
                (quiz.source.WikidataStatementViewable) loaded.anchor();
        assertEquals("P31", statement.property());
    }

    private static final class ManualItem extends ManualEntity {
        private final String id;

        private ManualItem(String id) { this.id = id; }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
