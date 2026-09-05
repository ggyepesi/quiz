package wikidata.explore.query.result;

import objectview.Viewable;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.MembershipPattern;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A part is reached through its owner, not listed beside it.
 *
 * <p>An owned class has no independent existence — one instance is made per owning
 * instance, carrying that owner's identifier — and the web already knows this and does
 * not serve them. The views that show instances did not: on Nobel, 989 structured names
 * appeared as a section of their own, next to the prizes and the people they name, as
 * though a Name were a thing you could have on its own.
 */
class PartsAreReachedNotListedTest {

    static final class Name implements Viewable {
        private final String id;
        Name(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id + " — Structured Name"; }
        @Override public String typeName() { return "Name"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    static final class Person implements Viewable {
        private final String id;
        public Name structuredName;
        Person(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public String typeName() { return "Person"; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
    }

    private static ObjectQueryResult resultWithParts(List<String> parts) {
        Person kissinger = new Person("Q66107");
        kissinger.structuredName = new Name("Q66107");
        return new ObjectQueryResult(List.of(kissinger), Person.class, "test",
                List.of(), parts);
    }

    @Test void aPartGetsNoSectionOfItsOwn() {
        assertEquals(List.of("Person"),
                List.copyOf(resultWithParts(List.of("Name")).byTypeWithoutParts().keySet()));
    }

    /** Reached and counted all the same — it is hidden from the headings, not the walk. */
    @Test void thePartIsStillReachedAndStillCounted() {
        ObjectQueryResult result = resultWithParts(List.of("Name"));

        assertTrue(result.byType().containsKey("Name"),
                "the grouping stays the whole truth");
        assertEquals(1, result.countOf("Name"));
    }

    /**
     * Whatever a part reaches is still found.
     *
     * <p>Which is why the part is dropped from the sections rather than from the walk:
     * skipping it would lose everything on its far side.
     */
    @Test void whatAPartReachesIsNotLostWithIt() {
        Person owner = new Person("Q1");
        owner.structuredName = new Name("Q1");
        ObjectQueryResult result = new ObjectQueryResult(List.of(owner), Person.class,
                "test", List.of(), List.of("Name"));

        assertTrue(result.byType().containsKey("Name"));
        assertFalse(result.byTypeWithoutParts().containsKey("Name"));
        assertEquals(1, result.byTypeWithoutParts().get("Person").size());
    }

    /** A result that declares no parts is unchanged — this is opt-in per producer. */
    @Test void declaringNoPartsChangesNothing() {
        ObjectQueryResult result = resultWithParts(List.of());

        assertEquals(result.byType().keySet(), result.byTypeWithoutParts().keySet());
    }

    /** Which classes are parts is asked once, of the model that knows. */
    @Test void theModelSaysWhichClassesArePartsOfAnother() throws Exception {
        GeneratedProjectModel person = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/person/person.model.json"));

        assertEquals(List.of("Name"), MembershipPattern.partClassNames(person),
                "Name is produced at Person.structuredName and nowhere on its own");
    }
}
