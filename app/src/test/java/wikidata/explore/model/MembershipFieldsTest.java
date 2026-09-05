package wikidata.explore.model;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipFieldsTest {

    private static GeneratedClassModel clazz() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        return c;
    }

    private static long fieldsFor(GeneratedClassModel c, String pid) {
        return c.fields().stream()
                .filter(f -> pid.equals(f.mapping().propertyPid())
                        && f.mapping().direction() == RuleDirection.ROOT_TO_ITEM)
                .count();
    }

    @Test void relationalMultiTargetGetsTargetAndType() {
        // Oscars: P1411 → category set.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P1411", List.of("Q102427", "Q103916"), false));

        List<String> added = MembershipFields.ensure(c);
        assertTrue(added.contains(MembershipFields.TARGET_FIELD), added.toString());
        assertTrue(added.contains(MembershipFields.TYPE_FIELD), added.toString());

        // target field restricted to the membership target set.
        GeneratedFieldModel target = c.fields().stream()
                .filter(f -> f.name().equals(MembershipFields.TARGET_FIELD))
                .findFirst().orElseThrow();
        assertEquals("P1411", target.mapping().propertyPid());
        assertTrue(target.mapping().allowedQids().containsAll(
                List.of("Q102427", "Q103916")));
        assertEquals(FieldType.ENTITY, target.type());
    }

    @Test void multiTypeMembershipGetsTypeOnly() {
        // Stars: P31 ∈ {star, red giant, variable star} — target IS the type.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P31", List.of("Q523", "Q1153690", "Q6243"), false));   // star

        List<String> added = MembershipFields.ensure(c);
        assertEquals(List.of(MembershipFields.TYPE_FIELD), added);
        assertEquals(1, fieldsFor(c, "P31"));
    }

    @Test void singleExactTypeGetsNothing() {
        // Plain P31 = Q5 membership: one type, no target set → no auto fields.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P31", List.of("Q5"), false));

        assertFalse(MembershipFields.appliesType(c));
        assertTrue(MembershipFields.ensure(c).isEmpty());
        assertEquals(0, fieldsFor(c, "P31"));   // no auto type field
    }

    @Test void doesNotDuplicateExistingEquivalentField() {
        // A hand-made `category` already mapping P1411 → don't add a second target.
        GeneratedClassModel c = clazz();
        c.membership(EntityBound.relation("P1411", List.of("Q102427"), false));
        GeneratedFieldModel existing = new GeneratedFieldModel(
                "category", FieldType.ENTITY, FieldCardinality.COLLECTION);
        existing.mapping().propertyPid("P1411");
        existing.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        c.fields().add(existing);

        List<String> added = MembershipFields.ensure(c);
        assertFalse(added.contains(MembershipFields.TARGET_FIELD), added.toString());
        assertEquals(1, fieldsFor(c, "P1411"));   // not doubled
    }
}
