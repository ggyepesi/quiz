package datasource.api;

/** A recipe attached to one typed, replaceable model site. */
public record SourceBinding(SourceBindingTarget target, SourceRecipe recipe) {

    public SourceBinding {
        if (target == null) throw new IllegalArgumentException("Binding target is required");
        if (recipe == null) throw new IllegalArgumentException("Source recipe is required");
    }

    /** Resolve the recipe and prove that the offering can be attached here. */
    public DatasourceOperation resolve(DatasourceRegistry registry) {
        return recipe.resolve(registry, target.scope());
    }

    /** Whether another binding occupies the same replaceable configuration slot. */
    public boolean sameTarget(SourceBinding other) {
        return other != null && target.equals(other.target);
    }
}
