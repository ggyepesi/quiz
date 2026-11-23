package domain.provenance;

import domain.datasource.evidence.CategoryMembership;
import domain.datasource.evidence.ContentDigest;
import domain.datasource.evidence.SourceDocument;
import domain.runtime.WikidataDynamicObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikipediaCorrespondenceTest {

    @Test void aRetainedCategoryDocumentIsAnExactArticleCorrespondence() {
        WikidataDynamicObject ennead = new WikidataDynamicObject("Q233458", "Ennead");
        ennead.categoryMemberships(List.of(new CategoryMembership(
                "Groups of Egyptian deities",
                document("Ennead"))));

        assertEquals("Ennead", WikipediaCorrespondence.title(ennead));
    }

    @Test void theDeclaredSitelinkWinsWhenSeveralSourceDocumentsExist() {
        WikidataDynamicObject value = new WikidataDynamicObject("Q1", "Example");
        value.enwikiTitle("Declared article");
        value.categoryMemberships(List.of(new CategoryMembership(
                "Examples", document("Observed article"))));

        assertEquals("Declared article", WikipediaCorrespondence.title(value));
    }

    private static SourceDocument document(String title) {
        return new SourceDocument("Wikipedia (English)", title, title,
                "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'), "1",
                new ContentDigest("sha256", "abc"), "2026-11-23T00:00:00Z");
    }
}
