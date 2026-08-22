package wikidata.explore.query.template.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.filter.WikidataValueFilterOperator;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's variable is its position in the FULL included-field list, so a skipped field
 * still advances the index.
 *
 * <p>Skipping exists because a value filter and an included field on the same property used to
 * bind it twice, and the cross-product only showed up once a named subquery forced it to
 * materialize — 51 seconds against 2 for the same root. Deduping them means one of the pair is
 * emitted and the other skipped.
 *
 * <p>Then the two halves disagreed about what "skipped" does to the numbering: the SELECT and
 * the extractor counted over the whole list, and the patterns were handed a pre-filtered one,
 * so everything after a skip shifted down by one. A Star root with P18 loaded 180 stars and no
 * images — {@code image} was selected as {@code ?image_1} and bound as {@code ?image_0}, a name
 * that matches nothing and reports nothing.
 */
class IncludedFieldVariableIndexTest {

    @Test void aSkippedFieldStillAdvancesTheIndexForTheOnesAfterIt() {
        List<RuleIncludedField> fields = magnitudeThenImage();

        String patterns = wherePatterns(fields, Set.of(fields.get(0)));

        assertTrue(patterns.contains("?" + RuleIncludedFieldSparql.variableName(fields.get(1), 1)),
                patterns);
        assertFalse(patterns.contains("?image_0"),
                "that name is what the extractor never finds: " + patterns);
    }

    /** What the caller must not do, kept here because the contract only says it in prose. */
    @Test void handingOverAPreFilteredListIsWhatShiftedTheNumbering() {
        List<RuleIncludedField> fields = magnitudeThenImage();

        String preFiltered = wherePatterns(List.of(fields.get(1)), Set.of());

        assertTrue(preFiltered.contains("?image_0"),
                "pre-filtering renumbers it, which is the bug: " + preFiltered);
        assertFalse(preFiltered.contains("?image_1"), preFiltered);
    }

    @Test void withNothingSkippedEveryFieldKeepsItsOwnPosition() {
        List<RuleIncludedField> fields = magnitudeThenImage();

        String patterns = wherePatterns(fields, Set.of());

        assertTrue(patterns.contains("?apparentMagnitude_0"), patterns);
        assertTrue(patterns.contains("?image_1"), patterns);
    }

    /**
     * The whole chain, on the model that reported it: a value-filtered field followed by an
     * image. The generated query has to BIND the variable the extractor will read.
     */
    @Test void aFieldFollowingAValueFilteredOneIsBoundUnderTheNameItIsReadBy() {
        RuleNode root = RuleTreeCompiler.compileProject(starWithFilteredMagnitudeAndImage());
        RuleIncludedField image = root.includedFields().stream()
                .filter(f -> "image".equals(f.fieldName())).findFirst().orElseThrow();
        int position = root.includedFields().indexOf(image);
        String readBy = RuleIncludedFieldSparql.variableName(image, position);

        String query = RuleNodeQueryBuilder.valuesQuery(root);

        assertEquals("image_1", readBy, "the value filter takes position 0");
        assertTrue(query.contains("?" + readBy + " ."), query);
        assertFalse(query.contains("?image_0"), query);
    }

    /** And the property filter still binds once, which is why anything is skipped at all. */
    @Test void theFilteredPropertyIsBoundOnceRatherThanTwice() {
        RuleNode root = RuleTreeCompiler.compileProject(starWithFilteredMagnitudeAndImage());

        String query = RuleNodeQueryBuilder.valuesQuery(root);

        assertEquals(1, query.split("wdt:P1215", -1).length - 1,
                "a second binding is the cross-product this dedup exists to remove: " + query);
    }

    private static String wherePatterns(
            List<RuleIncludedField> fields, Set<RuleIncludedField> skip) {
        StringBuilder sb = new StringBuilder();
        RuleIncludedFieldSparql.appendWherePatterns(sb, fields, false, skip);
        return sb.toString();
    }

    private static List<RuleIncludedField> magnitudeThenImage() {
        return RuleTreeCompiler.compileProject(starWithFilteredMagnitudeAndImage())
                .includedFields();
    }

    private static GeneratedProjectModel starWithFilteredMagnitudeAndImage() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.instanceMapping().sourceQid("Q523");
        star.instanceMapping().propertyPid("P31");

        GeneratedFieldModel magnitude =
                star.addField("apparentMagnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        magnitude.mapping().propertyPid("P1215");
        magnitude.filterOperator(WikidataValueFilterOperator.LE);
        magnitude.filterValue(6.0);

        GeneratedFieldModel image =
                star.addField("image", FieldType.IMAGE, FieldCardinality.SINGLE);
        image.mapping().propertyPid("P18");
        image.mapping().direction(RuleDirection.ROOT_TO_ITEM);

        project.rootClass(star);
        return project;
    }
}
