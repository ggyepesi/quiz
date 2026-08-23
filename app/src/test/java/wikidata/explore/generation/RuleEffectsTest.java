package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A generation's result already states its configuration implicitly — the instances are
 * typed by the generated class and the card shows the fields that class declares, so the
 * reader reads the config through the data. A Remap had no equivalent: it reported the
 * size of the pool, which barely moves, and offered every object in it as one flat list.
 *
 * <p>These are the same reading generalized. One bucket per configured rule, holding the
 * instances that rule accounts for, so "what did the Remap do" is answered by the rules
 * themselves rather than by a change report written alongside them.
 */
class RuleEffectsTest {

    private static WikidataDynamicObject nomination(String id, Object ceremony) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, id);
        o.type("Nomination");
        if (ceremony != null) o.put("ceremony", ceremony);
        return o;
    }

    private static GeneratedProjectModel model(FieldExpectation level) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        project.rootClass(new GeneratedClassModel("Root"));
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("Root", "P1411"));
        GeneratedFieldModel ceremony =
                nomination.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE);
        ceremony.mapping().qualifierPid("P805");
        ceremony.expectation(level);
        project.addClass(nomination);
        return project;
    }

    private static List<WikidataDynamicObject> pool() {
        WikidataDynamicObject edition = new WikidataDynamicObject("Q66707607", "95th");
        edition.type("Edition");
        return new ArrayList<>(List.of(
                nomination("n1", edition), nomination("n2", edition),
                nomination("n3", null), nomination("n4", null)));
    }

    @Test void aRuleBucketHoldsTheInstancesItAccountsForNotACount() {
        List<RuleEffects.Effect> effects =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool());

        assertEquals(1, effects.size());
        RuleEffects.Effect effect = effects.get(0);
        assertEquals(2, effect.size(), "the two with no ceremony");
        assertEquals(List.of("n3", "n4"), effect.instances().stream()
                        .map(objectview.Viewable::getIdentifier).toList(),
                "and you can open the bucket and act on them, which a number never was");
    }

    @Test void expectedNamesInstancesWithoutClaimingItChangedThem() {
        // The distinction an expectation was designed around. A report that called both
        // levels "affected" would say 2 things happened when nothing did.
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool()).get(0);

        assertEquals(RuleEffects.Kind.FLAGGED, effect.kind());
        assertTrue(effect.detail().contains("are kept"), effect.detail());
    }

    @Test void requiredIsReportedAsAChangeBecauseItDeletes() {
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.REQUIRED), pool()).get(0);

        assertEquals(RuleEffects.Kind.CHANGED, effect.kind());
        assertTrue(effect.detail().contains("dropped"), effect.detail());
    }

    @Test void evaluatingTheRulesChangesNothing() {
        // What lets the plan ask the same question as the result: a REQUIRED rule
        // reports what it would delete without deleting it.
        List<WikidataDynamicObject> pool = pool();

        RuleEffects.of(model(FieldExpectation.REQUIRED), pool);

        assertEquals(4, pool.size(), "a plan must not apply the run it is describing");
    }

    @Test void aRuleThatHoldsGetsNoBucket() {
        List<WikidataDynamicObject> complete = pool();
        complete.removeIf(o -> o.get("ceremony") == null);

        assertTrue(RuleEffects.of(model(FieldExpectation.EXPECTED), complete).isEmpty(),
                "a bucket you cannot open is worse than silence");
        assertEquals("", RuleEffects.summary(List.of()));
    }

    @Test void theTitleCarriesItsOwnCountSoATabReadsUnopened() {
        RuleEffects.Effect effect =
                RuleEffects.of(model(FieldExpectation.EXPECTED), pool()).get(0);

        assertEquals("Nomination.ceremony is expected (2)", effect.title());
    }

    @Test void noExpectationsMeansNoBucketsRatherThanAnEmptyOne() {
        assertTrue(RuleEffects.of(model(FieldExpectation.NONE), pool()).isEmpty());
        assertTrue(RuleEffects.of(null, pool()).isEmpty());
        assertTrue(RuleEffects.of(model(FieldExpectation.EXPECTED), null).isEmpty());
    }
}
