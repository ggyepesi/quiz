package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instances of a declared class that share a display label — different meaning different
 * identifiers. Information about the domain, with no further automatic use.
 *
 * <p>What it excludes is the part worth stating. A value reached through a field is not
 * an instance however it arrived: the given name behind {@code Name.givenName} is a
 * Wikidata item with a label, but here it is a field's value, and two equal values are no
 * more a collision than two languages with five million speakers. Whether such values
 * should become a class, a vocabulary or stay as plain fields is a modelling decision
 * with several right answers, and reporting them here would argue for one of them.
 */
class NameCollisionsTest {

    /** The three declared classes these tests use, one per identity regime — because
     *  what a shared label means is decided by the kind, so a fixture naming only class
     *  names could not say. */
    private static final GeneratedProjectModel DECLARED = declared();

    @Test void twoInstancesUnderOneLabelAreACollision() {
        List<NameCollisions.ClassCollisions> byClass = NameCollisions.detect(List.of(
                instance("Q308", "Mercury", "Planet"),
                instance("Q1090", "Mercury", "Planet"),
                instance("Q111", "Venus", "Planet")), DECLARED);

        assertEquals(1, byClass.size());
        assertEquals("Planet", byClass.getFirst().className());
        assertEquals("Mercury", byClass.getFirst().collisions().getFirst().name());
        assertEquals(List.of("Q308", "Q1090"),
                byClass.getFirst().collisions().getFirst().ids());
        assertEquals(2, NameCollisions.instanceCount(byClass));
    }

    @Test void aFieldValueIsNotAnInstanceHoweverItArrived() {
        // Wikidata keeps one item per given name per language, so "Lee" the family name
        // and "Lee" the given name are different QIDs sharing a label. They are the
        // values of Name.givenName / Name.familyName — equal values, not two things
        // called the same.
        List<WikidataDynamicObject> pool = List.of(
                value("Q2061957", "Lee"), value("Q12794688", "Lee"),
                value("Q11983535", "Lee"));

        assertTrue(NameCollisions.detect(pool, DECLARED).isEmpty(),
                "reporting these would argue that they ought to be a class");
    }

    @Test void anInstanceOfAClassTheConfigurationDoesNotDeclareIsNotReported() {
        assertTrue(NameCollisions.detect(List.of(
                instance("Q1", "Mercury", "Undeclared"),
                instance("Q2", "Mercury", "Undeclared")), DECLARED).isEmpty());
    }

    @Test void aStatementInstanceIsIdentifiedByItsAssembledId() {
        // A Nomination is an instance of a declared class; its id is a statement id
        // rather than a QID, and it is still a different instance. Fifty-four of them
        // reading "Meryl Streep" is a true statement about that class's display-name rule.
        List<NameCollisions.ClassCollisions> byClass = NameCollisions.detect(List.of(
                instance("Q42$3f9a1c", "Meryl Streep", "Nomination"),
                instance("Q42$77b2de", "Meryl Streep", "Nomination"),
                instance("Q42$aa10ff", "Meryl Streep", "Nomination")), DECLARED);

        assertEquals(3, byClass.getFirst().collisions().getFirst().size());
        assertEquals("Nomination", byClass.getFirst().className());
        assertEquals(NameCollisions.Meaning.STATEMENT_REPETITION,
                byClass.getFirst().meaning());
        assertTrue(NameCollisions.classes(
                byClass, NameCollisions.Meaning.ENTITY_AMBIGUITY).isEmpty());
    }

    @Test void classesArePartitionedSoTheLargestCountDoesNotBuryTheOthers() {
        // A class whose labels do not distinguish its instances contributes thousands.
        // In one list it drowns every other class, though both are worth reading — and
        // they are read separately, because a Nomination repeating its nominee's name
        // and two planets called Mercury are not the same finding.
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(
                instance("Q1", "Little Women", "Planet"),
                instance("Q2", "Little Women", "Planet")));
        for (int i = 0; i < 6; i++) {
            pool.add(instance("Q9" + i + "$a", "Jack Oakie", "Nomination"));
        }
        pool.add(instance("Q80$a", "Other", "Nomination"));
        pool.add(instance("Q81$a", "Other", "Nomination"));

        List<NameCollisions.ClassCollisions> byClass = NameCollisions.detect(pool, DECLARED);

