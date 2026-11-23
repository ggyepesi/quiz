package domain.provenance;

import domain.datasource.evidence.SourceDocument;
import domain.runtime.WikidataDynamicObject;

/**
 * The one answer to which English Wikipedia article corresponds to a loaded entity.
 *
 * <p>The Wikidata sitelink is the preferred declaration. Older snapshots and entities
 * discovered through Wikipedia may instead already carry a versioned document from that
 * exact page. Those documents are correspondence evidence too; making each consumer read
 * only {@code enwikiTitle} made configured evidence work while discovery reported zero
 * articles for the same instance.</p>
 */
public final class WikipediaCorrespondence {
    private WikipediaCorrespondence() { }

    public static String title(WikidataDynamicObject value) {
        if (value == null) return "";
        String declared = clean(value.enwikiTitle());
        if (!declared.isBlank()) return declared;
        if (value.wikipediaArticle() != null) {
            String title = title(value.wikipediaArticle().document());
            if (!title.isBlank()) return title;
        }
        if (value.articleIntroduction() != null) {
            String title = title(value.articleIntroduction().document());
            if (!title.isBlank()) return title;
        }
        if (value.infoboxParameters() != null) {
            String title = title(value.infoboxParameters().document());
            if (!title.isBlank()) return title;
        }
        return value.categoryMemberships().stream()
                .map(membership -> title(membership.document()))
                .filter(candidate -> !candidate.isBlank())
                .findFirst().orElse("");
    }

    private static String title(SourceDocument document) {
        return document == null ? "" : clean(document.title());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
