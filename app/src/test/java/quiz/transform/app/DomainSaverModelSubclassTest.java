package quiz.transform.app;

import domain.DomainModel;
import objectview.Viewable;
import objectview.field.FieldSchema;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A semantic subclass made in TransformApp becomes part of its owning model. */
class DomainSaverModelSubclassTest {

    @Test void newlyAssignedSubclassIsDeclaredWithItsBase() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        DomainModel transformed = new DomainModel() {
            @Override public List<String> types() {
                return List.of("Position", "PositionWithHolders");
            }
            @Override public String baseType(String type) {
                return "PositionWithHolders".equals(type) ? "Position" : null;
            }
            @Override public FieldSchema fieldSchema(String type) {
                return () -> List.of();
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of();
            }
            @Override public Class<? extends Viewable> universe() {
                return Viewable.class;
            }
        };

        DomainSaver.addSubclasses(model, transformed);

        GeneratedClassModel saved = model.findClass("PositionWithHolders");
        assertNotNull(saved);
        assertEquals("Position", saved.baseClassName());
    }
}
