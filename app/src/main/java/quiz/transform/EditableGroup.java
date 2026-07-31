package quiz.transform;

import objectview.Viewable;
import objectview.group.ViewableGroup;
import objectview.group.ViewableGroupAdapter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable working-tree node shared by manual and rule-produced groups. */
public class EditableGroup extends ViewableGroupAdapter {

    private final String name;

    public EditableGroup(String name) {
        super(name, name);
        this.name = name;
    }

    public String name() { return name; }

    /** File children under the undecorated name, so a produced group's rule-decorated
     *  display name ({@link FacetGroup#getDisplayName()}) never becomes the sibling key. */
    @Override public String groupKey() { return name; }

    @Override public String getIdentifier() {
        if (getParent() == null) return name;
        String parentId = getParent().getIdentifier();
        return parentId == null || parentId.isBlank() ? name : parentId + "." + name;
    }

    public void setRole(Role value) { role(value); }
    public void setKeyRef(Viewable value) { keyRef(value); }

    public void addGroup(EditableGroup child) {
        if (child == null) return;
        child.parent(this);
        putChild(child);
    }

    public void removeGroup(EditableGroup child) {
        if (child == null) return;
        removeChild(child.groupKey());
        child.parent(null);
    }

    public void replaceMembers(Collection<? extends Viewable> values) {
        clearMembers();
        if (values != null) values.forEach(this::putMember);
    }

    /** Recompute rule-produced descendants against their immediate parent's members. */
    public void reproduceDescendants() {
        reproduceDescendants(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    // Tree-only in practice, but guarded so a back-edge (e.g. reconstructed from a bad
    // snapshot) can't recurse forever.
    private void reproduceDescendants(java.util.Set<ViewableGroup<Viewable>> visited) {
        if (!visited.add(this)) return;
        for (ViewableGroup<Viewable> child : getChildren()) {
            if (child instanceof ProducedGroup produced) {
                produced.reproduce(getMembers());
            }
            if (child instanceof EditableGroup editable) {
                editable.reproduceDescendants(visited);
            }
        }
    }

    public static EditableGroup copyOf(ViewableGroup<?> source) {
        return copyOf(source, new java.util.IdentityHashMap<>());
    }

    // A revisited source returns its in-progress copy, so a cyclic/DAG persisted graph
    // reconstructs without infinite recursion (and shared nodes copy once).
    private static EditableGroup copyOf(
            ViewableGroup<?> source, Map<ViewableGroup<?>, EditableGroup> seen) {
        EditableGroup existing = seen.get(source);
        if (existing != null) return existing;
        objectview.field.FieldSet fields = source.fields();
        String producer = text(fields.read("producer"));
        String storedName = text(fields.read("groupName"));
        String name = storedName.isBlank() ? source.getDisplayName() : storedName;
        EditableGroup copy;
        if ("facet".equals(producer)) {
            copy = new FacetGroup(name, text(fields.read("memberType")),
                    text(fields.read("facetField")));
        } else if ("filter".equals(producer)) {
            String memberType = text(fields.read("memberType"));
            String path = text(fields.read("filterField"));
            quiz.transform.pipeline.ui.FilterOperator operator =
                    quiz.transform.pipeline.ui.FilterOperator.valueOf(
                            text(fields.read("filterOperator")));
            quiz.transform.ui.DomainField domainField =
                    new quiz.transform.ui.DomainField(memberType, path, false, false);
            copy = new OperationGroup(name, memberType,
                    new quiz.transform.pipeline.ui.FilterCondition(
                            domainField, operator,
                            fields.read("filterValue"), fields.read("filterValue2")));
        } else {
            copy = new EditableGroup(name);
        }
        seen.put(source, copy);   // register before recursing so a back-edge terminates
        copy.setRole(source.getRole());
        copy.setKeyRef(source.getKeyRef());
        copy.replaceMembers(source.getMembers());
        for (ViewableGroup<?> child : source.getChildren()) {
            copy.addGroup(copyOf(child, seen));
        }
        return copy;
    }

    protected Map<String, Object> ruleFields() { return Map.of(); }

    @Override public objectview.field.FieldSet fields() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("children", getChildren());
        values.put("members", getMembers());
        values.put("parent", getParent());
        values.put("groupName", name);
        values.putAll(ruleFields());
        return new objectview.field.FieldSet() {
            @Override public List<objectview.field.FieldRef> fields() {
                List<objectview.field.FieldRef> refs = new java.util.ArrayList<>();
                refs.add(objectview.field.FieldRef.described(
                        "children", objectview.field.FieldKind.COLLECTION,
                        objectview.field.FieldKind.REFERENCE, "Collection<ViewableGroup>",
                        true, true, "ViewableGroup", true, false,
                        false, false, "", false, true));
                refs.add(objectview.field.FieldRef.described(
                        "members", objectview.field.FieldKind.COLLECTION,
                        objectview.field.FieldKind.REFERENCE, "Collection<Viewable>",
                        true, true, null, true, false,
                        false, false, "", false, true));
                refs.add(objectview.field.FieldRef.described(
                        "parent", objectview.field.FieldKind.REFERENCE,
                        objectview.field.FieldKind.REFERENCE, "ViewableGroup",
                        true, false, "ViewableGroup", true, false,
                        false, false, "", false, true));
                refs.add(objectview.field.FieldRef.of(
                        "groupName", objectview.field.FieldKind.TEXT, "String",
                        false, false, true));
                for (String key : ruleFields().keySet()) {
                    refs.add(objectview.field.FieldRef.of(
                            key, objectview.field.FieldKind.TEXT, "String",
                            false, false, true));
                }
                return List.copyOf(refs);
            }
            @Override public Object read(String field) { return values.get(field); }
            @Override public boolean has(String field) { return values.containsKey(field); }
            @Override public void write(String field, Object value) {
                throw new UnsupportedOperationException("read-only group fields");
            }
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
