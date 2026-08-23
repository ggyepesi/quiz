package datasource.api;

/**
 * The model site and semantic slot to which a {@link SourceRecipe} is attached.
 *
 * <p>The slot distinguishes simultaneous uses at one site (for example additive
 * category evidence and a fallback value source). Replacing a recipe compares targets,
 * not providers: choosing DBpedia after Wikipedia infobox replaces the same fallback
 * slot instead of leaving two competing configurations behind.
 */
public record SourceBindingTarget(
        BindingScope scope,
        String className,
        String fieldPath,
        SourceBindingSlot slot) {

    public SourceBindingTarget {
        if (scope == null) throw new IllegalArgumentException("Binding scope is required");
        className = cleanRequired(className, "class name");
        fieldPath = clean(fieldPath);
        if (slot == null) throw new IllegalArgumentException("Binding slot is required");
        if (slot.scope() != scope) {
            throw new IllegalArgumentException(
                    "Source binding slot " + slot + " has scope " + slot.scope()
                            + ", not " + scope);
        }
        if (scope == BindingScope.FIELD_VALUE && fieldPath.isBlank()) {
            throw new IllegalArgumentException("A field-value binding needs a field path");
        }
        if (scope != BindingScope.FIELD_VALUE && !fieldPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Only a field-value binding may name a field path");
        }
    }

    public static SourceBindingTarget classPopulation(String className) {
        return new SourceBindingTarget(
                BindingScope.CLASS_POPULATION, className, "",
                SourceBindingSlot.CLASS_POPULATION);
    }

    public static SourceBindingTarget fieldValue(
            String className, String fieldPath, SourceBindingSlot slot) {
        return new SourceBindingTarget(
                BindingScope.FIELD_VALUE, className, fieldPath, slot);
    }

    private static String cleanRequired(String value, String what) {
        String clean = clean(value);
        if (clean.isBlank()) throw new IllegalArgumentException(what + " is required");
        return clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
