package wikidata.explore.model;

import canonical.KeyComponent;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CanonicalizationPlans;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What identifies an instance is worked out in one place.
 *
 * <p>It was worked out in two: {@code Canonicalizer} branched on the class kind to build
 * an identifier, and the plan compiler branched the same way to build a key. They agreed
 * — which is the dangerous state, because two derivations of one fact stay agreed only
 * until one of them learns something the other does not.
 */
class OneIdentityDerivationTest {

    @Test void theCompilerAsksTheModelRatherThanWorkingItOutAgain() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.classKind(ClassKind.SOURCE);

        assertEquals(Canonicalizer.keyComponents(person.classKind(), person.canonical()),
                CanonicalizationPlans.keyOf(person.classKind(), person.canonical()),
                "one derivation, reached from either side");
        assertEquals(List.of(KeyComponent.sourceIdentity()),
                CanonicalizationPlans.of(person).key());
    }

    /**
     * And the identifiers it produces are the ones already in the shipped snapshots.
     *
     * <p>Identity is joined from a key's values in order, so a change in how the key is
     * derived would show up as a changed identifier — on 30,000 instances, that is a
     * strong check that the two derivations really were the same one.
     */
    @Test void everyShippedIdentifierStillFollowsFromItsKey() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String name : List.of("history", "nobelprizes", "oscarnominations")) {
            File dir = new File("../data/wikidata/" + name);
            GeneratedProjectModel model = new GeneratedProjectModelStore()
                    .load(new File(dir, name + ".model.json"));
            List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore()
                    .loadAll(new File(dir, name + ".snapshot.json"));

            for (GeneratedClassModel clazz : model.classes()) {
                var key = CanonicalizationPlans.of(clazz).key();
                if (key.size() != 1
                        || key.get(0).kind() != KeyComponent.Kind.SOURCE_IDENTITY) {
                    continue;
                }
                // A source-identified class's instances are identified by their QID.
                for (WikidataDynamicObject object : all) {
                    if (object == null || !clazz.className().equals(object.typeKey())) continue;
                    if (!object.getIdentifier().equals(object.qid()) && !object.qid().isBlank()) {
                        wrong.add(name + "/" + clazz.className() + ": "
                                + object.getIdentifier() + " vs " + object.qid());
                    }
                }
            }
        }
        assertEquals(List.of(), wrong.stream().limit(5).toList(),
                "a source-identified instance is identified by its source id");
    }

    /**
     * Nothing else works out which identity regime applies.
     *
     * <p>Asking BOTH regimes is the signature of deriving one — a file that chooses
     * between source identity and owner identity is answering "what identifies this",
     * which now has one answer. Forwarding a single flag is not that:
     * {@code CompiledClass} and {@code ProductCompiler} each pass one along, and neither
     * builds a key. The first version of this guard flagged any mention and caught them,
     * which is a guard that would have to be relaxed at every unrelated accessor.
     */
    @Test void onlyOneFileChoosesBetweenTheIdentityRegimes() throws Exception {
        List<String> derivers = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file);
                if (source.contains("identityFromSource()")
                        && source.contains("identityFromOwner()")) {
                    derivers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("Canonicalizer.java", "ClassKind.java"),
                derivers.stream().sorted().toList(),
                "one file derives it, and the enum that defines the regimes; the plan "
                        + "compiler asks that file rather than choosing again");
    }
}
