package quiz.transform;

import objectview.Viewable;
import objectview.facet.Facet;
import objectview.facet.FacetKey;
import objectview.group.ViewableGroup.Role;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A computed group whose bucket children are (re)produced by partitioning its parent scope's
 * members by a facet field — the facet-result counterpart of a hand-built manual group.
 *
 * <p>It remembers its RULE (the facet {@code field}) and its {@code memberType} (the
 * reproduce universe — needed because a computed group can be empty before the first
 * {@link #reproduce}, so member-inference won't do). {@code reproduce(parentMembers)}
 * re-partitions via a {@link Facet}, so the group stays fresh when the instance set
 * changes. Buckets are ordinary editable groups. Otherwise it behaves exactly
 * like a manual group, with its membership and children populated by the rule.
 */
public final class FacetGroup extends EditableGroup implements ProducedGroup {

    /**
     * How the facet buckets its members.
     *
     * <p>{@link #VALUE} makes one bucket per distinct value — and produces NO bucket for
     * a member whose field is empty, which is exactly what hides an expectation's
     * coverage gap. {@link #PRESENCE} makes two, present and missing, so the N records
     * an EXPECTED field reported as missing become a set you can select and curate
     * rather than a number in the run log (#96).
     */
    public enum Bucketing { VALUE, PRESENCE }

    private final String memberType;
    private final String field;
    private final Bucketing bucketing;

    public FacetGroup(String label, String memberType, String field) {
        this(label, memberType, field, Bucketing.VALUE);
    }

    public FacetGroup(String label, String memberType, String field,
                      Bucketing bucketing) {
        super(label);
        this.memberType = memberType;
        this.field = field;
        this.bucketing = bucketing == null ? Bucketing.VALUE : bucketing;
        role(Role.UNIVERSE);
    }

    /** Whether this group buckets by value or by present/missing. */
    public Bucketing bucketing() {
        return bucketing;
    }

    /** The type (typeName) whose instances this group scopes/reproduces from — remembered
     *  explicitly because a computed group can be empty before the first reproduce, so
     *  member-inference won't do. (The Java class is the shared universe, not this.) */
    public String memberType() {
        return memberType;
    }

    /** The rule: the field this group buckets its members by. */
    public String field() {
        return field;
    }

    @Override public String getDisplayName() {
        return name() + "  [facet: " + field
                + (bucketing == Bucketing.PRESENCE ? " present/missing" : "") + "]";
    }

    @Override public String ruleDescription() {
        return bucketing == Bucketing.PRESENCE
                ? "Facet by whether " + field + " is present"
                : "Facet by " + field;
    }

    @Override protected Map<String, Object> ruleFields() {
        return Map.of("producer", "facet", "memberType", memberType,
                "facetField", field, "facetBucketing", bucketing.name());
    }

    /** Rebuild children + members by partitioning {@code parentMembers} by {@link #field}
     *  (a facet over "Asia" reproduces against Asia's members — call parent before child). */
    public void reproduce(Collection<? extends Viewable> parentMembers) {
        clearChildren();
        replaceMembers(java.util.List.of());
        setRole(Role.UNIVERSE);
        Facet<Viewable> facet = bucketing == Bucketing.PRESENCE
                ? Facet.presence(field, field)
                : Facet.field(field);
        EditableGroup dimension = new EditableGroup(facet.label());
        dimension.setRole(Role.FACET);
        addGroup(dimension);
        Map<String, EditableGroup> buckets = new LinkedHashMap<>();
        java.util.List<Viewable> matchedMembers = new java.util.ArrayList<>();
        java.util.Set<Viewable> matched = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());

        for (Viewable member : parentMembers) {
            if (member == null) {
                continue;
            }
            for (FacetKey key : facet.keys().apply(member)) {
                if (key == null || !key.isUsable()) {
                    continue;
                }
                if (matched.add(member)) {
                    matchedMembers.add(member);
                }
                EditableGroup bucket = buckets.computeIfAbsent(key.name(), name -> {
                    EditableGroup created = new EditableGroup(name);
                    created.setRole(Role.BUCKET);
                    created.setKeyRef(key.ref());
                    dimension.addGroup(created);
                    return created;
                });
                java.util.List<Viewable> values =
                        new java.util.ArrayList<>(bucket.getMembers());
                values.add(member);
                bucket.replaceMembers(values);
            }
        }
        // The produced group is the union of its value buckets. A source member
        // without a usable facet value belongs to none of those buckets and is not
        // silently retained in the facet result.
        replaceMembers(matchedMembers);
    }
}
