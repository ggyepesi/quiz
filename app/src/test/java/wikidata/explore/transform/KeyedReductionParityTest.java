package wikidata.explore.transform;

import canonical.CanonicalizationPlan;
import canonical.KeyedReduction;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CanonicalizationPlans;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 2's exit condition: the new engine, run over what the old paths already
 * produced, reaches the same instances.
 *
 * <p>The pattern is {@code CompiledTransformParityTest}'s — run both and assert they
 * agree — because that is what makes a two-path window safe. Without it the new engine's
 * correctness is first established at regeneration, which is also the acceptance test,
 * and one event cannot establish two different things.
 *
 * <p>The input is a SHIPPED snapshot: instances the old paths produced, in the shape they
 * produced them. Re-partitioning them by their class's own key must give one instance per
 * instance, because they have already been reduced. Anything else means the two paths
 * disagree about what the key says.
 */
class KeyedReductionParityTest {

    private record Domain(GeneratedProjectModel model,
                          Map<String, List<WikidataDynamicObject>> byClass) { }

    private static Domain load(String name) throws Exception {
        File dir = new File("../data/wikidata/" + name);
        GeneratedProjectModel model = new GeneratedProjectModelStore()
                .load(new File(dir, name + ".model.json"));
        List<WikidataDynamicObject> all = new WikidataDynamicObjectJsonStore()
                .loadAll(new File(dir, name + ".snapshot.json"));
        Map<String, List<WikidataDynamicObject>> byClass = new LinkedHashMap<>();
        for (WikidataDynamicObject object : all) {
            if (object == null || object.typeKey() == null) continue;
            byClass.computeIfAbsent(object.typeKey(), ignored -> new ArrayList<>()).add(object);
        }
        return new Domain(model, byClass);
    }

    /**
     * Already-reduced instances survive the engine unchanged.
     *
     * <p>This is the parity claim in its strongest usable form. A saved instance is the
     * old paths' answer; feeding it back through the new key must not merge two of them
     * or split one, because the key is the same key. A merge here would mean the old path
     * kept two records the model says are one; a split cannot happen without the key
     * meaning something different.
     */
    @Test void reducingWhatTheOldPathsProducedChangesNothing() throws Exception {
        List<String> disagreements = new ArrayList<>();
        for (String name : List.of("history", "nobelprizes", "oscarnominations")) {
            Domain domain = load(name);
            for (GeneratedClassModel clazz : domain.model().classes()) {
                List<WikidataDynamicObject> instances =
                        domain.byClass().getOrDefault(clazz.className(), List.of());
                if (instances.isEmpty()) continue;

                CanonicalizationPlan plan = CanonicalizationPlans.of(clazz);
                if (!plan.identified()) continue;

                KeyedReduction.Result result = KeyedReduction.reduce(
                        plan, WikidataCandidates.of(instances),
                        WikidataCandidates.stableForm());

                // Every saved instance survives as exactly one — not "is accounted
                // for". Counting an unkeyed candidate as produced would let a policy
                // that DROPS records pass parity, which is how the default came to be
                // REJECT_CANDIDATE and would have discarded 99 real ones.
                if (result.instances().size() != instances.size()) {
                    disagreements.add(name + "/" + clazz.className() + ": "
                            + instances.size() + " saved became "
                            + result.instances().size() + " instance(s), "
                            + result.reducedPartitions() + " combined, "
                            + result.unkeyed().size() + " missing a key component");
                }
            }
        }
        assertEquals(List.of(), disagreements,
                "the same key over already-reduced instances must be a no-op");
    }

    /**
     * The candidate contract reads what normalization produced and does not recompute it.
     * An entity's identity is its QID, qualified; a part's is the owner-plus-site key
     * composed when it was produced.
     */
    @Test void identitiesAreReadFromWhatProductionAlreadyDecided() throws Exception {
        Domain history = load("history");
        var person = history.byClass().get("Person").get(0);
        assertTrue(WikidataCandidates.of(person)
                        .structuralIdentity(canonical.KeyComponent.Kind.SOURCE_IDENTITY)
                        .startsWith(WikidataCandidates.NAMESPACE + ":Q"),
                "provider-qualified, so no two datasources collide by accident");

        List<WikidataDynamicObject> names = history.byClass().getOrDefault("Name", List.of());
        if (!names.isEmpty()) {
            assertEquals(names.get(0).getIdentifier(),
                    WikidataCandidates.of(names.get(0))
                            .structuralIdentity(canonical.KeyComponent.Kind.OWNER_SITE_IDENTITY),
                    "a part's identity was composed at production; it is read, not remade");
        }
    }

    /**
     * A reified statement's occurrence identity is supplied, because it was always
     * there: the record's own identifier IS the Wikidata statement id.
     *
     * <p>This asserted the opposite, on a false premise of mine — that acquisition did
     * not store the GUID. Every shipped statement class is identified by one:
     * "q76555$82129A1D-…" for a holding, "Q72717$67ADCA97-…" for a nomination. The gap
     * this was documenting did not exist.
     */
    @Test void aStatementsOccurrenceIdentityIsItsStatementId() throws Exception {
        Domain history = load("history");
        var holding = history.byClass().get("OfficeHolding").get(0);

        String occurrence = WikidataCandidates.of(holding)
                .structuralIdentity(canonical.KeyComponent.Kind.SOURCE_OCCURRENCE);
        assertTrue(occurrence.startsWith(WikidataCandidates.NAMESPACE + ":"), occurrence);
        assertTrue(occurrence.contains("$"),
                "one claim on one entity, which is what an occurrence is: " + occurrence);
    }

    /** An entity has no occurrence: it is not one assertion, it is a thing. */
    @Test void anEntityHasNoOccurrenceIdentity() throws Exception {
        Domain history = load("history");
        var person = history.byClass().get("Person").get(0);

        assertEquals("", WikidataCandidates.of(person)
                .structuralIdentity(canonical.KeyComponent.Kind.SOURCE_OCCURRENCE));
    }

    /**
     * A missing key component keeps the record, and says so.
     *
     * <p>The shipped data has 99 such records — 56 Oscar nominations with no ceremony,
     * 36 office holdings with no dates, 7 Nobel awards with no motivation. They exist,
     * and the default policy may not be one that removes them: the same rule that lets a
     * reducer default says a default can only be non-destructive. Rejecting stays
     * available for a class where a candidate without a key is not a record, which is a
     * decision rather than a default.
     */
    @Test void recordsMissingAKeyComponentAreKeptAndCounted() throws Exception {
        Domain oscars = load("oscarnominations");
        GeneratedClassModel nomination = oscars.model().findClass("Nomination");
        List<WikidataDynamicObject> saved = oscars.byClass().get("Nomination");

        KeyedReduction.Result result = KeyedReduction.reduce(
                CanonicalizationPlans.of(nomination),
                WikidataCandidates.of(saved), WikidataCandidates.stableForm());

        assertEquals(saved.size(), result.instances().size(),
                "nothing is dropped for lacking a component");
        assertTrue(result.unkeyed().size() > 0,
                "and what lacked one is counted rather than passed over silently");
        assertTrue(result.report().contains("could not be keyed"), result.report());
    }
}
