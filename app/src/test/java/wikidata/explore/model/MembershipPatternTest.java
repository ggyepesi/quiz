package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MembershipPatternTest {

    private static GeneratedClassModel clazz() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        return c;
    }

    @Test void multiTargetRelation() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P1411");
        c.instanceMapping().additionalTypeQids().add("Q102427");
        c.instanceMapping().additionalTypeQids().add("Q103916");
        assertEquals(MembershipPattern.MULTI_TARGET_RELATION, MembershipPattern.of(c));
        assertEquals("Multi-target relation (P1411 → 2)", MembershipPattern.describe(c));
    }

    @Test void multiType() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().sourceQid("Q523");
        c.instanceMapping().additionalTypeQids().add("Q6243");
        assertEquals(MembershipPattern.MULTI_TYPE, MembershipPattern.of(c));
    }

    @Test void singleType() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().sourceQid("Q5");
        assertEquals(MembershipPattern.SINGLE_TYPE, MembershipPattern.of(c));
        assertEquals("Single type (Q5)", MembershipPattern.describe(c));
    }

    @Test void singleTargetRelation() {
        GeneratedClassModel c = clazz();
        c.instanceMapping().propertyPid("P166");
        c.instanceMapping().sourceQid("Q35637");
        assertEquals(MembershipPattern.SINGLE_TARGET_RELATION, MembershipPattern.of(c));
    }

    @Test void seededWhenNoRelation() {
        GeneratedClassModel c = clazz();
        c.seedQids().add("Q1");
        c.seedQids().add("Q2");
        assertEquals(MembershipPattern.SEEDED, MembershipPattern.of(c));
    }

    @Test void unconfigured() {
        assertEquals(MembershipPattern.UNCONFIGURED, MembershipPattern.of(clazz()));
    }

    @Test void reifiedDiscoveredWithSelection() {
        GeneratedClassModel c = clazz();
        StatementClassSource s = new StatementClassSource("P1411");
        s.valueSelectionName("OscarCategories");
        c.statementSource(s);
        assertEquals(MembershipPattern.REIFIED, MembershipPattern.of(c));
        assertEquals("Reified statements (P1411 · discovered → Selection 'OscarCategories')",
                MembershipPattern.describe(c));
    }

    @Test void reifiedFromSourceClass() {
        GeneratedClassModel c = clazz();
        c.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        assertEquals(MembershipPattern.REIFIED, MembershipPattern.of(c));
        assertEquals("Reified statements (P1411 of OscarNominations)",
                MembershipPattern.describe(c));
    }
}
