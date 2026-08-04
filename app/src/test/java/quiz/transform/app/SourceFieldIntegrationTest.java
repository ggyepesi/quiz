package quiz.transform.app;

import wikidata.explore.extract.WikidataDynamicObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.curation.WikidataLinkSourceFactory;
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

    @Test void manualEntityCarriesNoSourceField() {
        ManualItem item = new ManualItem("manual-1");
        ReflectionDomain domain = new ReflectionDomain(List.of(item));

        // A manual instance is authored, not produced from a source: it holds no
        // anchor/source field, and there is no Source type in the domain.
        assertFalse(domain.fieldTypes("ManualItem").fieldNames().contains("anchor"));
        assertFalse(domain.fieldTypes("ManualItem").fieldNames().contains("source"));
        assertFalse(domain.types().contains("Source"));
    }

    @Test void resolvedIdentityLivesInCurationNotOnInstance(@TempDir Path dir) {
        WikidataDynamicObject item =
                new WikidataDynamicObject("manual-1", "manual-1");
        item.type("ManualItem");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");
        ManualCuration curation = new ManualCuration(dir.resolve("x.curation.json").toFile());
        curation.putIdentityLink(link);

        // Identity is STABLE, and a non-QID manual key derives no source of its own.
        assertEquals("manual-1", item.getIdentifier());
        assertEquals("", item.qid());

        // The resolved qid is recorded in the curation history, keyed by the instance's
        // identity — never written onto the instance.
        String resolved = curation.identityLinks().stream()
                .filter(l -> l.targetId().equals(item.getIdentifier()))
                .map(IdentityLink::sourceId).findFirst().orElse(null);
        assertEquals("Q42", resolved);
    }

    @Test void wikidataIdentityDerivesQidWithoutAStoredSource(@TempDir Path dir)
            throws Exception {
        WikidataDynamicObject item = new WikidataDynamicObject("Q42", "Douglas Adams");
        item.type("Author");

        java.io.File snapshot = dir.resolve("author.snapshot.json").toFile();
        wikidata.explore.extract.WikidataDynamicObjectJsonStore store =
                new wikidata.explore.extract.WikidataDynamicObjectJsonStore();
        store.save(List.of(item), snapshot);

        WikidataDynamicObject loaded = store.load(snapshot).getFirst();
        // No source is stored; the qid/url are read from the stable identity.
        assertEquals("Q42", loaded.getIdentifier());
        assertEquals("Q42", loaded.qid());
        assertEquals("https://www.wikidata.org/wiki/Q42", loaded.wikidataUrl());
    }

    @Test void datasourcePipelineIdentifyFromCurationThenPull() {
        // The construct end to end, with source detached from the instance: identify
        // (a SourceFactory reads the curation's approved link → a WikidataSource) →
        // pull (a SourceProducer acts on that source and writes a field). The instance
        // itself never holds the source.
        WikidataDynamicObject item =
                new WikidataDynamicObject("manual-1", "Douglas Adams");
        item.type("ManualItem");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");

        // identify: from the instance + the curation's links, without touching the instance
        List<WikidataSource> candidates =
                new WikidataLinkSourceFactory(List.of(link)).identify(item);
        assertEquals(1, candidates.size(), "identified from curation history");
        WikidataSource source = candidates.get(0);
        assertEquals("Q42", source.qid());
        assertEquals("manual-1", item.getIdentifier(), "identity untouched by identify");

        // pull: a SourceProducer<WikidataSource> consumes the identified source
        SourceProducer<WikidataSource> producer = new SourceProducer<>() {
            @Override public Class<WikidataSource> sourceType() { return WikidataSource.class; }
            @Override public void produce(objectview.Viewable instance, WikidataSource s) {
                ((WikidataDynamicObject) instance).put("wikidata", s.wikidataUrl());
            }
        };
        assertTrue(producer.sourceType().isInstance(source), "producer dispatches by source kind");
        try {
            producer.produce(item, source);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals("https://www.wikidata.org/wiki/Q42", item.get("wikidata"),
                "the producer pulled a field from the identified source");
    }

    private static final class ManualItem extends ManualEntity {
        private final String id;

        private ManualItem(String id) { this.id = id; }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
