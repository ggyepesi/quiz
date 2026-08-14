package quiz.transform;

import objectview.Viewable;
import quiz.transform.ui.DomainModel;

import java.util.Collection;

/** A persisted, reproducible group whose admission rule is a {@link TypeSpec}. */
public final class TypeSpecGroup extends EditableGroup implements ProducedGroup {
    private final TypeSpec spec;
    private final DomainModel domain;

    public TypeSpecGroup(String name, TypeSpec spec, DomainModel domain) {
        super(name);
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.domain = java.util.Objects.requireNonNull(domain, "domain");
    }

    public String memberType() { return spec.instanceClass(); }
    public TypeSpec spec() { return spec; }

    @Override public String getDisplayName() { return name() + "  [types: " + ruleDescription() + "]"; }
    @Override public String ruleDescription() {
        java.util.List<String> rules = new java.util.ArrayList<>();
        rules.add("instance is " + spec.instanceClass());
        spec.fieldClasses().forEach((path, types) ->
                rules.add(path + " is " + String.join(" or ", types)));
        return String.join("; ", rules);
    }

    @Override protected java.util.Map<String, Object> ruleFields() {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("producer", "typeSpec");
        fields.put("memberType", spec.instanceClass());
        fields.put("typeSpecInstanceClass", spec.instanceClass());
        fields.put("typeSpecFields", spec.encodeFieldClasses());
        return fields;
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        java.util.List<? extends Viewable> admitted = parentMembers == null
                ? java.util.List.of() : parentMembers.stream()
                        .filter(member -> spec.matches(member, domain)).toList();
        replaceMembers(admitted);
    }
}
