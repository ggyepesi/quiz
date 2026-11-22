package domain.build;

import org.junit.jupiter.api.Test;
import domain.compile.ProjectModelCompiler;
import domain.runtime.WikidataDynamicObject;
import domain.config.FieldCardinality;
import domain.datasource.schema.FieldType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.RoleKind;
import domain.config.StatementClassSource;
import domain.compile.transform.OwnedComponents;
import domain.compile.transform.StatementTransforms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a rule's own record of what it did survives the whole way to the workflow result.
 *
 * <p>Every audit added so far was tested where it was produced and where it was
 * rendered, and not across the joins in between — which is exactly where the last two
 * defects lived. A REQUIRED expectation reported nothing because the result re-asked the
 * finished pool; owned parts were duplicated forever because a copy dropped one flag.
 * Both passed every test in existence.
 *
 * <p>So these assert the join: a real transform produces a finding, the finding reaches
 * a {@link GenerationRun}, and {@link RuleEffects} can still name the instances from
 * there. Nothing here is synthetic except the fixture.
 */
class AuditReachesTheRunTest {

    private static WikidataDynamicObject obj(String id, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(id, name);
        o.type(type);
        return o;
    }

    /** A person-rooted and a work-rooted copy of one shared nomination: the work's is a
     *  denormalized duplicate, witnessed by the person's. */
    private static GeneratedProjectModel model() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel source = new GeneratedClassModel("Member");
        source.instanceMapping().propertyPid("P1411");
        project.addClass(source);
        project.rootClass(source);

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("Member", "P1411"));
        nomination.instanceMapping().propertyPid("P1411");
        nomination.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                  .mapping().productionKind(
                          domain.config.FieldProductionKind.STATEMENT_OBJECT);
        GeneratedFieldModel nominee =
                nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.mapping().qualifierPid("P2453");
        nominee.mapping().roleKind(RoleKind.IDENTITY);
        nominee.mapping().missingQualifierPolicy(
                domain.config.MissingQualifierPolicy.STATEMENT_SUBJECT);
        GeneratedFieldModel forWork =
                nomination.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        forWork.mapping().qualifierPid("P1686");
        forWork.mapping().missingQualifierPolicy(
                domain.config.MissingQualifierPolicy.STATEMENT_SUBJECT);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nomination.canonical().keyFields().addAll(
                domain.config.StatementIdentity.structuralKey(nomination));
        project.addClass(nomination);
        return project;
    }

    private static List<WikidataDynamicObject> loadedPool() {
        WikidataDynamicObject category = obj("Qcat", "Best Supporting Actress", "Category");
        WikidataDynamicObject film = obj("Qfilm", "The Whale", "Member");
        WikidataDynamicObject person = obj("Qperson", "Hong Chau", "Member");

        WikidataDynamicObject bare = obj("Qfilm$bare", "x", "Statement");
        bare.put("category", category);                  // no qualifiers at all
        film.put("__Nomination", new ArrayList<>(List.of(bare)));

        WikidataDynamicObject real = obj("Qperson$real", "x", "Statement");
        real.put("category", category);
        real.put("forWork", film);                       // a REAL reference to the film
        person.put("__Nomination", new ArrayList<>(List.of(real)));

        return new ArrayList<>(List.of(category, film, person));
    }

    @Test void aRealReifyDecisionSurvivesIntoTheRunAndBackOutAsInstances() {
        List<WikidataDynamicObject> pool = loadedPool();

        StatementTransforms.Result transformed = StatementTransforms.apply(
                model(), ProjectModelCompiler.compile(model()), pool,
                records -> Map.of(), null);

        assertFalse(transformed.selfReferenceFindings().isEmpty(),
                "the transform must actually record a decision, or this proves nothing");

        GenerationRun run = new GenerationRun(
                model(), 1, null, pool, null, List.of(), null, List.of(),
                GenerationRun.Quality.completeQuality(), List.of(),
                transformed.selfReferenceFindings());

        assertTrue(run.selfReferenceAudit().executed());
        List<RuleEffects.Effect> effects = RuleEffects.fromRun(
                run.fieldCoverage(), run.selfReferenceAudit(),
                run.ownedCompositionAudit(), run.kindClassificationAudit(),
                run.projectionAudit());

        assertFalse(effects.isEmpty(),
                "a decision the transform recorded has to be reportable from the run");
        assertTrue(effects.stream().anyMatch(e -> e.rule().contains("Self-referential")),
                effects.stream().map(RuleEffects.Effect::rule).toList().toString());
    }

    @Test void aRunThatNeverReifiedSaysSoRatherThanLookingClean() {
        // The loaded-snapshot Remap. Silence about a rule that did not run must not be
        // reported the same way as a rule that ran and found nothing.
        GenerationRun run = new GenerationRun(
                model(), 1, null, loadedPool(), null, List.of(), null);

        assertFalse(run.selfReferenceAudit().executed());
        assertEquals("Not run in this operation",
                run.selfReferenceAudit().description());
        assertTrue(RuleEffects.fromRun(run.fieldCoverage(), run.selfReferenceAudit(),
                run.ownedCompositionAudit(), run.kindClassificationAudit(),
                run.projectionAudit()).isEmpty());
    }

    @Test void ownedCompositionReportsWhatItManufacturedNotWhatItHolds() {
        // Composition returns every part it has AND only the ones it just made; the
        // audit must carry the second, since reuse is the healthy outcome and creation
        // is the event. Getting this backwards is what made #112 invisible.
        GeneratedProjectModel project = model();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel structuredName =
                person.addField("structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.mapping().productionKind(
                domain.config.FieldProductionKind.OWNED_COMPONENT);
        structuredName.entityClassName("Name");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.classKind(domain.config.ClassKind.OWNED);
        project.addClass(person);
        project.addClass(name);

        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(obj("Q1", "Somebody", "Person")));

        OwnedComponents.Result first = OwnedComponents.apply(project, pool, pool, null);
        first.addTo(pool);
        OwnedComponents.Result again = OwnedComponents.apply(project, pool, pool, null);

        assertEquals(first.created(), first.createdComponents().size(),
                "what it made and how many it made must agree");
        assertTrue(again.createdComponents().isEmpty(),
                "a second pass recognises its own work and manufactures nothing: "
                        + again.created() + " created");
        assertFalse(again.components().isEmpty(),
                "while still holding the parts, which is why the two lists differ");
    }
}
