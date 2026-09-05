package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which entities a class's members are is one value.
 *
 * <p>It was three fields on the class's instance mapping — a property, one type QID, and
 * a set of further type QIDs OR-ed with it — and the split between "the type" and "the
 * extras" carried no meaning: every consumer re-joined them, and translating a list back
 * into that shape tore it apart as first-and-rest.
 */
class MembershipIsOneValueTest {

    private static GeneratedProjectModel model(String domain) throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + domain + "/" + domain + ".model.json"));
    }

    @Test void everyShippedModelKeepsItsMembershipInOnePlace() throws Exception {
        for (String domain : List.of("constellations", "mythology", "movies",
                "periodictable", "nobelprizes", "oscarnominations")) {
            GeneratedProjectModel project = model(domain);
            for (GeneratedClassModel clazz : project.classes()) {
                FieldSourceMapping mapping = clazz.instanceMapping();
                assertTrue(mapping.sourceQid().isBlank(),
                        domain + "/" + clazz.className()
                                + " still carries a membership type on its mapping");
                assertTrue(mapping.additionalTypeQids().isEmpty(),
                        domain + "/" + clazz.className()
                                + " still carries additional types on its mapping");
            }
        }
    }

    @Test void aShippedMembershipIsTheOneTheModelAlwaysHad() throws Exception {
        assertEquals(EntityBound.relation("P31", List.of("Q8928"), false),
                model("constellations").findClass("Constellation").membership());
        assertEquals(
                EntityBound.relation("P31", List.of("Q22988604", "Q22989102",
                        "Q12502038", "Q23015925"), false),
                model("mythology").findClass("Character").membership(),
                "four types in the order they were authored, not one plus three extras");
    }

    /** A copy that forgets it is a copy that populates nothing. */
    @Test void copyingAClassCarriesItsMembership() {
        GeneratedClassModel clazz = new GeneratedClassModel("Star");
        clazz.membership(EntityBound.relation("P31", List.of("Q523"), false));

        assertEquals(clazz.membership(), clazz.copy().membership());
    }

    /** Both rule-compiler paths read the same authored fact. */
    @Test void theCompiledClassCarriesTheAuthoredBound() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel clazz = new GeneratedClassModel("Star");
        clazz.membership(EntityBound.relation("P31", List.of("Q523", "Q6243"), false));
        project.addClass(clazz);
        project.rootClass(clazz);

        assertEquals(clazz.membership(),
                ProjectModelCompiler.compile(project).rootClass().membership());
    }

    /**
     * A class inherits its base's membership only while it declares none — the same
     * rule the effective mapping uses, asked of the bound rather than of whether one of
     * three fields happened to be blank.
     */
    @Test void aClassWithNoMembershipInheritsItsBase() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel base = new GeneratedClassModel("Star");
        base.membership(EntityBound.relation("P31", List.of("Q523"), false));
        GeneratedClassModel derived = new GeneratedClassModel("RedGiant");
        derived.baseClassName("Star");
        project.addClass(base);
        project.addClass(derived);

        assertEquals(base.membership(), derived.effectiveMembership(project));

        derived.membership(EntityBound.relation("P31", List.of("Q1051077"), false));
        assertEquals(derived.membership(), derived.effectiveMembership(project),
                "a declared membership is not inherited over");
    }

    /**
     * "Is this the plain membership case" has a name, and it is not a string comparison.
     *
     * <p>{@code equals("P31")} was answering it in four places, each of which had to
     * know that P31 means by-type and that a blank property means P31. It is a question
     * about the membership: a class whose members are "the entities that won this award"
     * has targets, qualifiers and a target field; one whose members are "the entities of
     * this type" has none of them.
     */
    @Test void whetherAMembershipIsRelationalIsAskedByName() {
        assertFalse(MembershipPattern.relational(MembershipPattern.DEFAULT_PROPERTY),
                "by type is the plain case");
        assertFalse(MembershipPattern.relational(""),
                "and so is an unstated property, which defaults to it");
        assertTrue(MembershipPattern.relational("P166"),
                "an award received relates members to something other than their type");

        GeneratedClassModel byType = new GeneratedClassModel("Star");
        byType.membership(EntityBound.relation("P31", List.of("Q523"), false));
        assertFalse(MembershipPattern.of(byType).relational());

        GeneratedClassModel byRelation = new GeneratedClassModel("Nominee");
        byRelation.membership(EntityBound.relation("P1411", List.of("Q102427"), false));
        assertTrue(MembershipPattern.of(byRelation).relational());
    }

    /**
     * The bound can hold subclass closure, which the three fields could not — and no run
     * performs it yet, so a model that asks for one is refused rather than quietly
     * narrowed to the flat backbone.
     */
    @Test void aClosureIsPersistableAndRefusedRatherThanIgnored() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel clazz = new GeneratedClassModel("Star");
        clazz.membership(EntityBound.relation("P31", List.of("Q523"), true));
        project.addClass(clazz);
        project.rootClass(clazz);

        assertTrue(clazz.membership().includeDescendants(), "the model can hold it");
        assertFalse(GeneratedProjectModelValidator.validate(project).valid(),
                "and no run performs it, so it is refused rather than dropped");
    }
}
