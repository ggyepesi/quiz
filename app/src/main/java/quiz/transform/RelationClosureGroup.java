package quiz.transform;

import domain.DomainModel;
import objectview.Viewable;
import objectview.field.FieldKind;
import objectview.field.FieldPath;
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
 * A produced group formed by the closure of a configured bipartite relation.
 * Seeds reach members through bridge rows; members reach further entities through
 * the same rows, but only entities in the named population may continue the walk.
 */
public final class RelationClosureGroup extends EditableGroup implements ProducedGroup {
    private final String memberType;
    private final String bridgeType;
    private final String memberField;
    private final String entityField;
    private final String admissionSelection;
    private final List<Viewable> seeds;
    /** One predecessor per reached node: breadth-first discovery makes this a shortest
     * path without retaining a copied path list for every member of a large closure. */
    private Map<TraversalNode, Arrival> arrivals = Map.of();
    private String problem = "";

    private enum Side { MEMBER, ENTITY }
    private record TraversalNode(Side side, NodeKey.Identity identity) {}
    private record Arrival(TraversalNode previous, Viewable bridge, Viewable node) {}

    /** The two alternating roles in a retained relation-closure path. */
    public enum PathRole { MEMBER, ENTITY }

    /** One original domain instance in a retained shortest path. */
    public record PathNode(PathRole role, Viewable instance) { }

    /** The original bridge instance connecting two adjacent path nodes. */
    public record PathEdge(PathNode source, PathNode target, Viewable bridge) { }

    /** A shortest path expressed as domain nodes and the bridge rows between them. */
    public record RelationPath(List<PathNode> nodes, List<PathEdge> edges) {
        public RelationPath {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        public boolean isEmpty() { return nodes.isEmpty(); }

        public List<Viewable> instances() {
            return nodes.stream().map(PathNode::instance).toList();
        }

        /** Compatibility shape used by persisted-path callers: node, bridge, node… */
        public List<Viewable> storedInstances() {
            if (nodes.isEmpty()) return List.of();
            List<Viewable> result = new ArrayList<>(nodes.size() + edges.size());
            result.add(nodes.getFirst().instance());
            for (PathEdge edge : edges) {
                result.add(edge.bridge());
                result.add(edge.target().instance());
            }
            return List.copyOf(result);
        }
    }

    public RelationClosureGroup(String name, String memberType, String bridgeType,
            String memberField, String entityField, String admissionSelection,
            Collection<? extends Viewable> seeds) {
        super(name);
        this.memberType = clean(memberType);
        this.bridgeType = clean(bridgeType);
        this.memberField = clean(memberField);
        this.entityField = clean(entityField);
        this.admissionSelection = clean(admissionSelection);
        this.seeds = seeds == null ? List.of() : List.copyOf(seeds);
    }

    public String memberType() { return memberType; }
    public String bridgeType() { return bridgeType; }
    public String memberField() { return memberField; }
    public String entityField() { return entityField; }
    public String admissionSelection() { return admissionSelection; }
    public List<Viewable> seeds() { return seeds; }

    /** The shortest retained seed-to-member path, alternating original path nodes and
     * bridge instances. Empty means the value is not a member of the reproduced closure. */
    public List<Viewable> pathTo(Viewable member) {
        return relationPathTo(member).storedInstances();
    }

    /** The shortest retained seed-to-member path with bridge rows represented as edges. */
    public RelationPath relationPathTo(Viewable member) {
        NodeKey.Identity identity = NodeKey.Identity.of(member);
        TraversalNode current = identity == null
                ? null : new TraversalNode(Side.MEMBER, identity);
        if (current == null || !arrivals.containsKey(current)) {
            return new RelationPath(List.of(), List.of());
        }
        record RetainedStep(TraversalNode key, Arrival arrival) { }
        ArrayDeque<RetainedStep> retained = new ArrayDeque<>();
        Set<TraversalNode> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current)) {
            Arrival arrival = arrivals.get(current);
            if (arrival == null || arrival.previous() != null && arrival.bridge() == null) {
                return new RelationPath(List.of(), List.of());
            }
            retained.addFirst(new RetainedStep(current, arrival));
            current = arrival.previous();
        }
        if (current != null) return new RelationPath(List.of(), List.of());

