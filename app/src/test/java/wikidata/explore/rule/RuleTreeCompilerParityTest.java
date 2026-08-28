package wikidata.explore.rule;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.filter.WikidataValueFilterOperator;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Phase-3 gate for the extraction compiler: compiling from the editable model
 * and from the compiled model must produce a RuleNode tree whose generated SPARQL
 * is byte-identical — so extraction (and thus the pool count) can't shift.
 */
class RuleTreeCompilerParityTest {

    /** A model exercising the compiler's branches: root membership + additional /
     *  excluded types, inline + entity + child-object fields, a value filter, an
     *  INVERT field (excluded from the plan), a discriminator and seeds. */
    private static GeneratedProjectModel model() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("constellations");

        GeneratedClassModel root = new GeneratedClassModel("Constellation");
        root.instanceMapping().sourceQid("Q8928");
        root.instanceMapping().propertyPid("P31");
        root.instanceMapping().additionalTypeQids().add("Q1053464");
        root.instanceMapping().excludedTypeQids().add("Q19478619");
        root.seedQids().add("Q8832");

        GeneratedFieldModel area =
                root.addField("area", FieldType.NUMBER, FieldCardinality.SINGLE);
        area.mapping().propertyPid("P2046");
        area.filterOperator(WikidataValueFilterOperator.LE);
        area.filterValue(500.0);

        GeneratedFieldModel mainStar =
                root.addField("mainStar", FieldType.ENTITY, FieldCardinality.SINGLE);
        mainStar.entityClassName("Star");
        mainStar.mapping().propertyPid("P138");

        GeneratedFieldModel stars =
                root.addField("stars", FieldType.ENTITY, FieldCardinality.COLLECTION);
        stars.entityClassName("Star");
        stars.mapping().propertyPid("P59");
        stars.mapping().productionKind(FieldProductionKind.CHILD_OBJECTS);

        GeneratedFieldModel back =
                root.addField("backref", FieldType.ENTITY, FieldCardinality.COLLECTION);
        back.entityClassName("Star");
        back.mapping().propertyPid("P59");
        back.mapping().productionKind(FieldProductionKind.INVERT);   // excluded from the plan
        project.rootClass(root);

        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.instanceMapping().sourceQid("Q523");
        star.instanceMapping().propertyPid("P31");
        star.discriminatorPid("P31");
        star.discriminatorQid("Q523");
        GeneratedFieldModel mag =
                star.addField("magnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        mag.mapping().propertyPid("P1215");
        // The forward reference Constellation.backref inverts. Without it the INVERT
        // could never produce anything, which the model validator now says out loud
        // instead of leaving it a silent no-op.
        GeneratedFieldModel inConstellation =
                star.addField("constellation", FieldType.ENTITY, FieldCardinality.SINGLE);
        inConstellation.entityClassName("Constellation");
        inConstellation.mapping().propertyPid("P59");
        project.addClass(star);

        return project;
    }

    @Test
    void generatedSparqlIsIdenticalEditableVsCompiled() {
        GeneratedProjectModel project = model();

        RuleNode fromEditable = RuleTreeCompiler.compileProject(project);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        RuleNode fromCompiled = RuleTreeCompiler.compileProject(compiled);

        assertEquals(
                RuleNodeQueryBuilder.valuesQuery(fromEditable),
                RuleNodeQueryBuilder.valuesQuery(fromCompiled),
                "valuesQuery must be byte-identical");

        assertEquals(
                RuleNodeQueryBuilder.fieldOptimizedValuesQuery(fromEditable),
                RuleNodeQueryBuilder.fieldOptimizedValuesQuery(fromCompiled),
                "fieldOptimizedValuesQuery must be byte-identical");
    }
}
