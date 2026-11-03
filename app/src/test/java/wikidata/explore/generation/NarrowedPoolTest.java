package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applying a graph result narrows a class to the population the graph accepted, and a
 * population is a set of identities.
 *
 * <p>What went wrong: the apply path removed every instance of the output class from the
 * pool and put the run's candidate objects in their place. A candidate carries a QID, a
 * label and a reverse reference to its annotation record and nothing else, so one Apply
 * turned 1317 generated Positions into 1307 shells whose only field was
 * "Graph annotation" — superClasses, jurisdiction, inception, abolished, replaces,
 * replacedBy and countries gone from every one — and pulled all 1307 annotation records
 * into the domain snapshot behind that reference, where they were counted as domain data
 * (counts.tsv read total=2615 against the previous total=1317).
 *
 * <p>So the assertions are about what SURVIVES, not about the count: the kept instances
 * must be the pool's own objects, whole.
 */
class NarrowedPoolTest {

    @Test void additiveGraphMembershipKeepsExistingMembersAndAddsGeneratedPositions() {
        WikidataDynamicObject existing = position("Q1", "Rome");
        existing.type("PositionWithHolders");
        WikidataDynamicObject predecessor = position("Q2", "Francia");

        GenerationRuns.NarrowedPool expanded = GenerationRuns.addedTo(
                List.of(existing, predecessor), "PositionWithHolders", "Position"::equals,
                Set.of("Q2"));

        assertEquals(List.of(existing, predecessor), expanded.pool());
        assertTrue(existing.directClassNames().contains("PositionWithHolders"));
        assertTrue(predecessor.directClassNames().contains("PositionWithHolders"));
        assertEquals("Francia", predecessor.get("jurisdiction"));
    }

    @Test void additiveGraphMembershipNeverStampsAnAnnotationSharingTheQid() {
        WikidataDynamicObject position = position("Q1", "Catalonia");
        WikidataDynamicObject annotation = new WikidataDynamicObject("Q1", "Accepted");
        annotation.type("GraphConstraint");
        annotation.typeKey("GraphConstraint");
        // Reproduce the contaminated membership saved by the former identity-only apply.
        annotation.type("PositionWithHolders");

        GenerationRuns.NarrowedPool expanded = GenerationRuns.addedTo(
                List.of(position, annotation), "PositionWithHolders", "Position"::equals,
                Set.of("Q1"));

        assertTrue(position.directClassNames().contains("PositionWithHolders"));
        assertTrue(!annotation.directClassNames().contains("PositionWithHolders"),
                "the same QID does not make a graph annotation a position");
        assertEquals(Set.of("Q1"), expanded.kept());
    }

    /** The Historical Positions build of 2026-10-29 (#423): the second Generate acquired the
     *  expansion's pending identities through PositionWithHolders' own pipeline, so they
     *  arrived typed as the output class. The additive apply recognised only objects typed
     *  exactly Position and stripped the class from the rest — 1010 transformed members
     *  became 961, and those identities kept reading as "no generated instance yet" (#424).
     *  A subclass of the carrier is a carrier; a graph annotation sharing the QID is not. */
    @Test void anIdentityAcquiredAsTheOutputClassStaysAMemberOfIt() {
        wikidata.explore.model.GeneratedProjectModel model =
                new wikidata.explore.model.GeneratedProjectModel();
        model.rootClass(new wikidata.explore.model.GeneratedClassModel("Position"));
        var subclass = new wikidata.explore.model.GeneratedClassModel("PositionWithHolders");
        subclass.baseClassName("Position");
        model.addClass(subclass);
        WikidataDynamicObject stamped = position("Q1", "Rome");
        stamped.type("PositionWithHolders");
        WikidataDynamicObject acquired = new WikidataDynamicObject("Q2", "office Q2");
        acquired.type("PositionWithHolders");
        acquired.typeKey("PositionWithHolders");
        WikidataDynamicObject annotation = new WikidataDynamicObject("Q2", "Accepted");
        annotation.type("PositionReplacementExpansion");
        annotation.typeKey("PositionReplacementExpansion");

        GenerationRuns.NarrowedPool expanded = GenerationRuns.addedTo(
                List.of(stamped, acquired, annotation), "PositionWithHolders",
                type -> model.isSameOrSubclass(type, "Position"), Set.of("Q2"));

        assertTrue(stamped.directClassNames().contains("PositionWithHolders"));
        assertTrue(acquired.directClassNames().contains("PositionWithHolders"),
                "an identity acquired for the output class is a member of it");
        assertEquals(Set.of("Q2"), expanded.kept());
        assertEquals(Set.of(), expanded.ungenerated(), "it is generated, not pending");
        assertTrue(!annotation.directClassNames().contains("PositionWithHolders"));
    }

