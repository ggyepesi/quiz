package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

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

        ensureNameField();
    }

    public String className() { return className; }

    public void className(String className) {
        this.className =
                className == null || className.isBlank()
                        ? "GeneratedClass"
                        : className.trim();
    }

    public FieldSourceMapping instanceMapping() {
        return instanceMapping;
    }

    public List<GeneratedFieldModel> fields() {
        ensureNameField();
        return fields;
    }

    public void ensureNameField() {
        for (GeneratedFieldModel f : fields) {
            if (f != null && f.isNameField()) {
                return;
            }
        }

        fields.add(0, GeneratedFieldModel.nameField());
    }

    public GeneratedClassModel copy() {
        GeneratedClassModel c = new GeneratedClassModel(className);

        c.instanceMapping.copyFrom(instanceMapping);

        c.fields.clear();
        for (GeneratedFieldModel f : fields) {
            if (f != null) {
                c.fields.add(f.copy());
            }
        }

        return c;
    }

    public GeneratedFieldModel addField(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        ensureNameField();

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