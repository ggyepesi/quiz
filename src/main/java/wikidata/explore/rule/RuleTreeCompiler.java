package wikidata.explore.rule;

import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.*;

public final class RuleTreeCompiler {

    private RuleTreeCompiler() {
    }

    public static RuleNode compileProject(GeneratedProjectModel project) {
        return compileClass(project.rootClass());
    }

    public static RuleNode compileClass(GeneratedClassModel clazz) {
        FieldSourceMapping m = clazz.instanceMapping();

        RuleNode node = new RuleNode(clazz.className(), decap(clazz.className()));

        node.sourceQid(m.sourceQid());
        node.sourceLabel(m.sourceLabel());
        node.propertyPid(m.propertyPid().isBlank() ? "P31" : m.propertyPid());
        node.propertyLabel(m.propertyLabel().isBlank()
                ? "instance of"
                : m.propertyLabel());
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(m.limit());
        node.labelConfig(new RuleLabelConfig(
                m.requireLabel(),
                m.labelLanguage()));

        for (GeneratedFieldModel f : clazz.fields()) {
            if (f.isNameField()) {
                continue;
            }
            compileFieldIntoNode(node, f);
        }

        return node;
    }

    public static RuleIncludedField compileField(
            GeneratedFieldModel field) {

        if (field == null || field.mapping().propertyPid().isBlank()) {
            return null;
        }

        FieldSourceMapping m = field.mapping();

        RuleIncludedField included = new RuleIncludedField(
                field.name(),
                m.propertyPid(),
                m.propertyLabel(),
                fieldKindFor(field),
                true);

        included.collection(field.collection());

        return included;
    }

    private static void compileFieldIntoNode(
            RuleNode parent,
            GeneratedFieldModel field) {

        if (field == null || field.mapping().propertyPid().isBlank()) {
            return;
        }

        FieldProductionKind kind = resolvedProductionKind(field);
        FieldSourceMapping m = field.mapping();

        if (kind == FieldProductionKind.CHILD_OBJECTS) {
            RuleNode child = new RuleNode(
                    field.entityClassName().isBlank()
                            ? cap(field.name())
                            : field.entityClassName(),
                    decap(field.name()));

            child.sourceQid(parent.sourceQid());
            child.sourceLabel(parent.sourceLabel());
            child.propertyPid(m.propertyPid());
            child.propertyLabel(m.propertyLabel());
            child.direction(m.direction());
            child.limit(m.limit());
            child.labelConfig(parent.labelConfig());

            parent.edges().add(new RuleEdge(
                    field.name(),
                    child,
                    field.collection()));
            return;
        }

        RuleIncludedField included = compileField(field);

        if (included != null) {
            parent.includedFields().add(included);
        }
    }

    private static RuleIncludedField.FieldKind fieldKindFor(
            GeneratedFieldModel field) {

        if (field.type() == FieldType.IMAGE) {
            return RuleIncludedField.FieldKind.MEDIA;
        }

        if (field.type() == FieldType.ENTITY || field.collection()) {
            return RuleIncludedField.FieldKind.ENTITY;
        }

        return RuleIncludedField.FieldKind.AUTO;
    }

    private static FieldProductionKind resolvedProductionKind(
            GeneratedFieldModel field) {

        FieldSourceMapping m = field.mapping();

        if (m.productionKind() != FieldProductionKind.AUTO) {
            return m.productionKind();
        }

        if (!field.fields().isEmpty()) {
            return FieldProductionKind.CHILD_OBJECTS;
        }

        if (field.collection()) {
            return FieldProductionKind.DELAYED_ENTITY_FIELD;
        }

        if (field.type() == FieldType.ENTITY) {
            return FieldProductionKind.DELAYED_ENTITY_FIELD;
        }

        return FieldProductionKind.INLINE_VALUE;
    }

    private static String decap(String s) {
        if (s == null || s.isBlank()) {
            return "item";
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return "Item";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
