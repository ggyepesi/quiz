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
    private String problem = "";

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
        replaceMembers(List.of());
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers,
            DomainModel domain) {
        setRole(Role.BUCKET);
        problem = "";
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
        Map<NodeKey.Identity, LinkedHashSet<NodeKey.Identity>> membersByEntity =
                new LinkedHashMap<>();
        Map<NodeKey.Identity, LinkedHashSet<NodeKey.Identity>> entitiesByMember =
                new LinkedHashMap<>();

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
                    membersByEntity.computeIfAbsent(entityId, ignored -> new LinkedHashSet<>())
                            .add(memberId);
                    entitiesByMember.computeIfAbsent(memberId, ignored -> new LinkedHashSet<>())
                            .add(entityId);
                }
            }
        }

        ArrayDeque<NodeKey.Identity> pending = new ArrayDeque<>();
        Set<NodeKey.Identity> visitedEntities = new LinkedHashSet<>();
        for (Viewable seed : seeds) {
            NodeKey.Identity id = NodeKey.Identity.of(seed);
            if (id != null && visitedEntities.add(id)) pending.addLast(id);
        }
        Set<NodeKey.Identity> reachedMembers = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            NodeKey.Identity entity = pending.removeFirst();
            for (NodeKey.Identity member : membersByEntity.getOrDefault(
                    entity, new LinkedHashSet<>())) {
                if (!reachedMembers.add(member)) continue;
                for (NodeKey.Identity next : entitiesByMember.getOrDefault(
                        member, new LinkedHashSet<>())) {
                    if (admittedEntities.contains(next) && visitedEntities.add(next)) {
                        pending.addLast(next);
                    }
                }
            }
        }

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
