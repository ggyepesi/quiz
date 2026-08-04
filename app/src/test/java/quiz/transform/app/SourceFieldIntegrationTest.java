package quiz.transform.app;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.IdentitySources;
import quiz.curation.ManualCuration;
import quiz.source.ManualEntity;
import quiz.source.SourceProducer;
import quiz.source.WikidataSource;
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
        assertTrue(item.fields().read("anchor") instanceof quiz.source.ManualSource);
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
        assertTrue(item.anchor() instanceof quiz.source.WikidataSource);
        quiz.source.WikidataSource src = (quiz.source.WikidataSource) item.anchor();
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
        item.anchor(new quiz.source.WikidataStatementSource(
                "Q42$statement-guid", "P31", "statement fact"));

        java.io.File snapshot = dir.resolve("statement.snapshot.json").toFile();
        wikidata.explore.extract.WikidataDynamicObjectJsonStore store =
                new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        store.save(List.of(item), snapshot);

        WikidataDynamicObject loaded = store.load(snapshot).getFirst();
        assertEquals("Q42$statement-guid", loaded.getIdentifier());
        assertTrue(loaded.anchor() instanceof quiz.source.WikidataStatementSource);
        quiz.source.WikidataStatementSource statement =
                (quiz.source.WikidataStatementSource) loaded.anchor();
        assertEquals("P31", statement.property());
    }

    @Test void datasourcePipelineIdentifyThenPull() {
        // The full construct against real code: identify (the SourceFactory that
        // IdentitySources orchestrates) → resolve → anchor → pull (a SourceProducer
        // consuming the anchor). A manual instance is resolved to Q42, then a
        // producer reads that source and writes a field — end to end.
        WikidataDynamicObject item =
                new WikidataDynamicObject("manual-1", "Douglas Adams");
        item.type("ManualItem");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");

        // identify + resolve, through the real IdentitySources → WikidataLinkSourceFactory
        IdentitySources.apply(List.of(item), List.of(link));
        assertTrue(item.anchor() instanceof WikidataSource, "identified + anchored");
        assertEquals("manual-1", item.getIdentifier(), "identity unchanged by re-anchor");

        // pull: a SourceProducer<WikidataSource> consumes the resolved anchor
        SourceProducer<WikidataSource> producer = new SourceProducer<>() {
            @Override public Class<WikidataSource> sourceType() { return WikidataSource.class; }
            @Override public void produce(objectview.Viewable instance, WikidataSource source) {
                ((WikidataDynamicObject) instance).put("wikidata", source.wikidataUrl());
            }
        };
        WikidataSource anchor = (WikidataSource) item.anchor();
        assertTrue(producer.sourceType().isInstance(anchor), "producer dispatches by source kind");
        try {
            producer.produce(item, anchor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("https://www.wikidata.org/wiki/Q42", item.get("wikidata"),
                "the producer pulled a field from the resolved source");
    }

    private static final class ManualItem extends ManualEntity {
        private final String id;

        private ManualItem(String id) { this.id = id; }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