    private static WikidataDynamicObject position(String qid, String jurisdiction) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "office " + qid);
        value.type("Position");
        value.typeKey("Position");
        value.put("jurisdiction", jurisdiction);
        return value;
    }

    /** What the graph produces for a reached entity: an id, a label, a back-reference. */
    private static WikidataDynamicObject candidate(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, "office " + qid);
        value.type("Position");
        value.put("Graph annotation", new WikidataDynamicObject(qid, "record " + qid));
        return value;
    }

    @Test void anAcceptedInstanceKeepsEveryFieldAcquisitionGaveIt() {
        WikidataDynamicObject kept = position("Q1", "Spain");
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(kept), "Position", Set.of("Q1"));

        assertEquals(1, narrowed.pool().size());
        assertSame(kept, narrowed.pool().get(0),
                "the surviving instance is the pool's own object, not a stand-in for it");
        assertEquals("Spain", narrowed.pool().get(0).get("jurisdiction"));
        assertEquals(Set.of("Q1"), narrowed.kept());
    }

    @Test void aCandidateNeverEntersThePoolInPlaceOfAnInstance() {
        WikidataDynamicObject generated = position("Q1", "Spain");
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(generated), "Position", Set.of("Q1"));

        WikidataDynamicObject survivor = narrowed.pool().get(0);
        assertEquals(null, survivor.get("Graph annotation"),
                "narrowing selects among instances; it never substitutes the run's "
                        + "candidate objects, which would carry the annotation into the "
                        + "domain snapshot with them");
        assertTrue(narrowed.pool().stream().noneMatch(
                        value -> value.get("Graph annotation") != null),
                "no annotation reference reaches the pool");
        // The shape the bug produced, stated so it cannot come back unnoticed.
        assertEquals("Spain", survivor.get("jurisdiction"));
        assertTrue(candidate("Q1").get("Graph annotation") != null,
                "a candidate really does carry the back-reference — that is why "
                        + "substituting one loses the instance and gains the record");
    }

    @Test void aRejectedInstanceLeavesThePopulationButStaysTheEntityItWasGeneratedAs() {
        WikidataDynamicObject accepted = position("Q1", "Spain");
        WikidataDynamicObject rejected = position("Q2", "Rome");
        rejected.assignClass("ImperialOffice");
        WikidataDynamicObject other = new WikidataDynamicObject("Q3", "a person");
        other.type("Person");

        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(accepted, rejected, other), "Position", Set.of("Q1"));

        assertEquals(List.of(accepted, rejected, other), narrowed.pool(),
                "narrowing changes membership, never the pool");
        assertEquals(Set.of("ImperialOffice"), rejected.directClassNames(),
                "narrowing Position does not retract another class population");
        assertEquals("ImperialOffice", rejected.typeName());
        assertEquals("Position", rejected.typeKey(),
                "it is still the entity it was generated as: ⟨Position, Q2⟩");
        assertEquals("Rome", rejected.get("jurisdiction"));
        assertTrue(other.directClassNames().contains("Person"),
                "only the output class is narrowed; every other class passes through");
        assertEquals(Set.of("Q1"), narrowed.kept());
    }

    /**
     * The Historical Positions restore order (#423): the second Generate acquired the
     * replacement chain's predecessors as Positions, re-applying GraphConstraint narrowed
     * them out because they were never its to accept, and the chain's apply ran next. While
     * narrowing deleted them, that apply found 8 of 71 and the build ended at 961.
     */
    @Test void anEntityNarrowedOutCanStillBeAddedByALaterAdditiveGraph() {
        WikidataDynamicObject discovered = position("Q1", "Spain");
        WikidataDynamicObject predecessor = position("Q2", "Visigothic kingdom");

        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(discovered, predecessor), "Position", Set.of("Q1"));
        GenerationRuns.NarrowedPool expanded = GenerationRuns.addedTo(
                narrowed.pool(), "PositionWithHolders", "Position"::equals, Set.of("Q2"));

        assertEquals(Set.of("Q2"), expanded.kept());
        assertEquals(Set.of(), expanded.ungenerated(),
                "the generated predecessor is found, not reported as pending");
        assertTrue(predecessor.directClassNames().contains("PositionWithHolders"));
        assertEquals("Visigothic kingdom", predecessor.get("jurisdiction"));
    }

    /** The other side: an entity left in no class is not saved as data unless something
     *  still references it, as the deleted object was not. */
    @Test void aNarrowedOutEntityIsSavedOnlyWhileSomethingReferencesIt() throws Exception {
        WikidataDynamicObject accepted = position("Q1", "Spain");
        WikidataDynamicObject referenced = position("Q2", "Rome");
        WikidataDynamicObject unreferenced = position("Q3", "Gaul");
        WikidataDynamicObject holding = new WikidataDynamicObject("Q4", "a holding");
        holding.type("OfficeHolding");
        holding.typeKey("OfficeHolding");
        holding.put("position", referenced);
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(accepted, referenced, unreferenced, holding), "Position", Set.of("Q1"));
        java.io.File file = java.nio.file.Files.createTempFile("narrowed", ".snapshot.json")
                .toFile();
        file.deleteOnExit();

        new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .saveWithFieldGraph(narrowed.pool(), file);
        List<WikidataDynamicObject> saved =
                new wikidata.explore.extract.WikidataDynamicObjectJsonStore().load(file);

        Set<String> savedIds = new java.util.HashSet<>();
        saved.forEach(value -> savedIds.add(value.getIdentifier()));
        assertTrue(savedIds.containsAll(Set.of("Q1", "Q4")), savedIds.toString());
        assertTrue(!savedIds.contains("Q3"), "an unclassed, unreferenced entity is not data");
        assertTrue(saved.stream().noneMatch(value -> "Q2".equals(value.getIdentifier())
                        && value.hasTypeStamp()),
                "a referenced one survives only as the reference target, a member of nothing");
    }

    @Test void anAcceptedIdWithNoInstanceIsReportedRatherThanInvented() {
        GenerationRuns.NarrowedPool narrowed = GenerationRuns.narrowedTo(
                List.of(position("Q1", "Spain")), "Position", Set.of("Q1", "Q9"));

        assertEquals(1, narrowed.pool().size(),
                "an id the project has not generated stays an id: an empty stand-in is "
                        + "indistinguishable from an instance whose acquisition failed");
        assertEquals(Set.of("Q9"), narrowed.ungenerated());
    }
}