        assertEquals(List.of("Nomination", "Planet"),
                byClass.stream().map(NameCollisions.ClassCollisions::className).toList(),
                "the class with the most shared labels first");
        assertEquals(2, byClass.getFirst().size());
        assertEquals(6, byClass.getFirst().worst(), "and how bad it gets within it");
        assertEquals(1, byClass.get(1).size());
        assertEquals(NameCollisions.Meaning.STATEMENT_REPETITION,
                byClass.getFirst().meaning());
        assertEquals(NameCollisions.Meaning.ENTITY_AMBIGUITY, byClass.get(1).meaning());
    }

    @Test void aPartIsAnInstanceLikeAnyOther() {
        // Owned components are instances of a declared class. Their labels derive from
        // their owners, so two owners with the same label give their parts the same one
        // — which is exactly the kind of thing this is here to show.
        List<NameCollisions.ClassCollisions> byClass = NameCollisions.detect(List.of(
                part("Q1", "Lee — Structured Name"),
                part("Q2", "Lee — Structured Name")), DECLARED);

        assertEquals("Name", byClass.getFirst().className());
        assertEquals(2, byClass.getFirst().collisions().getFirst().size());
        assertEquals(NameCollisions.Meaning.OWNED_REPETITION,
                byClass.getFirst().meaning());
    }

    @Test void theBiggestCollisionInAClassComesFirst() {
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(
                instance("Q1", "Pair", "Planet"), instance("Q2", "Pair", "Planet")));
        for (int i = 0; i < 5; i++) pool.add(instance("Q1" + i, "Crowd", "Planet"));
        for (int i = 0; i < 3; i++) pool.add(instance("Q2" + i, "Few", "Planet"));

        assertEquals(List.of("Crowd", "Few", "Pair"),
                NameCollisions.detect(pool, DECLARED).getFirst().collisions().stream()
                        .map(NameCollisions.Collision::name).toList());
        assertEquals(10, NameCollisions.instanceCount(
                NameCollisions.detect(pool, DECLARED)));
    }

    @Test void oneInstanceSeenTwiceIsOneInstance() {
        assertTrue(NameCollisions.detect(List.of(
                instance("Q308", "Mercury", "Planet"),
                instance("Q308", "Mercury", "Planet")), DECLARED).isEmpty(),
                "the same identifier appearing twice in the pool is not two instances");
    }

    @Test void anythingWithoutBothALabelAndAnIdIsSkippedRatherThanGrouped() {
        assertTrue(NameCollisions.detect(Arrays.asList(
                instance("Q1", null, "Planet"), instance("Q2", null, "Planet"),
                instance("Q3", "   ", "Planet"), instance("Q4", "   ", "Planet"),
                instance("", "Nameless", "Planet"), instance(null, "Nameless", "Planet"),
                null), DECLARED).isEmpty());
    }

    @Test void nothingToLookAtIsNoCollisions() {
        assertEquals(List.of(), NameCollisions.detect(null, DECLARED));
        assertEquals(List.of(), NameCollisions.detect(List.of(), DECLARED));
        assertEquals(List.of(), NameCollisions.detect(
                List.of(instance("Q1", "Mercury", "Planet")),
                (GeneratedProjectModel) null));
        assertEquals(0, NameCollisions.instanceCount(null));
        assertEquals(List.of(), NameCollisions.flatten(null));
    }

    private static WikidataDynamicObject instance(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    /** Reached through a field, never declared as anything. */
    private static WikidataDynamicObject value(String id, String name) {
        return new WikidataDynamicObject(id, name);
    }

    private static WikidataDynamicObject part(String ownerId, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(ownerId, name);
        o.type("Name");
        o.typeKey("Name@Person.structuredName");
        o.part(true);
        return o;
    }

    private static GeneratedProjectModel declared() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(clazz("Planet", ClassKind.SOURCE));
        model.addClass(clazz("Nomination", ClassKind.STATEMENT));
        model.addClass(clazz("Name", ClassKind.OWNED));
        return model;
    }

    /** A STATEMENT class is one that reifies statements, so it is made that way rather
     *  than stamped — classKind() derives it, and a test that set it directly would pass
     *  while production disagreed. */
    private static GeneratedClassModel clazz(String className, ClassKind kind) {
        GeneratedClassModel clazz = new GeneratedClassModel(className);
        if (kind == ClassKind.STATEMENT) {
            clazz.statementSource(new StatementClassSource("P1411"));
        } else {
            clazz.classKind(kind);
        }
        return clazz;
    }
}
