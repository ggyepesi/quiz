package wikidata.explore.query.logical;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.ClassDependencies;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.ProductionChain;
import wikidata.explore.transform.OwnedComponents;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An owned class is sampled by its OWNERS, and one pass is enough.
 *
 * <p>It has no members of its own: a part is made per owning instance, carrying that
 * owner's identifier. So the bound goes where the population is. And because a part is
 * produced FROM one owner rather than reduced from many, bounding the owners makes the
 * sample small and never makes a part wrong — the difference from an aggregate, which
 * needs a second pass for exactly that reason.
 */
class OwnedSampleIsWholePartsTest {

    private static GeneratedProjectModel person() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/person/person.model.json"));
    }

    /** The population the bound sits on is the owner's, and the chain says so. */
    @Test void theOwnersAreWhatGetsSampled() throws Exception {
        GeneratedProjectModel project = person();
        GeneratedClassModel name = project.findClass("Name");

        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(name, project));
        ProductionChain chain = ProductionChain.of(name, project);
        assertTrue(chain.resolved(), chain.refusal());
        assertEquals("Person", chain.population().className());
        assertTrue(chain.has(ClassDependencies.Kind.OWNED));
    }

    /**
     * Fewer owners give fewer parts, and the parts are the same parts.
     *
     * <p>This is what makes one pass enough. Compare with an aggregate, where the same
     * bound applied to the source produces groups missing most of their members.
     */
    @Test void boundingTheOwnersDoesNotChangeAnyPart() throws Exception {
        GeneratedProjectModel project = person();

        List<WikidataDynamicObject> all = new ArrayList<>(owners("Q1", "Q2", "Q3"));
        OwnedComponents.apply(project, all, null, null).addTo(all);
        List<String> whole = partIdentifiers(all);
        assertEquals(3, whole.size(), "one part per owner");

        List<WikidataDynamicObject> bounded = new ArrayList<>(owners("Q1", "Q2"));
        OwnedComponents.apply(project, bounded, null, null).addTo(bounded);
        List<String> small = partIdentifiers(bounded);

        assertEquals(2, small.size(), "a smaller sample");
        assertEquals(whole.subList(0, 2), small,
                "and the same parts — a part is composed from ONE owner, so leaving "
                        + "owners out cannot leave anything out of a part");
    }

    /** Only the requested class's parts are shown, not the owners they came from. */
    @Test void theOwnersThemselvesAreNotTheSample() throws Exception {
        GeneratedProjectModel project = person();
        List<WikidataDynamicObject> pool = new ArrayList<>(owners("Q1", "Q2"));
        OwnedComponents.apply(project, pool, null, null).addTo(pool);

        List<WikidataDynamicObject> parts =
                pool.stream().filter(o -> o.directClassNames().contains("Name")).toList();

        assertEquals(2, parts.size());
        assertTrue(parts.stream().noneMatch(p -> p.directClassNames().contains("Person")),
                "a sample of Name that showed People would be showing the wrong class");
    }

    /** Nothing produces it: there are no owners, so there is nothing to sample from. */
    @Test void anUnproducedOwnedClassSaysNothingMakesIt() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel orphan = new GeneratedClassModel("Name");
        orphan.ownedClass(true);
        project.addClass(orphan);

        ProductionChain chain = ProductionChain.of(orphan, project);

        assertFalse(chain.resolved());
        assertTrue(chain.refusal().contains("Nothing produces Name"), chain.refusal());
    }

    /**
     * Produced from two kinds of entity: the sites disagree about the population.
     *
     * <p>Two sites on the SAME owner agree perfectly and are fine; it is the owner's
     * kind that matters. Taking whichever was declared first would sample one population
     * and label it with a class the other also produces.
     */
    @Test void anOwnedClassWithTwoOwnerKindsSaysWhichIsAmbiguous() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel part = new GeneratedClassModel("Name");
        part.ownedClass(true);
        project.addClass(part);
        project.addClass(ownerOf("Person", "fullname"));
        project.addClass(ownerOf("Organisation", "legalName"));

        String reason = ProductionChain.of(part, project).refusal();

        assertTrue(reason.contains("Person") && reason.contains("Organisation"), reason);
        assertTrue(reason.contains("one population has to speak for it"), reason);
    }

    private static GeneratedClassModel ownerOf(String className, String fieldName) {
        GeneratedClassModel owner = new GeneratedClassModel(className);
        var field = owner.addField(fieldName, datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.SINGLE);
        field.entityClassName("Name");
        field.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.OWNED_COMPONENT);
        return owner;
    }

    private static List<WikidataDynamicObject> owners(String... qids) {
        List<WikidataDynamicObject> people = new ArrayList<>();
        for (String qid : qids) {
            WikidataDynamicObject person = new WikidataDynamicObject(qid, "Person " + qid);
            person.type("Person");
            people.add(person);
        }
        return people;
    }

    private static List<String> partIdentifiers(List<WikidataDynamicObject> pool) {
        return pool.stream().filter(o -> o.directClassNames().contains("Name")).toList().stream()
                .map(WikidataDynamicObject::getIdentifier).toList();
    }
}
