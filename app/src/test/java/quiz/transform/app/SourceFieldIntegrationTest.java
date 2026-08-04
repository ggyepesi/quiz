package quiz.transform.app;

import wikidata.explore.extract.WikidataDynamicObject;

import objectview.field.FieldSet;
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
    }

    @Test void resolvingIdentityReAnchorsSourceWithoutChangingIdentity(
            @TempDir Path dir) {
        WikidataDynamicObject item =
                new WikidataDynamicObject("manual-1", "manual-1");
        item.type("ManualItem");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");
        ManualCuration curation = new ManualCuration(dir.resolve("x.curation.json").toFile());
        curation.putIdentityLink(link);

        IdentitySources.apply(List.of(item), curation.identityLinks());

        // Re-anchoring never re-keys the object: identity is stable.
        assertEquals("manual-1", item.getIdentifier());
        // The Wikidata anchor is attached as the source descriptor.
        assertTrue(item.anchor() instanceof quiz.source.WikidataViewable);
        quiz.source.WikidataViewable src = (quiz.source.WikidataViewable) item.anchor();
        assertEquals("Q42", src.qid());
        assertEquals("Douglas Adams", src.getDisplayName());
    }

    private static final class ManualItem extends ManualEntity {
        private final String id;

        private ManualItem(String id) { this.id = id; }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }
}
