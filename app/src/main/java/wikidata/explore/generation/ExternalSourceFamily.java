package wikidata.explore.generation;

import datasource.api.SourceExecutionPlan;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

/** One installable external field-acquisition family. */
public interface ExternalSourceFamily {
    String id();
    String displayName();
    default int summaryOrder() { return 0; }
    boolean configured(SourceExecutionPlan plan);
    Outcome empty();
    Outcome acquire(Context context) throws Exception;

    record Context(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan, WikidataSparqlClient dbpedia,
            wikidata.api.WikidataApiClient wikidata, GenerationLog log,
            work.CancellationToken cancellation) { }

    record Outcome(String familyId, int values, String summary, int summaryOrder) {
        public Outcome {
            familyId = familyId == null ? "" : familyId;
            summary = summary == null ? "" : summary;
        }
    }
}
