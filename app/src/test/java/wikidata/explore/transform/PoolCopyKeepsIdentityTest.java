package wikidata.explore.transform;

import datasource.evidence.CategoryMembership;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deep copy has to produce the same objects, not merely objects with the same field
 * values — and {@code PoolCopy} used to decide what "the same object" meant from
 * outside, by listing the four pieces of state it happened to know about.
 *
 * <p>Every piece added to {@link WikidataDynamicObject} afterwards was therefore dropped
 * in silence. {@code part} was one of them, and the consequences ran a long way from the
 * copier: a deep copy of a loaded snapshot presented its owned components as ordinary
 * entities, so the kind classifier — which explicitly skips parts — restamped them and
 * overwrote the production site their type key names ({@code Name@Person.structuredName}
 * became {@code Name}). Owned composition could then no longer find a part it had
 * already made, and manufactured a second one for the same owner. Every Remap, for ever:
 * 40173 objects became 47036, then 53899, then 60762.
 */
class PoolCopyKeepsIdentityTest {

    /**
     * The one thing {@code copyWithoutFields} deliberately does not carry. A graph
     * copier has to rewire each reference to its own clone, so it supplies the values
     * itself.
     */
    private static final Set<String> SUPPLIED_BY_THE_CALLER = Set.of("dynamicFields");

    @Test void everyPieceOfStateIsEitherCopiedOrDeliberatelyLeftToTheCaller() {
        // The forcing part. Adding a field to WikidataDynamicObject without deciding
        // whether a copy carries it is exactly how `part` was lost, and the loss was
        // invisible until a Remap silently doubled a class months later.
        WikidataDynamicObject source = fullyPopulated();
        WikidataDynamicObject copy = source.copyWithoutFields();

        List<String> notCarried = new ArrayList<>();
        for (Field field : WikidataDynamicObject.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (SUPPLIED_BY_THE_CALLER.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object from = field.get(source);
                Object to = field.get(copy);
                if (from == null ? to != null : !String.valueOf(from).equals(String.valueOf(to))) {
                    notCarried.add(field.getName() + ": " + from + " -> " + to);
                }
            } catch (IllegalAccessException unreachable) {
                throw new AssertionError(unreachable);
            }
        }

        assertTrue(notCarried.isEmpty(),
                "copyWithoutFields drops state that makes the object itself. Carry it, "
                        + "or name it in SUPPLIED_BY_THE_CALLER with the reason:\n  "
                        + String.join("\n  ", notCarried));
    }

    @Test void aPartStaysAPartThroughADeepCopy() {
        // The specific loss, named: a part must not present itself as an ordinary
        // entity, because the kind classifier decides what to restamp on that basis.
        WikidataDynamicObject part = new WikidataDynamicObject("Q115990", "Lawrence Lasker — Structured Name");
        part.type("Name");
        part.typeKey("Name@Person.structuredName");
        part.part(true);

        WikidataDynamicObject copied = PoolCopy.deepCopy(List.of(part)).get(0);

        assertTrue(copied.isPart(), "a birth name is not a person, in a copy either");
        assertEquals("Name@Person.structuredName", copied.typeKey(),
                "and its type key still names the site that produced it");
    }

    @Test void aCopyKeepsEveryClassItWasStampedWithNotJustTheCarrier() {
        WikidataDynamicObject object = new WikidataDynamicObject("Q42", "Somebody");
        object.type("Person");
        object.assignClass("Nominee");

        WikidataDynamicObject copied = PoolCopy.deepCopy(List.of(object)).get(0);

        assertTrue(copied.directClassNames().containsAll(Set.of("Person", "Nominee")),
                "a copy that keeps only the carrier reads as newly unclassified, and "
                        + "every classifier pass reports it as newly classified again: "
                        + copied.directClassNames());
    }

    @Test void referencesStillPointAtTheCopiesAndCyclesTerminate() {
        // The property the copier existed for in the first place — kept honest while
        // its identity handling changed.
        WikidataDynamicObject person = new WikidataDynamicObject("Q1", "Person");
        WikidataDynamicObject name = new WikidataDynamicObject("Q1", "Person — Name");
        name.part(true);
        person.put("structuredName", name);
        name.put("owner", person);

        List<WikidataDynamicObject> copies = PoolCopy.deepCopy(List.of(person, name));

        assertTrue(copies.get(0) != person, "a copy, not the original");
        assertEquals(copies.get(1), copies.get(0).get("structuredName"),
                "the reference is rewired to the copy, not left pointing at the original");
        assertEquals(copies.get(0), copies.get(1).get("owner"), "and the cycle closes");
    }

    private static WikidataDynamicObject fullyPopulated() {
        WikidataDynamicObject object =
                new WikidataDynamicObject("Q115990", "Lawrence Lasker — Structured Name");
        object.type("Name");
        object.assignClass("Nominee");
        object.typeKey("Name@Person.structuredName");
        object.part(true);
        object.valueObject(true);
        object.referenceLabel("a reference label");
        object.aliases(List.of("an alias"));
        object.categoryMemberships(List.of(new CategoryMembership(
                "American screenwriters",
                new datasource.evidence.SourceDocument(
                        "wikipedia", "en", "Lawrence Lasker",
                        "https://en.wikipedia.org/wiki/Lawrence_Lasker", "1",
                        new datasource.evidence.ContentDigest("sha256", "d"),
                        "2026-08-23T00:00:00Z"))));
        object.wikidataEntityMissing(true);
        object.infoboxParameters(datasource.evidence.InfoboxParameters.fromEnglishWikipedia(
                "Infobox person", java.util.Map.of("occupation", "screenwriter"),
                "Lawrence Lasker", "1"));
        object.fieldStatus("image", wikidata.explore.extract.FieldStatus.ASSERTED_NONE);
        object.put("someField", "some value");   // proves fields are the caller's job
        return object;
    }
}
