package wikidata.explore.model;

import datasource.api.SourceRecipe;
import org.junit.jupiter.api.Test;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seed QIDs are not a second way of saying what a membership says.
 *
 * <p>They combine with it: with no bound the seeds ARE the members, and with one they
 * RESTRICT it — "the twelve Olympians, and only those that are gods". The rule tree
 * emits both, the membership triple and a {@code VALUES ?value} over the seeds, and the
 * editor's own tooltip offers the combination. So they are not the {@code EXPLICIT} mode
 * of {@link EntityBound} wearing another name, and folding them into it would delete a
 * capability rather than remove a duplicate.
 *
 * <p>What WAS wrong is the catalogue projection, which could only name one of them.
 */
class SeedsRestrictAMembershipTest {

    private static GeneratedProjectModel projectWith(GeneratedClassModel clazz) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.addClass(clazz);
        project.rootClass(clazz);
        return project;
    }

    @Test void aSeedRestrictsTheMembershipRatherThanReplacingIt() {
        GeneratedClassModel god = new GeneratedClassModel("Olympian");
        god.membership(EntityBound.relation("P31", List.of("Q22989102"), false));
        god.seedQids().add("Q41");
        god.addField("name", datasource.schema.FieldType.STRING,
                FieldCardinality.SINGLE);

        RuleNode node = RuleTreeCompiler.compileProject(projectWith(god));
        String sparql = RuleNodeQueryBuilder.valuesQuery(node);

        assertTrue(sparql.contains("wdt:P31 wd:Q22989102"),
                "the membership still bounds them:\n" + sparql);
        assertTrue(sparql.contains("VALUES ?value { wd:Q41 }"),
                "and the seed still restricts them:\n" + sparql);
    }

    /**
     * The catalogue has one operation per population, each mapping to ONE
     * PopulationRequest, so it cannot say "these, restricted to those". It used to
     * answer with the membership alone and drop the restriction without a word — and
     * assigning that answer back deleted the seeds.
     */
    @Test void theCatalogueDoesNotDescribeAPopulationItCannotExpress() {
        GeneratedClassModel both = new GeneratedClassModel("Olympian");
        both.membership(EntityBound.relation("P31", List.of("Q22989102"), false));
        both.seedQids().add("Q41");

        assertNull(both.populationSource(),
                "half of a population is not a description of it");
    }

    @Test void eachHalfOnItsOwnStillProjects() {
        GeneratedClassModel bounded = new GeneratedClassModel("God");
        bounded.membership(EntityBound.relation("P31", List.of("Q22989102"), false));
        SourceRecipe membership = bounded.populationSource();
        assertEquals("statement-membership", membership.operationId());
        assertEquals("Q22989102", membership.parameter("values"));

        GeneratedClassModel seeded = new GeneratedClassModel("Olympians");
        seeded.seedQids().add("Q41");
        seeded.seedQids().add("Q37340");
        SourceRecipe list = seeded.populationSource();
        assertEquals("seed-list", list.operationId());
        assertEquals("Q41,Q37340", list.parameter("ids"));
    }
}
