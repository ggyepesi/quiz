package wikidata.explore.generation;

import datasource.Datasources;
import datasource.api.SourceExecutionPlan;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalSourceAcquisitionTest {

    @Test void anEmptyPlanIsOneSharedNoOpForGenerateAndEnrich() throws Exception {
        SourceExecutionPlan plan = SourceExecutionPlan.compile(
                List.of(), Datasources.standard());

        ExternalSourceAcquisition.Result result = ExternalSourceAcquisition.apply(
                new GeneratedProjectModel(), List.of(), plan,
                null, null, GenerationLog.NOOP, new work.CancellationToken(),
                ExternalSourceAcquisition.FailurePolicy.STRICT);

        assertEquals(0, result.values());
        assertEquals("0 category membership(s), 0 infobox value(s), 0 DBpedia value(s)",
                result.summary());
    }
}
