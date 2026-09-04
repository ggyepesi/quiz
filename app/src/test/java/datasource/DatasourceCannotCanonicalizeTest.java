package datasource;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A provider says what it produced. It never says what becomes of it.
 *
 * <p>That is the boundary this whole design draws, and the reason it must be enforced
 * rather than described: identity and reduction are shared by every provider, so a
 * provider that decided them would be deciding for all the others. It has happened
 * already — which record survived a collision was chosen by an Oscars-shaped preference
 * living inside Wikidata's transform layer, and that was the general rule for every
 * domain until this refactor named it.
 *
 * <p>So the contract is allowed and the engine is not. A provider may implement
 * {@link canonical.Candidate} and name the kinds of identity its production supplies; it
 * may not import the reduction engine, the compiled plan or the reducer vocabulary, and
 * it may not grow its own words for them.
 */
class DatasourceCannotCanonicalizeTest {

    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    /** What a provider may hand off with: the candidate, and the kinds of identity. */
    private static final Set<String> CONTRACT =
            Set.of("canonical.Candidate", "canonical.KeyComponent");

    /** Deciding what becomes of a candidate. Not a provider's question. */
    private static final Set<String> ENGINE = Set.of(
            "canonical.KeyedReduction", "canonical.CanonicalizationPlan",
            "canonical.Reduction", "canonical.MissingKeyPolicy", "canonical.StableForm");

    /** A provider growing its own vocabulary for the same thing is the same failure,
     *  just spelled differently — which is exactly how EXCLUDE and GROUP came to be a
     *  second name for REJECT_CANDIDATE and INCOMPLETE_GROUP. */
    private static final Pattern OWN_VOCABULARY = Pattern.compile(
            "\\benum\\s+(MissingKeyPolicy|DuplicatePolicy|Reduction|MergePolicy"
                    + "|CanonicalKey|KeyPolicy)\\b");

    @Test void noProviderImportsTheCanonicalizationEngine() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            Matcher imports = IMPORT.matcher(Files.readString(file));
            while (imports.find()) {
                String imported = imports.group(1);
                if (ENGINE.contains(imported)) {
                    offenders.add(file.getFileName() + " imports " + imported);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a provider hands off candidates; identity and reduction are the model's");
    }

    @Test void norDoesOneGrowItsOwnWordsForTheSameDecisions() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            Matcher declared = OWN_VOCABULARY.matcher(Files.readString(file));
            while (declared.find()) {
                offenders.add(file.getFileName() + " declares " + declared.group(1));
            }
        }
        assertEquals(List.of(), offenders,
                "one vocabulary for one concept, or the two drift while agreeing");
    }

    /** And the contract itself stays reachable, or the boundary has no handoff at all. */
    @Test void theHandoffItselfIsAvailableToProviders() throws Exception {
        List<String> reaching = new ArrayList<>();
        for (Path file : sources()) {
            Matcher imports = IMPORT.matcher(Files.readString(file));
            while (imports.find()) {
                if (CONTRACT.contains(imports.group(1))) {
                    reaching.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("CandidateProducer.java"), reaching,
                "one handoff, and it is the only place a provider meets the model");
    }

    private static List<Path> sources() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/datasource"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
