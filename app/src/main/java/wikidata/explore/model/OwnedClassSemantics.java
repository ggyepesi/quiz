package wikidata.explore.model;

import datasource.schema.FieldType;

/** Single model boundary for the explicit Owned-class construction path. */
public final class OwnedClassSemantics {

    private OwnedClassSemantics() {}

    public static boolean isOwnedClass(GeneratedClassModel clazz) {
        return clazz != null && clazz.classKind() == ClassKind.OWNED;
    }

    public static boolean isOwnerQidField(
            GeneratedFieldModel field, GeneratedProjectModel project) {
        if (field == null || project == null || field.type() != FieldType.ENTITY
                || field.mapping().productionKind()
                        != FieldProductionKind.OWNED_COMPONENT) return false;
        return isOwnedClass(project.findClass(field.entityClassName()));
    }

    /** One compatibility boundary for models created before classKind=OWNED existed. */
    public static void migrateLegacy(GeneratedProjectModel project) {
        if (project == null) return;
        for (GeneratedClassModel owner : project.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field == null || field.mapping().productionKind()
                        != FieldProductionKind.OWNED_COMPONENT) continue;
                GeneratedClassModel target = project.findClass(field.entityClassName());
                if (target != null && target.classKind() == ClassKind.SOURCE) {
                    target.classKind(ClassKind.OWNED);
                }
            }
        }
    }
}
