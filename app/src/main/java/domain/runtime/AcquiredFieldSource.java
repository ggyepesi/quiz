package domain.runtime;

import domain.datasource.SourceRef;
import domain.datasource.evidence.SourceDocument;
import domain.provenance.WikipediaCorrespondence;
import domain.provenance.WikipediaSource;

/** Source links carried from a shared field loader to review. */
public final class AcquiredFieldSource {
    private AcquiredFieldSource() { }

    public static void wikidata(WikidataDynamicObject object, String field, String property,
            String label, String direction) {
        if (!object.capturesFieldSource(field)) return;
        object.recordFieldSource(field, new SourceRef("Wikidata", object.qid(),
                "https://www.wikidata.org/wiki/" + object.qid(), property, label, direction));
    }

    public static void wikipedia(WikidataDynamicObject object, String field, String property,
            SourceDocument document) {
        if (!object.capturesFieldSource(field)) return;
        if (document != null) {
            object.recordFieldSource(field, new SourceRef(document.kind(), document.sourceId(),
                    document.url(), property), document);
        } else {
            String title = WikipediaCorrespondence.title(object);
            if (!title.isBlank()) {
                object.recordFieldSource(field, new SourceRef("Wikipedia", title,
                        new WikipediaSource(title).url(), property));
            }
        }
    }
}
