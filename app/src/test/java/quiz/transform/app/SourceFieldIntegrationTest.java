package quiz.transform.app;

import objectview.ViewableAdapter;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.IdentitySources;
import quiz.curation.ManualCuration;
import quiz.source.WikidataSource;
import quiz.transform.ui.ReflectionDomain;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFieldIntegrationTest {

    @Test void manualDomainDeclaresSourceWithoutDiscoveringASourceEntity() {
        ManualItem item = new ManualItem("manual-1");
        ReflectionDomain domain = new ReflectionDomain(List.of(item));

        assertTrue(domain.fieldTypes("ManualItem").fieldNames().contains("source"));
        assertFalse(domain.structuralFields("ManualItem").contains("source"));
        assertFalse(domain.types().contains("Source"));
        assertNotNull(domain.fieldSchema("Source"));
        assertTrue(domain.fieldSchema("Source").fields().stream()
                .anyMatch(field -> field.name().equals("qid") && field.link()));
        assertTrue(domain.fields("ManualItem").stream()
                .anyMatch(field -> field.field().equals("source.qid")),
                "validation/search configuration sees QID through the ordinary field tree");
    }

    @Test void resolvedIdentityMaterializesAndSnapshotConversionKeepsSourceAsValue(
            @TempDir Path dir) {
        ManualItem item = new ManualItem("manual-1");
        IdentityLink link = new IdentityLink(
                "ManualItem", "manual-1", "Wikidata", "Q42",
                "https://www.wikidata.org/wiki/Q42", "Douglas Adams", "test");
        ManualCuration curation = new ManualCuration(dir.resolve("x.curation.json").toFile());
        curation.putIdentityLink(link);

        IdentitySources.apply(List.of(item), curation.identityLinks());
        WikidataSource source = assertInstanceOf(WikidataSource.class, item.source());
        assertEquals("Q42", source.sourceId());

        ReflectionDomain reflected = new ReflectionDomain(List.of(item));
        CuratableDomain domain = new CuratableDomain(reflected, curation);
        ViewableToWdo.ConvertedDomain converted = ViewableToWdo.convertDomain(
                List.of(item), List.of(), domain);
        Object storedSource = converted.memberRoots().get(0).dynamicFields().get("source");
        wikidata.explore.extract.WikidataDynamicObject sourceValue =
                assertInstanceOf(wikidata.explore.extract.WikidataDynamicObject.class,
                        storedSource);
        assertTrue(sourceValue.isValueObject());
        assertEquals("Q42|https://www.wikidata.org/wiki/Q42",
                sourceValue.dynamicFields().get("qid"));
    }

    private static final class ManualItem extends ViewableAdapter {
        private final String id;

        private ManualItem(String id) { this.id = id; }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }
}
