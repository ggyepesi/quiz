package quiz.transform;

import objectview.Viewable;
import objectview.field.FieldAccess;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.group.ViewableGroup.Role;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A computed group which follows one stored reference field upward and assigns each
 * member to its closest selected ancestor. The rule is source- and domain-neutral:
 * the field supplies the graph and the persisted entity references supply its semantic
 * anchors.
 */
public final class NearestAncestorGroup extends EditableGroup implements ProducedGroup {

    public static final String REVIEW = "Review";
    public static final String UNCLASSIFIED = "Unclassified";

    private final String memberType;
    private final String field;
    private final List<Viewable> anchors;

    public NearestAncestorGroup(
            String label, String memberType, String field,
            Collection<? extends Viewable> anchors) {
        super(label);
        this.memberType = memberType == null ? "" : memberType;
        this.field = field == null ? "" : field;
        this.anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }

    public String memberType() { return memberType; }
    public String field() { return field; }
    public List<Viewable> anchors() { return anchors; }

    @Override public String getDisplayName() {
        return name() + "  [nearest ancestor: " + field + "]";
    }

    @Override public String ruleDescription() {
        return "Group by nearest selected ancestor through " + field;
    }

    @Override protected Map<String, Object> ruleFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("producer", "nearestAncestor");
        fields.put("memberType", memberType);
        fields.put("ancestorField", field);
        fields.put("ancestorAnchors", anchors);
        return fields;
    }

    @Override protected List<FieldRef> ruleFieldRefs() {
        return List.of(
                FieldRef.of("producer", FieldKind.TEXT, "String", false, false, true),
                FieldRef.of("memberType", FieldKind.TEXT, "String", false, false, true),
                FieldRef.of("ancestorField", FieldKind.TEXT, "String", false, false, true),
                FieldRef.described("ancestorAnchors", FieldKind.COLLECTION,
                        FieldKind.REFERENCE, "Collection<Viewable>", true, true,
                        null, true, true, false, false, "", true));
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        clearChildren();
        setRole(Role.UNIVERSE);
        List<Viewable> members = new ArrayList<>();
        if (parentMembers != null) {
            parentMembers.stream().filter(java.util.Objects::nonNull)
                    .forEach(members::add);
        }
        replaceMembers(members);

        Map<Identity, Viewable> anchorsByIdentity = new LinkedHashMap<>();
        for (Viewable anchor : anchors) {
            Identity identity = Identity.of(anchor);
            if (identity != null) anchorsByIdentity.putIfAbsent(identity, anchor);
        }
        Map<Identity, String> labels = uniqueAnchorLabels(anchorsByIdentity);
        Map<Identity, List<Viewable>> assigned = new LinkedHashMap<>();
        anchorsByIdentity.keySet().forEach(key -> assigned.put(key, new ArrayList<>()));
        List<Viewable> review = new ArrayList<>();
        List<Viewable> unclassified = new ArrayList<>();

        Map<NodeKey, Set<Identity>> nearestByNode = nearestAnchors(
                members, anchorsByIdentity.keySet());
        for (Viewable member : members) {
            Set<Identity> nearest = nearestByNode.getOrDefault(
                    NodeKey.of(member), Set.of());
            if (nearest.isEmpty()) {
                unclassified.add(member);
            } else if (nearest.size() > 1) {
                review.add(member);
            } else {
                assigned.get(nearest.iterator().next()).add(member);
            }
        }
        assigned.forEach((identity, values) -> {
            EditableGroup child = bucket(labels.get(identity), values);
            child.setKeyRef(anchorsByIdentity.get(identity));
        });
        if (!review.isEmpty()) bucket(REVIEW, review);
        if (!unclassified.isEmpty()) bucket(UNCLASSIFIED, unclassified);
    }

    private EditableGroup bucket(String label, List<Viewable> members) {
        EditableGroup child = new EditableGroup(label);
        child.setRole(Role.BUCKET);
        child.replaceMembers(members);
        addGroup(child);
        return child;
    }

    /** Index the upward graph once, then search it in reverse from all anchors at
     * once. Every node is therefore classified without repeating its ancestry walk. */
    private Map<NodeKey, Set<Identity>> nearestAnchors(
            List<Viewable> members, Set<Identity> anchorIds) {
        Map<NodeKey, Viewable> nodes = new LinkedHashMap<>();
        Map<NodeKey, Set<NodeKey>> descendants = new LinkedHashMap<>();
        ArrayDeque<Viewable> pending = new ArrayDeque<>(members);
        objectview.field.FieldPath path = objectview.field.FieldPath.parse(field);
        while (!pending.isEmpty()) {
            Viewable node = pending.removeFirst();
            NodeKey nodeKey = NodeKey.of(node);
            if (nodes.putIfAbsent(nodeKey, node) != null) continue;
            List<Viewable> parents = new ArrayList<>();
            addViewables(FieldAccess.getPathValues(node, path), parents);
            for (Viewable parent : parents) {
                NodeKey parentKey = NodeKey.of(parent);
                descendants.computeIfAbsent(parentKey, ignored -> new LinkedHashSet<>())
                        .add(nodeKey);
                if (!nodes.containsKey(parentKey)) pending.addLast(parent);
            }
        }

        record Reach(NodeKey node, Identity anchor, int distance) { }
        ArrayDeque<Reach> reached = new ArrayDeque<>();
        for (Identity anchor : anchorIds) {
            NodeKey key = NodeKey.stable(anchor);
            if (nodes.containsKey(key)) reached.addLast(new Reach(key, anchor, 0));
        }
        Map<NodeKey, Integer> bestDistance = new LinkedHashMap<>();
        Map<NodeKey, Set<Identity>> nearest = new LinkedHashMap<>();
        while (!reached.isEmpty()) {
            Reach current = reached.removeFirst();
            Integer best = bestDistance.get(current.node());
            boolean propagate;
            if (best == null || current.distance() < best) {
                bestDistance.put(current.node(), current.distance());
                Set<Identity> one = new LinkedHashSet<>();
                one.add(current.anchor());
                nearest.put(current.node(), one);
                propagate = true;
            } else if (current.distance() == best) {
                propagate = nearest.get(current.node()).add(current.anchor());
            } else {
                propagate = false;
            }
            if (!propagate) continue;
            for (NodeKey child : descendants.getOrDefault(current.node(), Set.of())) {
                reached.addLast(new Reach(child, current.anchor(), current.distance() + 1));
            }
        }
        return nearest;
    }

    private static void addViewables(Object value, List<Viewable> out) {
        if (value instanceof Viewable viewable) {
            out.add(viewable);
        } else if (value instanceof Collection<?> values) {
            values.stream().filter(Viewable.class::isInstance)
                    .map(Viewable.class::cast).forEach(out::add);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(value, i);
                if (element instanceof Viewable viewable) out.add(viewable);
            }
        }
    }

    private static Map<Identity, String> uniqueAnchorLabels(
            Map<Identity, Viewable> anchors) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        anchors.values().forEach(anchor -> counts.merge(label(anchor), 1, Integer::sum));
        Map<Identity, String> labels = new LinkedHashMap<>();
        anchors.forEach((identity, anchor) -> {
            String label = label(anchor);
            if (counts.getOrDefault(label, 0) > 1
                    || REVIEW.equals(label) || UNCLASSIFIED.equals(label)) {
                label += " (" + anchor.getIdentifier() + ")";
            }
            labels.put(identity, label);
        });
        return labels;
    }

    private static String label(Viewable value) {
        String label = value.getReferenceLabel();
        return label == null || label.isBlank() ? value.getIdentifier() : label;
    }

    private record Identity(String type, String id) {
        static Identity of(Viewable value) {
            if (value == null || value.getIdentifier() == null
                    || value.getIdentifier().isBlank()) return null;
            String type = value.identityTypeName();
            return new Identity(type == null ? "" : type, value.getIdentifier());
        }
    }

    /** Stable identity where available; otherwise reference identity for an inline
     * anonymous graph node. */
    private static final class NodeKey {
        private final Identity stable;
        private final Viewable reference;

        private NodeKey(Identity stable, Viewable reference) {
            this.stable = stable;
            this.reference = reference;
        }

        static NodeKey of(Viewable value) {
            Identity identity = Identity.of(value);
            return identity == null
                    ? new NodeKey(null, value) : stable(identity);
        }

        static NodeKey stable(Identity identity) {
            return new NodeKey(identity, null);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof NodeKey key)) return false;
            return stable != null || key.stable != null
                    ? java.util.Objects.equals(stable, key.stable)
                    : reference == key.reference;
        }

        @Override public int hashCode() {
            return stable != null ? stable.hashCode()
                    : System.identityHashCode(reference);
        }
    }
}
