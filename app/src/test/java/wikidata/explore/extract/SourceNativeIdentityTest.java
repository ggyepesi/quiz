package wikidata.explore.extract;

import datasource.EntityRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.Canonicalizer;
import wikidata.explore.model.ClassKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SOURCE class means source identity, not specifically Wikidata identity.
 *
 * <p>Nobel Prize is the forcing case: {@code phy/2018} is a perfectly stable native
 * source key and inventing a QID or a statement class for it would falsify how the
 * object is built. The provider-qualified id must survive canonicalization and the
 * snapshot boundary while remaining visibly not-a-Wikidata-entity.
 */
class SourceNativeIdentityTest {

    @Test void aSourceClassKeepsAProviderQualifiedIdentifier() {
        String id = new EntityRef("nobel", "prize:phy:2018").qualifiedId();

        assertEquals("nobel:prize:phy:2018", Canonicalizer.identifier(
                ClassKind.SOURCE, new CanonicalSpec(), Map.of()::get, id, "fallback"));
    }

    @Test void aNativeSourceIdentifierRoundTripsWithoutBecomingAQid(
            @TempDir Path directory) throws Exception {
        String id = new EntityRef("nobel", "prize:phy:2018").qualifiedId();
        WikidataDynamicObject prize =
                new WikidataDynamicObject(id, "The Nobel Prize in Physics 2018");
        prize.type("NobelPrize");
        prize.put("year", "2018");

        Path snapshot = directory.resolve("nobel.snapshot.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(prize), snapshot.toFile());

        WikidataDynamicObject loaded = store.loadAll(snapshot.toFile()).getFirst();
        assertEquals(id, loaded.getIdentifier());
        assertEquals("", loaded.qid(), "a native Nobel id is not a Wikidata QID");
        assertEquals("", loaded.wikidataUrl(),
                "the carrier does not invent a Wikidata link for another source's id");
        assertEquals("2018", loaded.get("year"));
    }
}
