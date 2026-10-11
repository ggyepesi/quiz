package wikidata.explore.model;

import datasource.schema.FieldType;

/** Single model boundary for the explicit Owned-class construction path. */
public final class OwnedClassSemantics {

    private OwnedClassSemantics() {}

    /** The one declared back-reference shared by every owned class. */
    public static final String OWNER_FIELD = "owner";
    /** Named fallback role for an owned class whose possible producers are not one
     *  concrete modeled class. This is model vocabulary; {@code Viewable} is only the
     *  Java rendering interface and must never appear as the semantic target. */
    public static final String OWNER_ROLE = "Owner";

    public static boolean isOwnedClass(GeneratedClassModel clazz) {
        return clazz != null && clazz.classKind() == ClassKind.OWNED;
    }

    /**
     * Makes the owner reference part of the actual class schema, rather than a field
     * secretly appended by a renderer or mapper. The project-level synchronization
     * below resolves its named target; before the class is attached to a project the
     * ordinary {@link #OWNER_ROLE} fallback keeps the schema semantic and inspectable.
     *
     * <p>The field is identified by {@link FieldProductionKind#OWNER_REFERENCE}. A field
     * the user wrote under the reserved name is never replaced or rewritten: nothing is
     * added, and the validator names the collision so it is renamed where it was
     * written.
     */
    public static GeneratedFieldModel ensureOwnerReference(
            GeneratedClassModel clazz) {
        if (!isOwnedClass(clazz)) return null;
        GeneratedFieldModel owner = clazz.fields().stream()
                .filter(field -> isOwnerReferenceField(clazz, field))
                .findFirst().orElse(null);
        if (owner == null) {
            if (authoredOwnerNameCollision(clazz) != null) return null;
            owner = new GeneratedFieldModel(
                    OWNER_FIELD, FieldType.ENTITY, FieldCardinality.SINGLE);
            owner.mapping().productionKind(FieldProductionKind.OWNER_REFERENCE);
            // Structural facts come first in the class and in its cards.
            clazz.fields().add(0, owner);
        }
        owner.unclassedEntity(false);
        if (owner.entityClassName().isBlank()) owner.entityClassName(OWNER_ROLE);
        owner.renderMode(FieldRenderMode.REFERENCE);
        return owner;
    }

    public static boolean isOwnerReferenceField(
            GeneratedClassModel clazz, GeneratedFieldModel field) {
        return isOwnedClass(clazz) && isOwnerReference(field);
    }

    private static boolean isOwnerReference(GeneratedFieldModel field) {
        return field != null && field.mapping().productionKind()
                == FieldProductionKind.OWNER_REFERENCE;
    }

    /** A field this owned class's author declared under the reserved owner name. */
    public static GeneratedFieldModel authoredOwnerNameCollision(GeneratedClassModel clazz) {
        if (!isOwnedClass(clazz)) return null;
        return clazz.fields().stream()
                .filter(field -> field != null && OWNER_FIELD.equals(field.name())
                        && !isOwnerReference(field))
                .findFirst().orElse(null);
    }

    /** Remove the system-declared reference when a class stops being owned. */
    public static void removeOwnerReference(GeneratedClassModel clazz) {
        if (clazz == null) return;
        clazz.fields().removeIf(OwnedClassSemantics::isOwnerReference);
    }

    public static boolean isOwnerQidField(
            GeneratedFieldModel field, GeneratedProjectModel project) {
        if (field == null || project == null || field.type() != FieldType.ENTITY
                || field.mapping().productionKind()
                        != FieldProductionKind.OWNED_COMPONENT) return false;
        return isOwnedClass(project.resolveClass(
                field.entityDeclarationId(), field.entityClassName()));
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
        synchronizeOwnerReferences(project);
    }

    /** Keep the class kind and its one derived schema field in lockstep. */
    public static void synchronizeOwnerReferences(GeneratedProjectModel project) {
        if (project == null) return;
        for (GeneratedClassModel clazz : java.util.List.copyOf(project.classes())) {
            GeneratedFieldModel owner = ensureOwnerReference(clazz);
            if (owner == null) continue;
            // An imported schema is owned by its source model. Its resolved target is
            // copied with it and may not be rewritten from the importing domain's sites.
            if (clazz.isImported() && !owner.entityClassName().isBlank()) continue;

            owner.unclassedEntity(false);
            owner.entityClassName(ownerTargetClassName(project, clazz));
        }
    }

    /**
     * The semantic target of the derived owner reference. Local owned classes derive it
     * from their production sites; imported schemas retain the target authored by their
     * owning model.
     */
    public static String ownerTargetClassName(
            GeneratedProjectModel project, GeneratedClassModel ownedClass) {
        if (project == null || !isOwnedClass(ownedClass)) return "";
        GeneratedFieldModel owner = ownedClass.fields().stream()
                .filter(field -> isOwnerReferenceField(ownedClass, field))
                .findFirst().orElse(null);
        if (ownedClass.isImported() && owner != null
                && !owner.entityClassName().isBlank()) {
            GeneratedClassModel target = project.resolveClass(
                    owner.entityDeclarationId(), owner.entityClassName());
            return target == null ? owner.entityClassName() : target.className();
        }
        java.util.List<GeneratedClassModel> producers = ownerClasses(project, ownedClass);
        return producers.size() == 1 ? producers.getFirst().className() : OWNER_ROLE;
    }

    /**
     * Whether this owned schema needs the reusable, explicitly authored Owner role.
     * Asked of the production sites, which are the fact, never of the stored target,
     * which is derived from them only at a load, save, import or compile boundary. An
     * imported schema keeps the target its source model resolved.
     */
    public static boolean requiresOwnerRole(
            GeneratedProjectModel project, GeneratedClassModel ownedClass) {
        if (project == null || !isOwnedClass(ownedClass)) return false;
        return OWNER_ROLE.equals(ownerTargetClassName(project, ownedClass));
    }

    /** Classes whose declared fields produce instances of {@code ownedClass}. */
    public static java.util.List<GeneratedClassModel> ownerClasses(
            GeneratedProjectModel project, GeneratedClassModel ownedClass) {
        if (project == null || ownedClass == null) return java.util.List.of();
        java.util.LinkedHashMap<String, GeneratedClassModel> result =
                new java.util.LinkedHashMap<>();
        for (MembershipPattern.OwnedBy site
                : MembershipPattern.ownedBy(ownedClass, project)) {
            GeneratedClassModel candidate = project.findClass(site.ownerClass());
            if (candidate != null) result.putIfAbsent(candidate.className(), candidate);
        }
        return java.util.List.copyOf(result.values());
    }

    /** A production site is admission for an already-known owner representation. */
    public static boolean isOwnerRepresentation(
            GeneratedProjectModel project,
            GeneratedClassModel role,
            GeneratedClassModel representation) {
        if (project == null || role == null || representation == null) return false;
        for (GeneratedClassModel owned : project.classes()) {
            if (!isOwnedClass(owned)) continue;
            if (!role.className().equals(ownerTargetClassName(project, owned))) continue;
            if (ownerClasses(project, owned).stream().anyMatch(candidate ->
                    candidate == representation
                            || candidate.className().equals(representation.className()))) {
                return true;
            }
        }
        return false;
    }
}
