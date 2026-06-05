package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Single visible root class for now.
 */
public class GeneratedClassModel {

    private String className;

    private final FieldSourceMapping instanceMapping = new FieldSourceMapping();
    private final List<GeneratedFieldModel> fields = new ArrayList<>();

    public GeneratedClassModel() {
        this("GeneratedClass");
    }

    public GeneratedClassModel(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();

        fields.add(GeneratedFieldModel.nameField());
    }

    public String className() { return className; }
    public void className(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();
    }

    public FieldSourceMapping instanceMapping() { return instanceMapping; }

    public List<GeneratedFieldModel> fields() { return fields; }

    public GeneratedFieldModel addField(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        GeneratedFieldModel f =
                new GeneratedFieldModel(name, type, cardinality);

        fields.add(f);
        return f;
    }

    @Override
    public String toString() {
        return className;
    }
}
