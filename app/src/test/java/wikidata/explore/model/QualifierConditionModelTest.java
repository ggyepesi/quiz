package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.QualifierCondition;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field's qualifier condition is saved with the model, survives the source-binding
 * projection on load (which would otherwise rewrite the mapping from a recipe that did
 * not know it), and is refused wherever no reader would honour it (#395).
 */
class QualifierConditionModelTest {

    private static final QualifierCondition V_BAND =
            new QualifierCondition("P1227", List.of("Q4892529"));

    private static GeneratedProjectModel stars() {
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.relation("P31", List.of("Q523"), false));
        GeneratedFieldModel magnitude =
                star.addField("apparentMagnitude", FieldType.NUMBER, FieldCardinality.SINGLE);
        magnitude.mapping().propertyPid("P1215");
        magnitude.mapping().qualifierCondition(V_BAND);
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(star);
        return project;
    }

    private static GeneratedFieldModel magnitude(GeneratedProjectModel project) {
        return project.findClass("Star").fields().stream()
                .filter(f -> f.name().equals("apparentMagnitude")).findFirst().orElseThrow();
    }

    private static List<String> errors(GeneratedProjectModel project) {
        return GeneratedProjectModelValidator.validate(project).problems().stream()
                .filter(p -> p.severity() == GeneratedProjectModelValidator.Severity.ERROR)
                .map(GeneratedProjectModelValidator.Problem::message).toList();
    }

    @Test void theConditionIsSavedAndLoadedWithTheField(@TempDir Path dir) throws Exception {
        File file = dir.resolve("stars.model.json").toFile();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(stars(), file);

        GeneratedProjectModel loaded = store.load(file);

        assertEquals(V_BAND, magnitude(loaded).mapping().qualifierCondition());
        assertEquals("P1227", FieldSourceBindings.binding(magnitude(loaded),
                datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .recipe().parameter(FieldSourceBindings.CONDITION_QUALIFIER));
    }

    @Test void aFieldWithoutAConditionSavesNone(@TempDir Path dir) throws Exception {
        GeneratedProjectModel project = stars();
        magnitude(project).mapping().qualifierCondition(null);
        File file = dir.resolve("stars.model.json").toFile();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        store.save(project, file);

        assertNull(magnitude(store.load(file)).mapping().qualifierCondition());
        assertTrue(!java.nio.file.Files.readString(file.toPath())
                .contains("\"qualifierCondition\""), "absent stays absent on disk");
    }

    @Test void aConditionIsPartOfWhereAnOwnerSiteAcquiresTheField() {
        FieldSourceMapping site = new FieldSourceMapping();
        site.copyAcquisitionFrom(magnitude(stars()).mapping());
        assertEquals(V_BAND, site.qualifierCondition());
    }

    @Test void aCompleteConditionOnAValueFieldIsValid() {
        assertEquals(List.of(), errors(stars()));
    }

    @Test void anIncompleteConditionIsAnError() {
        GeneratedProjectModel project = stars();
        magnitude(project).mapping().qualifierCondition(
                QualifierCondition.of("P1227", List.of()));
        assertTrue(errors(project).stream().anyMatch(m -> m.contains("allowed value")),
                errors(project).toString());
    }

    @Test void aConditionWhereNoReaderHonoursItIsAnError() {
        GeneratedProjectModel dbpedia = stars();
        magnitude(dbpedia).mapping().sourceType(FieldSourceType.DBPEDIA);
        assertTrue(errors(dbpedia).stream().anyMatch(m -> m.contains("Wikidata property")),
                errors(dbpedia).toString());

        GeneratedProjectModel related = stars();
        magnitude(related).mapping().productionKind(FieldProductionKind.CHILD_OBJECTS);
        assertTrue(errors(related).stream().anyMatch(m -> m.contains("related objects")),
                errors(related).toString());
    }
}
