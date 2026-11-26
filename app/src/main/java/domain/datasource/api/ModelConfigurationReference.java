package domain.datasource.api;

/**
 * One model declaration named inside a datasource recipe.
 *
 * <p>The operation owns the recipe grammar, so it is also the only component that may
 * interpret recipe parameters as class or field references.  The model-layer
 * configuration inventory resolves these provider-neutral descriptions to concrete
 * configuration sites; it never switches on provider or operation ids.</p>
 */
public record ModelConfigurationReference(
        Kind kind,
        ClassSource classSource,
        String className,
        String fieldPath,
        String role,
        String classParameterKey,
        String fieldParameterKey,
        boolean required,
        String declarationId) {

    /** CLASS and FIELD name model classes; TEXT_SOURCE names a text-source declaration
     *  by its {@link #declarationId}. */
    public enum Kind { CLASS, FIELD, TEXT_SOURCE }
    public enum ClassSource {
        /** The recipe stores the referenced class name. */
        NAMED,
        /** The referenced class is the configured binding field's entity target. */
        BINDING_FIELD_TARGET
    }

    public ModelConfigurationReference {
        if (kind == null) throw new IllegalArgumentException("Reference kind is required");
        classSource = classSource == null ? ClassSource.NAMED : classSource;
        className = clean(className);
        fieldPath = clean(fieldPath);
        role = clean(role);
        classParameterKey = clean(classParameterKey);
        fieldParameterKey = clean(fieldParameterKey);
        declarationId = clean(declarationId);
        if (kind == Kind.TEXT_SOURCE) {
            if (declarationId.isBlank()) {
                throw new IllegalArgumentException("A text-source reference needs its declaration id");
            }
            if (!className.isBlank() || !fieldPath.isBlank()) {
                throw new IllegalArgumentException("A text-source reference names no class or field");
            }
        } else if (!declarationId.isBlank()) {
            throw new IllegalArgumentException("Only a text-source reference carries a declaration id");
        }
        if (kind != Kind.TEXT_SOURCE && classSource == ClassSource.NAMED && className.isBlank()) {
            throw new IllegalArgumentException("A model reference needs a class name");
        }
        if (classSource == ClassSource.BINDING_FIELD_TARGET && !className.isBlank()) {
            throw new IllegalArgumentException(
                    "A binding-field target reference does not store a class name");
        }
        if (kind == Kind.FIELD && fieldPath.isBlank()) {
            throw new IllegalArgumentException("A field reference needs a field path");
        }
        if (kind == Kind.CLASS && !fieldPath.isBlank()) {
            throw new IllegalArgumentException("A class reference has no field path");
        }
    }

    public static ModelConfigurationReference classReference(
            String className, String role, boolean required) {
        return new ModelConfigurationReference(
                Kind.CLASS, ClassSource.NAMED, className, "", role, "", "", required, "");
    }

    public static ModelConfigurationReference classParameterReference(
            String className, String role, String parameterKey, boolean required) {
        return new ModelConfigurationReference(Kind.CLASS, ClassSource.NAMED,
                className, "", role, parameterKey, "", required, "");
    }

    public static ModelConfigurationReference fieldReference(
            String className, String fieldPath, String role, boolean required) {
        return new ModelConfigurationReference(
                Kind.FIELD, ClassSource.NAMED, className, fieldPath,
                role, "", "", required, "");
    }

    public static ModelConfigurationReference fieldParameterReference(
            String className, String fieldPath, String role,
            String classParameterKey, String fieldParameterKey, boolean required) {
        return new ModelConfigurationReference(Kind.FIELD, ClassSource.NAMED,
                className, fieldPath, role, classParameterKey, fieldParameterKey,
                required, "");
    }

    public static ModelConfigurationReference bindingTargetField(
            String fieldPath, String role, boolean required) {
        return new ModelConfigurationReference(Kind.FIELD,
                ClassSource.BINDING_FIELD_TARGET, "", fieldPath,
                role, "", "", required, "");
    }

    /** A recipe parameter that names one text source by declaration id. */
    public static ModelConfigurationReference textSourceReference(
            String declarationId, String role, String parameterKey, boolean required) {
        return new ModelConfigurationReference(Kind.TEXT_SOURCE, ClassSource.NAMED, "", "",
                role, parameterKey, "", required, declarationId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
