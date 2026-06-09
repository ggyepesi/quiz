package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

public class GeneratedFieldModel {

    private String name;
    private FieldType type = FieldType.AUTO;
    private String entityClassName = "";
    private FieldCardinality cardinality = FieldCardinality.AUTO;
    private FieldRenderMode renderMode = FieldRenderMode.AUTO;

    private final FieldSourceMapping mapping = new FieldSourceMapping();
    private final List<GeneratedFieldModel> fields = new ArrayList<>();

    public GeneratedFieldModel() {
        this("field", FieldType.AUTO, FieldCardinality.AUTO);
    }

    public GeneratedFieldModel(
            String name,
            FieldType type,
            FieldCardinality cardinality) {

        this.name = name == null || name.isBlank() ? "field" : name.trim();
        this.type = type == null ? FieldType.AUTO : type;
        this.cardinality =
                cardinality == null ? FieldCardinality.AUTO : cardinality;
    }

    public static GeneratedFieldModel nameField() {
        GeneratedFieldModel f =
                new GeneratedFieldModel(
                        "name",
                        FieldType.STRING,
                        FieldCardinality.SINGLE);
        f.mapping().productionKind(FieldProductionKind.INLINE_VALUE);
        return f;
    }

    public String name() { return name; }

    public void name(String name) {
        this.name = name == null || name.isBlank() ? "field" : name.trim();
    }

    public FieldType type() { return type; }

    public void type(FieldType type) {
        this.type = type == null ? FieldType.AUTO : type;
    }

    public String entityClassName() { return entityClassName; }

    public void entityClassName(String entityClassName) {
        this.entityClassName =
                entityClassName == null ? "" : entityClassName.trim();
    }

    public FieldCardinality cardinality() { return cardinality; }

    public void cardinality(FieldCardinality cardinality) {
        this.cardinality =
                cardinality == null ? FieldCardinality.AUTO : cardinality;
    }

    public FieldRenderMode renderMode() {
        return renderMode;
    }

    public void renderMode(FieldRenderMode renderMode) {
        this.renderMode =
                renderMode == null ? FieldRenderMode.AUTO : renderMode;
    }

    public boolean renderAsReference() {
        return renderMode == FieldRenderMode.REFERENCE;
    }

    public FieldSourceMapping mapping() { return mapping; }

    public List<GeneratedFieldModel> fields() { return fields; }

    public boolean collection() {
        return cardinality == FieldCardinality.COLLECTION;
    }

    public boolean isNameField() {
        return "name".equalsIgnoreCase(name);
    }

    public String displayType() {
        String base =
                switch (type) {
                    case STRING -> "String";
                    case IMAGE -> "Image";
                    case ENTITY -> entityClassName == null || entityClassName.isBlank()
                            ? "Object"
                            : entityClassName;
                    case NUMBER -> "Number";
                    case DATE -> "Date";
                    case TEXT -> "Text";
                    case AUTO -> entityClassName == null || entityClassName.isBlank()
                            ? "Auto"
                            : entityClassName;
                };

        return collection() ? "List<" + base + ">" : base;
    }

    @Override
    public String toString() {
        String s = displayType() + " " + name;

        if (renderMode == FieldRenderMode.REFERENCE) {
            s += " [reference]";
        } else if (renderMode == FieldRenderMode.INLINE) {
            s += " [inline]";
        }

        return s;
    }
}