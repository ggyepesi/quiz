package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #97: Generate and Remap are the same work, differing only in whether the data was just
 * downloaded — and each carried its own copy of the transform sequence. The copies
 * drifted: a projection reached one path and not the other (#93), and Generate ran its
 * restriction and invert stages over the object registry, which does not contain the
 * records the reify had just created, so a rule declared on a reified class applied on
 * Remap and silently did nothing on Generate.
 */
class OneTransformSequenceTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    /** A statement class whose value field admits only the Oscar category. */
    private static CompiledProjectModel modelRestrictingCategory() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");

        GeneratedClassModel source = new GeneratedClassModel("OscarNominations");
        source.instanceMapping().propertyPid("P1411");
        project.addClass(source);
        project.rootClass(source);

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nomination.instanceMapping().propertyPid("P1411");
        wikidata.explore.model.GeneratedFieldModel category =
                nomination.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        category.mapping().allowedQids().add("Q106301");
        // Declared, not implied: reification used to invent a "source" field for the
        // subject, so fixtures inherited one they never wrote down. A statement now has
        // to say where its subject goes, and this is the field it was always using.
        nomination.addField("source", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nomination.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nomination));
        project.addClass(nomination);

        return ProjectModelCompiler.compile(project);
    }

    /** The pool as qualifier-load leaves it: statements hanging off the member under the
     *  internal list field, not yet promoted to records. */
    private static List<WikidataDynamicObject> loadedPool() {
        WikidataDynamicObject oscar = obj("Q106301", "Best Supporting Actress", "Category");
        WikidataDynamicObject grammy = obj("Q47170", "Grammy", "Category");

        WikidataDynamicObject onOscar = obj("Q38195662$a", "x", "__Nomination");
        onOscar.put("category", oscar);
        WikidataDynamicObject onGrammy = obj("Q38195662$b", "x", "__Nomination");
        onGrammy.put("category", grammy);       // shares P1411, is not an Oscar

        WikidataDynamicObject member = obj("Q38195662", "Hong Chau", "OscarNominations");
        member.put("__Nomination", new ArrayList<>(List.of(onOscar, onGrammy)));

        return new ArrayList<>(List.of(member, oscar, grammy));
    }

    @Test void aRuleDeclaredOnAReifiedClassReachesTheRecordsThatClassProduces() {
        List<WikidataDynamicObject> pool = loadedPool();

        StatementTransforms.Result result = StatementTransforms.apply(
                null, modelRestrictingCategory(), pool, records -> Map.of(), null);

        assertEquals(2, result.reified().size(), "both statements are promoted");
        List<Object> categories = result.reified().stream()
                .map(record -> record.get("category")).toList();
        assertTrue(categories.contains(null),
                "the Grammy category is not in the allowed set, so it is pruned: "
                        + categories);
    }

    @Test void theReifiedRecordsAreInThePoolTheLaterStagesSee() {
        // The precise reason Generate diverged: it ran the later stages over the
        // registry, which the reify had never added the new records to.
        List<WikidataDynamicObject> pool = loadedPool();

        StatementTransforms.Result result = StatementTransforms.apply(
                null, modelRestrictingCategory(), pool, records -> Map.of(), null);

        for (WikidataDynamicObject record : result.reified()) {
            assertTrue(pool.contains(record),
                    "a record the reify created must be in the pool the next stage reads");
        }
    }

    @Test void internalPlumbingDoesNotSurviveIntoTheServedPool() {
        List<WikidataDynamicObject> pool = loadedPool();

        StatementTransforms.apply(
                null, modelRestrictingCategory(), pool, records -> Map.of(), null);

        for (WikidataDynamicObject object : pool) {
            assertFalse(object.dynamicFields().containsKey("__Nomination"),
                    object.getIdentifier() + " still carries the raw statement list");
        }
    }

    @Test void whatARemapSaysItSkippedIsWhatItActuallySkips() {
        // The plan named field-value restrictions and inverts as unavailable on a loaded
        // snapshot while applyIdempotent was running both — so a user was told their
        // restriction edits had not been applied when they had. A stage is reported as
        // replayable exactly when it carries the call, so the two cannot disagree.
        List<String> reportedSkipped = StatementTransforms.unavailableOnLoadedSnapshot();

        for (StatementTransforms.Stage stage : StatementTransforms.Stage.values()) {
            assertEquals(!stage.snapshotReplayable(),
                    reportedSkipped.contains(stage.displayName()),
                    stage + " is reported as skipped but not as unreplayable, or vice versa");
        }
        assertEquals(List.of("reify", "companion match"), reportedSkipped,
                "only the two genuinely non-idempotent stages are skipped");
    }

    @Test void theReplayableSubsetRunsEverythingItClaimsAndNothingItDoesNot() {
        // Behavioural, not by name: the restriction takes effect (so the stage really
        // ran) and no record is created (so reify really did not).
        List<WikidataDynamicObject> pool = loadedPool();
        StatementTransforms.apply(
                null, modelRestrictingCategory(), pool, records -> Map.of(), null);
        int afterFullPass = pool.size();

        // Put the disallowed category back, then replay as a loaded snapshot would.
        WikidataDynamicObject record = pool.stream()
                .filter(o -> "Nomination".equals(o.typeName()))
                .filter(o -> o.get("category") == null)
                .findFirst().orElseThrow();
        record.put("category", obj("Q47170", "Grammy", "Category"));

        StatementTransforms.applyIdempotent(modelRestrictingCategory(), pool, null);

        assertEquals(afterFullPass, pool.size(),
                "the replay must create no records — reify is not replayable");
        assertNull(record.get("category"),
                "and the value restriction must still take effect, which is exactly what "
                        + "the plan used to claim had been skipped");
    }

    @Test void neitherPathSpellsTheSequenceForItself() throws IOException {
        // The forcing part: a stage added to one path and not the other is how the
        // sequences drifted, so neither path may name the stages any more.
        List<String> stages = List.of(
                "ModelStatementReifications.reify",
                "FieldValueRestrictions.apply",
                "ModelInverts.apply",
                "ModelYearProjections.apply",
                "CompanionMatch.applyWithSets");

        for (String path : List.of(
                "src/main/java/wikidata/explore/query/logical/GenerateDomainQuery.java",
                "src/main/java/wikidata/explore/generation/GenerationPipeline.java")) {
            String source = Files.readString(Path.of(path));
            for (String stage : stages) {
                assertFalse(source.contains(stage),
                        path + " calls " + stage + " directly. Add the stage to "
                                + "StatementTransforms so BOTH Generate and Remap run it.");
            }
        }
    }
}
