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
 * A part is reached through its owner and remains visible as an instance of its class.
 *
 * <p>An owned class has no independent existence — one instance is made per owning
 * instance, carrying that owner's identifier. Ownership governs production and identity;
 * it must not make the produced instances disappear from ModelBuilder while TransformApp
 * shows the same class and snapshot.
 */
class PartsAreReachedAndListedTest {

    static final class Name implements Viewable {
        private final String id;
        Name(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        // The same name as its owner, which is what a part is called now.
        @Override public String getDisplayName() { return id; }
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

    @Test void aPartGetsAClassSectionOfItsOwn() {
        assertEquals(List.of("Person", "Name"),
                List.copyOf(resultWithParts(List.of("Name")).byType().keySet()));
    }

    /** Reached and counted by the same grouping that supplies the headings. */
    @Test void thePartIsStillReachedAndStillCounted() {
        ObjectQueryResult result = resultWithParts(List.of("Name"));

        assertTrue(result.byType().containsKey("Name"),
                "the grouping stays the whole truth");
        assertEquals(1, result.countOf("Name"));
    }

    /**
     * Whatever a part reaches is still found.
     *
     * <p>The instance view walks through the part, so everything on its far side remains
     * reachable too.
     */
    @Test void whatAPartReachesIsNotLostWithIt() {
        Person owner = new Person("Q1");
        owner.structuredName = new Name("Q1");
        ObjectQueryResult result = new ObjectQueryResult(List.of(owner), Person.class,
                "test", List.of(), List.of("Name"));

        assertTrue(result.byType().containsKey("Name"));
        assertEquals(1, result.byType().get("Name").size());
        assertEquals(1, result.byType().get("Person").size());
    }

    @Test void aSameTypedReferenceIsNotAnotherPopulationMember() {
        class RefPerson implements Viewable {
            final String id; public Viewable related;
            RefPerson(String id) { this.id = id; }
            @Override public String getIdentifier() { return id; }
            @Override public String getDisplayName() { return id; }
            @Override public String typeName() { return "Position"; }
            @Override public FieldSet fields() { return FieldSet.of(this); }
        }
        RefPerson root = new RefPerson("Q1");
        RefPerson reference = new RefPerson("Q2");
        root.related = reference;

        ObjectQueryResult result = new ObjectQueryResult(List.of(root), RefPerson.class, "");

        assertEquals(List.of(root), result.byType().get("Position"),
                "a class section lists its roots; a same-typed referenced object remains "
                        + "a reference, not a second instance");
    }

    /** Which classes are parts is asked once, of the model that knows. */
    @Test void theModelSaysWhichClassesArePartsOfAnother() throws Exception {
        GeneratedProjectModel person = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/person/person.model.json"));

        assertEquals(List.of("Name"), MembershipPattern.partClassNames(person),
                "Name is produced at Person.structuredName and nowhere on its own");
    }
}
