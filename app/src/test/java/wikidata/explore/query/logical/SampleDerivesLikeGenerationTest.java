package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sampled instance is what a generated one is, so the sample runs every derivation.
 *
 * <p>The production chain says how the SAMPLED class is produced. It says nothing about
 * what hangs off the classes its instances REACH, and using it to choose which
 * derivation steps to run confused the two: a NobelPrize is aggregated and owns nothing,
 * so composition was skipped, and its laureates came back without the structured names a
 * generated laureate has — from a step that would have made them had it run.
 *
 * <p>What is derived is decided by what is IN the pool; the chain decides only where the
 * bound sits — keys where a reduction happens, owners where none does.
 */
class SampleDerivesLikeGenerationTest {

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/wikidata/explore/query/logical/"
                + "SampleDerivedClassQuery.java"));
    }

    @Test void compositionIsNotConditionalOnHowTheSampledClassIsProduced()
            throws Exception {
        assertFalse(source().contains("Kind.OWNED"),
                "a sample skipping composition because the class it was asked for owns "
                        + "nothing is a sample whose laureates have no names");
    }

    /** The chain still decides the bound — that is the question it can answer. */
    @Test void theChainStillDecidesWhereTheBoundSits() throws Exception {
        assertTrue(source().contains("Kind.AGGREGATED"),
                "keys where a reduction happens, owners where none does");
    }

    /** Generation's order, not the chain's: a part must exist before it can be grouped. */
    @Test void partsAreComposedBeforeGroupsAreReduced() throws Exception {
        String body = source();
        assertTrue(body.indexOf("SemanticConvergence.apply")
                        < body.indexOf("ModelAggregates.apply"),
                "reducing first would group parts that do not exist yet");
    }
}
