package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run compiles its model once, through the owner of that decision.
 *
 * <p>A Generate run used to compile it twice — once for `GenerateDomainPipeline` to say
 * what would happen, once for `GenerateDomainQuery` to make it happen — and a third time
 * if the fact-demand plan was not handed one. Two descriptions of one model can describe
 * different models the moment anything edits it between them, and nothing would report
 * that.
 *
 * <p>The rule is about FLOWS, not about the compiler: an advisor explaining a class, a
 * panel deriving inverts, a transform deriving reifications each compile for a question
 * of their own and are not runs. What may not compile for itself is a flow that executes
 * one — those read {@link CompiledPipelineRun#model()}.
 */
class OneCompilePerRunTest {

    /** The flows: each executes a run, and so must not compile a model of its own. */
    private static final List<String> FLOWS = List.of(
            "query/logical/GenerateDomainQuery.java",
            "query/logical/SampleEffectiveClassQuery.java",
            "query/logical/SampleStatementClassQuery.java",
            "query/logical/SampleDerivedClassQuery.java",
            "generation/GenerateDomainPipeline.java");

    @Test void noFlowCompilesAModelOfItsOwn() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String flow : FLOWS) {
            if (source(flow).contains("ProjectModelCompiler.compile")) offenders.add(flow);
        }

        assertEquals(List.of(), offenders,
                "a flow reads CompiledPipelineRun.model(); compiling again is a second "
                        + "answer to what the model is");
    }

    /** And the ones that legitimately compile are questions, not runs. */
    @Test void askingAboutAModelIsNotRunningOne() throws IOException {
        assertTrue(source("advisor/EffectiveClassExplanations.java")
                        .contains("ProjectModelCompiler.compile"),
                "an explanation compiles to answer a question about the model");
    }

    /** A blocked plan stops a flow before it fetches, with the report as the reason. */
    @Test void aFlowRefusesABlockedPlanBeforeAcquiring() throws IOException {
        for (String flow : FLOWS) {
            if (flow.contains("GenerateDomainPipeline")) continue;
            assertTrue(source(flow).contains("blocked()"),
                    flow + " runs a plan without asking whether it can be run");
        }
    }

    /** Every pipeline type stays inside generation — no flow-shaped edges outward. */
    @Test void thePipelineVocabularyDependsOnNoFlow() throws IOException {
        try (Stream<Path> sources = Files.walk(
                Path.of("src/main/java/wikidata/explore/generation"))) {
            List<String> offenders = new ArrayList<>();
            for (Path source : sources.filter(p -> p.getFileName().toString()
                    .startsWith("Pipeline")).toList()) {
                String text = Files.readString(source);
                if (text.contains("query.logical") || text.contains("workbench")) {
                    offenders.add(source.getFileName().toString());
                }
            }
            assertEquals(List.of(), offenders,
                    "the pipeline is what flows are expressed in, not the other way round");
        }
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of("src/main/java/wikidata/explore/" + path));
    }
}
