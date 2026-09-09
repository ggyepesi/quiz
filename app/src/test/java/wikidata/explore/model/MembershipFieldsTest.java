package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipFieldsTest {

    private static GeneratedClassModel clazz() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        return c;
    }

    @Test void relationalMultiTargetMakesTargetAndTypeRelevantChoices() {
        // Oscars: P1411 → category set.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P1411", List.of("Q102427", "Q103916"), false));

        assertTrue(MembershipFields.appliesTarget(c));
        assertTrue(MembershipFields.appliesType(c));
        assertTrue(c.fields().isEmpty(), "advice does not author either field");
    }

    @Test void multiTypeMembershipMakesOnlyTypeRelevant() {
        // Stars: P31 ∈ {star, red giant, variable star} — target IS the type.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P31", List.of("Q523", "Q1153690", "Q6243"), false));   // star

        assertTrue(MembershipFields.appliesType(c));
        assertFalse(MembershipFields.appliesTarget(c));
        assertTrue(c.fields().isEmpty(), "advice does not author the type field");
    }

    @Test void singleExactTypeGetsNothing() {
        // Plain P31 = Q5 membership: one type, no target set → no auto fields.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P31", List.of("Q5"), false));

        assertFalse(MembershipFields.appliesType(c));
        assertTrue(c.fields().isEmpty());
    }
}
