package domain.compile.rule;

import java.util.List;
import domain.config.EntityBound;
import org.junit.jupiter.api.Test;
import domain.compile.CompiledClass;
import domain.compile.CompiledProjectModel;
import domain.compile.ProjectModelCompiler;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.SubclassCondition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubclassDiscriminatorTest {

    private static GeneratedProjectModel oscarProject() {
        GeneratedClassModel base = new GeneratedClassModel("Oscarnominations");
        base.membership(EntityBound.relation("P1411", List.of("Q102427", "Q103916"), false));   // nominated for

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(base);
        return p;
    }

    @Test void subclassIntersectsInheritedMembershipWithDiscriminator() {
        GeneratedProjectModel p = oscarProject();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.baseClassName("Oscarnominations");   // inherit the nominee membership
        person.discriminatorQid("Q5");              // … AND instance-of human (P31 default)
        p.addClass(person);

        RuleNode node = RuleTreeCompiler.compileClass(person, p);

        // Inherited relational membership (P1411 → the categories) …
        assertEquals("P1411", RuleNode.cleanPid(node.propertyPid()));
        assertTrue(node.allSourceQids().contains("Q102427"), node.allSourceQids().toString());
        // … intersected with the P31 discriminator.
        assertTrue(node.hasMembershipFilter());
        assertEquals("P31", RuleNode.cleanPid(node.membershipPid()));
        assertEquals("Q5", RuleNode.cleanQid(node.membershipQid()));
    }

    @Test void baseWithoutDiscriminatorHasNoExtraFilter() {
        GeneratedProjectModel p = oscarProject();
        RuleNode node = RuleTreeCompiler.compileClass(p.rootClass(), p);
        assertFalse(node.hasMembershipFilter());
    }

    @Test void discriminatorEmittedInGeneratedSparql() {
        GeneratedProjectModel p = oscarProject();
        GeneratedClassModel film = new GeneratedClassModel("Film");
        film.baseClassName("Oscarnominations");
        film.discriminatorQid("Q11424");
        p.addClass(film);

        RuleNode node = RuleTreeCompiler.compileClass(film, p);
        assertFalse(node.membershipFilterIncludesDescendants());
        String sparql = wikidata.query.template.rule.RuleNodeQueryBuilder
                .valuesQuery(node);
        assertTrue(sparql.contains("wdt:P31 wd:Q11424"), sparql);
    }

    @Test void discriminatorPropertyCanBeNonP31() {
        // Generic axis: discriminate on a relation other than instance-of.
        GeneratedProjectModel p = oscarProject();
        GeneratedClassModel byCat = new GeneratedClassModel("BestActorNom");
        byCat.baseClassName("Oscarnominations");
        byCat.discriminatorPid("P1411");        // nominated for …
        byCat.discriminatorQid("Q103916");      // … Best Actor
        p.addClass(byCat);

        RuleNode node = RuleTreeCompiler.compileClass(byCat, p);
        assertEquals("P1411", RuleNode.cleanPid(node.membershipPid()));
        assertEquals("Q103916", RuleNode.cleanQid(node.membershipQid()));
        String sparql = wikidata.query.template.rule.RuleNodeQueryBuilder
                .valuesQuery(node);
        assertTrue(sparql.contains("wdt:P1411 wd:Q103916"), sparql);
    }

    @Test void descendantDiscriminatorUsesTheSameClosureInBothCompilerPaths() {
        GeneratedProjectModel p = oscarProject();
        GeneratedClassModel moon = new GeneratedClassModel("Moon");
        moon.baseClassName("Oscarnominations");
        moon.subclassCondition(SubclassCondition.propertyValue(
                "P31", "Q109645860", true));
        p.addClass(moon);

        RuleNode editableNode = RuleTreeCompiler.compileClass(moon, p);
        assertTrue(editableNode.membershipFilterIncludesDescendants());
        assertDescendantFilter(
                wikidata.query.template.rule.RuleNodeQueryBuilder
                        .valuesQuery(editableNode));

        CompiledProjectModel compiledProject = ProjectModelCompiler.compile(p);
        CompiledClass compiledMoon = compiledProject.findClass("Moon").orElseThrow();
        assertTrue(compiledMoon.discriminatorIncludesDescendants());
        RuleNode compiledNode = RuleTreeCompiler.compileClass(
                compiledMoon, compiledProject);
        assertTrue(compiledNode.membershipFilterIncludesDescendants());
        assertDescendantFilter(
                wikidata.query.template.rule.RuleNodeQueryBuilder
                        .valuesQuery(compiledNode));
    }

    private static void assertDescendantFilter(String sparql) {
        assertTrue(sparql.contains(
                "?value wdt:P31 ?membershipFilterValue"), sparql);
        assertTrue(sparql.contains(
                "?membershipFilterValue wdt:P279* wd:Q109645860"), sparql);
        assertFalse(sparql.contains("?value wdt:P31 wd:Q109645860"), sparql);
    }
}