        List<PathNode> nodes = new ArrayList<>(retained.size());
        List<PathEdge> edges = new ArrayList<>(Math.max(0, retained.size() - 1));
        for (RetainedStep step : retained) {
            PathNode node = new PathNode(step.key().side() == Side.MEMBER
                    ? PathRole.MEMBER : PathRole.ENTITY, step.arrival().node());
            if (!nodes.isEmpty()) {
                edges.add(new PathEdge(nodes.getLast(), node, step.arrival().bridge()));
            }
            nodes.add(node);
        }
        return new RelationPath(nodes, edges);
    }

    @Override public String getDisplayName() {
        return name();
    }

    @Override public String ruleDescription() {
        return "Follow " + bridgeType + "." + memberField + " ↔ "
                + bridgeType + "." + entityField + "; continue through "
                + admissionSelection;
    }

    @Override public String problem() { return problem; }

    @Override protected Map<String, Object> ruleFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("producer", "relationClosure");
        fields.put("memberType", memberType);
        fields.put("bridgeType", bridgeType);
        fields.put("memberField", memberField);
        fields.put("entityField", entityField);
        fields.put("admissionSelection", admissionSelection);
        fields.put("closureSeeds", seeds);
        return fields;
    }

    @Override protected List<FieldRef> ruleFieldRefs() {
        List<FieldRef> refs = new ArrayList<>();
        for (String name : List.of("producer", "memberType", "bridgeType",
                "memberField", "entityField", "admissionSelection")) {
            refs.add(FieldRef.of(name, FieldKind.TEXT, "String", false, false, true));
        }
        refs.add(FieldRef.described("closureSeeds", FieldKind.COLLECTION,
                FieldKind.REFERENCE, "Collection<Viewable>", true, true,
                null, true, true, false, false, "", true));
        return List.copyOf(refs);
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        arrivals = Map.of();
        replaceMembers(List.of());
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers,
            DomainModel domain) {
        setRole(Role.BUCKET);
        problem = "";
        arrivals = Map.of();
        if (domain == null || parentMembers == null || seeds.isEmpty()) {
            replaceMembers(List.of());
            return;
        }
        // The boundary is what makes this a closure. Without it the walk would stop at
        // the seeds' own members and look like a real, smaller answer, so an unresolved
        // boundary produces nothing and says why.
        if (!domain.selectionNames().contains(admissionSelection)) {
            problem = "Admission population '" + admissionSelection
                    + "' is not loaded in this domain, so the closure was not computed.";
            replaceMembers(List.of());
            return;
        }

        Set<NodeKey.Identity> eligibleMembers = identities(parentMembers);
        Set<NodeKey.Identity> admittedEntities = identities(
                domain.selectionMembers(admissionSelection));
        if (admittedEntities.isEmpty()) {
            problem = "Admission population '" + admissionSelection
                    + "' has no loaded members, so the walk did not continue past the"
                    + " seeds' own members.";
        }
        Map<NodeKey.Identity, Map<NodeKey.Identity, Viewable>> membersByEntity =
                new LinkedHashMap<>();
        Map<NodeKey.Identity, Map<NodeKey.Identity, Viewable>> entitiesByMember =
                new LinkedHashMap<>();
        Map<NodeKey.Identity, Viewable> entityInstances = new LinkedHashMap<>();
        Map<NodeKey.Identity, Viewable> memberInstances = new LinkedHashMap<>();
        for (Viewable member : parentMembers) {
            NodeKey.Identity id = NodeKey.Identity.of(member);
            if (id != null) memberInstances.putIfAbsent(id, member);
        }

        FieldPath memberPath = FieldPath.parse(memberField);
        FieldPath entityPath = FieldPath.parse(entityField);
        for (Viewable row : domain.instancesOf(bridgeType)) {
            List<Viewable> rowMembers = ReferenceField.values(row, memberPath).stream()
                    .filter(value -> domain.isInstanceOf(value, memberType)).toList();
            List<Viewable> rowEntities = ReferenceField.values(row, entityPath);
            for (Viewable member : rowMembers) {
                NodeKey.Identity memberId = NodeKey.Identity.of(member);
                if (memberId == null || !eligibleMembers.contains(memberId)) continue;
                for (Viewable entity : rowEntities) {
                    NodeKey.Identity entityId = NodeKey.Identity.of(entity);
                    if (entityId == null) continue;
                    entityInstances.putIfAbsent(entityId, entity);
                    memberInstances.putIfAbsent(memberId, member);
                    membersByEntity.computeIfAbsent(entityId,
                                    ignored -> new LinkedHashMap<>())
                            .putIfAbsent(memberId, row);
                    entitiesByMember.computeIfAbsent(memberId,
                                    ignored -> new LinkedHashMap<>())
                            .putIfAbsent(entityId, row);
                }
            }
        }

        ArrayDeque<NodeKey.Identity> pending = new ArrayDeque<>();
        Set<NodeKey.Identity> visitedEntities = new LinkedHashSet<>();
        Map<TraversalNode, Arrival> discovered = new LinkedHashMap<>();
        for (Viewable seed : seeds) {
            NodeKey.Identity id = NodeKey.Identity.of(seed);
            if (id != null && visitedEntities.add(id)) {
                Viewable connectedSeed = entityInstances.getOrDefault(id, seed);
                discovered.put(new TraversalNode(Side.ENTITY, id),
                        new Arrival(null, null, connectedSeed));
                pending.addLast(id);
            }
        }
        Set<NodeKey.Identity> reachedMembers = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            NodeKey.Identity entity = pending.removeFirst();
            for (Map.Entry<NodeKey.Identity, Viewable> memberEdge
                    : membersByEntity.getOrDefault(entity, Map.of()).entrySet()) {
                NodeKey.Identity member = memberEdge.getKey();
                if (!reachedMembers.add(member)) continue;
                TraversalNode memberNode = new TraversalNode(Side.MEMBER, member);
                discovered.put(memberNode, new Arrival(
                        new TraversalNode(Side.ENTITY, entity), memberEdge.getValue(),
                        memberInstances.get(member)));
                for (Map.Entry<NodeKey.Identity, Viewable> entityEdge
                        : entitiesByMember.getOrDefault(member,
                                Map.of()).entrySet()) {
                    NodeKey.Identity next = entityEdge.getKey();
                    if (admittedEntities.contains(next) && visitedEntities.add(next)) {
                        discovered.put(new TraversalNode(Side.ENTITY, next), new Arrival(
                                memberNode, entityEdge.getValue(), entityInstances.get(next)));
                        pending.addLast(next);
                    }
                }
            }
        }

        arrivals = Map.copyOf(discovered);

        List<Viewable> result = new ArrayList<>();
        for (Viewable member : parentMembers) {
            if (reachedMembers.contains(NodeKey.Identity.of(member))) result.add(member);
        }
        replaceMembers(result);
    }

    private static Set<NodeKey.Identity> identities(Collection<? extends Viewable> values) {
        Set<NodeKey.Identity> result = new LinkedHashSet<>();
        if (values != null) for (Viewable value : values) {
            NodeKey.Identity identity = NodeKey.Identity.of(value);
            if (identity != null) result.add(identity);
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
