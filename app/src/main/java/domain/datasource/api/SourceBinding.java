package domain.datasource.api;

/** A recipe attached to one typed model site. Most slots are replaceable;
 * candidate evidence is an ordered additive list. */
public record SourceBinding(SourceBindingTarget target, SourceRecipe recipe) {

    public SourceBinding {
        if (target == null) throw new IllegalArgumentException("Binding target is required");
        if (recipe == null) throw new IllegalArgumentException("Source recipe is required");
    }

    /** Resolve the recipe and prove that the offering can be attached here. */
    public DatasourceOperation resolve(DatasourceRegistry registry) {
        return recipe.resolve(registry, target.scope());
    }

    /** Whether another binding names the same semantic slot. Additive callers use this
     * only to group the children, never to collapse them. */
    public boolean sameTarget(SourceBinding other) {
        return other != null && target.equals(other.target);
    }
}
