package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.rule.RuleLabelConfig;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a class's configured label language means for which members it admits, and for
 * which of their names it keeps.
 *
 * <p>Requiring a label used to mean requiring an ENGLISH one. Historical Positions lost
 * 126 of 1,314 offices that way — {@code zağarcıbaşı}, {@code beylikçi},
 * {@code Alcaide de los Donceles}, {@code zemský lovčí} — every one of them named, none
 * of them named in English, and all of them exactly what a domain of historical
 * positions is for.
 *
 * <p>The domain therefore does not require one: the entity is kept, and named in English
 * when it has an English name. A few then carry no name, which is a curation job rather
 * than a reason to lose them. Admitting every language instead was tried and was worse —
 * this query binds one row per language, so an unrestricted label renamed 367 of 367
 * sampled offices that HAD an English name into whichever language came back first.
 */
class MembershipLabelLanguageTest {

    @Test void aListOfLanguagesIsARestrictionRatherThanOneBogusTag() {
        // Inlined as a single tag this read LANG(?l) = "en,tr,es" — a language no label
        // carries — so the class generated empty and said nothing about why.
        String sparql = membershipQuery("en,tr", true);

        assertTrue(sparql.contains("LANG(?valueLabel_s) IN (\"en\", \"tr\", \"mul\")"),
                sparql);
        assertFalse(sparql.contains("\"en,tr\""), sparql);
        assertFalse(sparql.contains("= \"en,tr\""), sparql);
    }

    @Test void asingleLanguageStillKeepsItsMulFallback() {
        String sparql = membershipQuery("en", true);

        assertTrue(sparql.contains("LANG(?valueLabel_s) IN (\"en\", \"mul\")"), sparql);
    }

    @Test void aLanguageSettingThatCannotMatchIsReportedRatherThanRunEmpty() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> membershipQuery("en tr", true));

        assertTrue(refused.getMessage().contains("Not a language code"),
                refused.getMessage());
        assertEquals(true, refused.getMessage().contains("\"any\""),
                "and it says what a valid setting looks like: " + refused.getMessage());
    }

    @Test void theRootMembershipQueryStillRestrictsWhenALanguageIsNamed() {
        String sparql = rootQuery("en");

        assertTrue(sparql.contains("FILTER(LANG(?valueLabel) IN (\"en\", \"mul\"))"),
                sparql);
        assertFalse(sparql.contains("NOT EXISTS"),
                "a named language is a restriction, not a preference: " + sparql);
    }

    @Test void notRequiringALabelKeepsTheEntityAndStillNamesItInEnglish() {
        // The chosen shape, and the simplest one that does not drop anybody: the label
        // is bound when it is English, and an entity without one is kept regardless.
        // A handful of offices then render as a bare QID, which is a curation job
        // rather than a reason to lose them.
        String sparql = rootQuery("en", false);

        assertTrue(sparql.contains("OPTIONAL"), sparql);
        assertTrue(sparql.contains("?value rdfs:label ?valueLabel"), sparql);
        assertTrue(sparql.contains("LANG(?valueLabel) IN (\"en\", \"mul\")"),
                "named in English where there is one: " + sparql);
    }

    private static String rootQuery(String language) {
        return rootQuery(language, true);
    }

    private static String rootQuery(String language, boolean requireLabel) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q114962596"), false));
        project.rootClass(position);
        RuleNode node = RuleTreeCompiler.compileClass(position, project);
        node.labelConfig(new RuleLabelConfig(requireLabel, language));
        return RuleNodeQueryBuilder.valuesQuery(node);
    }

    private static String membershipQuery(String language, boolean requireLabel) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q114962596"), false));
        project.rootClass(position);
        RuleNode node = RuleTreeCompiler.compileClass(position, project);
        node.labelConfig(new RuleLabelConfig(requireLabel, language));
        return RuleNodeQueryBuilder.childQueryForParent(node, "Q114962596", 50);
    }
}
