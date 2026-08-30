package wikidata.explore.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceType;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source read after extraction names no Wikidata property, so nothing it names may
 * reach a generated query.
 *
 * <p>The rule was written as a list of source types — in two places in the compiler, and
 * a name shorter in the field editor, which is how an infobox parameter reached a routine
 * that only understands Pxx. {@link FieldSourceType#filledAfterExtraction()} answers it
 * now, and this test walks the enum rather than naming the members: a new post-extraction
 * source fails here until the compiler skips it too.
 */
class PostExtractionSourcesStayOutOfTheRuleTreeTest {

    @Test void noPostExtractionSourceIsCompiledIntoAGeneratedQuery() {
        for (FieldSourceType type : FieldSourceType.values()) {
            if (!type.filledAfterExtraction()) continue;

            String query = RuleNodeQueryBuilder.valuesQuery(
                    RuleTreeCompiler.compileProject(modelWithPrimary(type)));

            assertFalse(query.contains("Infobox film.country"), type + " leaked its key");
            assertFalse(query.contains("populationTotal"), type + " leaked its property");
            assertTrue(query.contains("P31"), type + " must not cost the membership backbone");
        }
    }

    /** The PRIMARY mapping, not the fallback: a post-extraction source is selectable as a
     *  field's own source, and that is exactly the case the compiler used to mishandle. */
    private static GeneratedProjectModel modelWithPrimary(FieldSourceType type) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("films");
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().sourceQid("Q11424");
        movie.instanceMapping().propertyPid("P31");
        GeneratedFieldModel country =
                movie.addField("country", FieldType.TEXT, FieldCardinality.SINGLE);
        country.mapping().sourceType(type);
        country.mapping().propertyPid(type == FieldSourceType.WIKIPEDIA_INFOBOX
                ? "Infobox film.country" : "populationTotal");
        project.rootClass(movie);
        return project;
    }
}
