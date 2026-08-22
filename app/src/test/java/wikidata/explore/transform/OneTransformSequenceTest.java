package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
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
