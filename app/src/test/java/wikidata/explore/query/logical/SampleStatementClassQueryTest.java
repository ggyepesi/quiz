package wikidata.explore.query.logical;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.FakeWikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.VocabularySelection;
import wikidata.explore.query.core.WikidataAccess;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The statement inspection adapter runs acquisition and construction, not a parallel preview. */
class SampleStatementClassQueryTest {

    @Test void samplesAClasslessStatementThroughDiscoveryAndReification() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        VocabularySelection categories = new VocabularySelection("Categories");
        categories.valueQids(List.of("Q102427"));
        project.addSelection(categories);

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        StatementClassSource source = new StatementClassSource("P1411");
        source.valueSelectionName("Categories");
        nomination.statementSource(source);
        nomination.instanceMapping().propertyPid("P1411");
        nomination.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");
        project.addClass(nomination);

        RecordingSparql sparql = new RecordingSparql();
        sparql.row(Map.of("subject", "Q11"));
        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q11", "Nominee")
                .entity("Q102427", "Best Actor")
                .statement("Q11", "P1411", "Q11$nomination", "Q102427", Map.of());

        var result = new SampleStatementClassQuery(
                project, "Nomination", "statement P1411", 8)
                .execute(WikidataAccess.of(sparql, api).bind());

        assertEquals(1, result.size());
        assertEquals("Nomination", result.instances().objects().getFirst().typeName());
        var sampled = result.instances().objects().getFirst();
        assertEquals("Nominee — Best Actor", sampled.getDisplayName(),
                "the normal reifier's statement display name survives materialization");
        assertEquals("Best Actor",
                ((objectview.Viewable) sampled.fields().read("category")).getDisplayName());
        assertTrue(sparql.lastQuery.contains("LIMIT 9"),
                "inspection bounds subject discovery at limit + 1");
    }

    private static final class RecordingSparql extends FakeWikidataSparqlClient {
        private String lastQuery = "";

        @Override public List<wikidata.WikidataBinding> query(String query) {
            lastQuery = query;
            return super.query(query);
        }
    }
}
