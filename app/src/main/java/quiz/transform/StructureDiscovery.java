package quiz.transform;

import objectview.Viewable;
import objectview.field.FieldPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers a second-order structure from already loaded objects:
 * relation components become conceptual families, one member represents each family,
 * and families are connected when a bridge relation gives them a shared neighbour.
 * No input is changed and no provider request is made.
 *
 * <p>Nodes are identified by {@link NodeKey}, the same rule {@link RelationProfile} used to
 * form the components being read here. Identity by QID alone would merge an owned part
 * with its owner, which carries the same QID by construction.
 */
public final class StructureDiscovery {
    private StructureDiscovery() { }

    public record Bridge(String rowType, String memberField, String sharedField) {
        public Bridge {
            rowType = clean(rowType);
            memberField = clean(memberField);
            sharedField = clean(sharedField);
            if (memberField.isBlank() || sharedField.isBlank()) {
                throw new IllegalArgumentException(
                        "Structure discovery needs member and shared-entity fields");
            }
        }
        @Override public String toString() {
            return rowType + "." + memberField + " — shared by " + rowType + "." + sharedField;
        }
    }

    public record Family(int index, Viewable representative, List<Viewable> members,
                         List<Viewable> sharedEntities, int representativeSharedCount) {
        public Family {
            members = List.copyOf(members);
            sharedEntities = List.copyOf(sharedEntities);
        }
    }

    public record SharedLink(Family left, Family right, List<Viewable> sharedEntities) {
        public SharedLink { sharedEntities = List.copyOf(sharedEntities); }
    }

    public record Result(List<Family> families, List<SharedLink> links,
                         int bridgeRows, int unmatchedMemberReferences) {
        public Result {
            families = List.copyOf(families);
            links = List.copyOf(links);
        }
    }

    public static Result discover(RelationProfile relation,
                                  Collection<? extends Viewable> bridgeRows,
                                  Bridge bridge) {
        if (relation == null || bridge == null) return new Result(List.of(), List.of(), 0, 0);
        List<RelationProfile.Component> components = relation.components();
        Map<NodeKey, Integer> familyByMember = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (Viewable member : components.get(i).members()) {
                familyByMember.put(NodeKey.of(member), i);
            }
        }

        List<Map<NodeKey, Viewable>> sharedByFamily = maps(components.size());
        Map<NodeKey, Map<NodeKey, Viewable>> memberShared = new LinkedHashMap<>();
        Map<NodeKey, Set<Integer>> familiesByShared = new LinkedHashMap<>();
        Map<NodeKey, Viewable> sharedValues = new LinkedHashMap<>();
        FieldPath memberPath = FieldPath.parse(bridge.memberField());
        FieldPath sharedPath = FieldPath.parse(bridge.sharedField());
        int rows = 0;
        int unmatched = 0;
        if (bridgeRows != null) {
            for (Viewable row : bridgeRows) {
                if (row == null) continue;
                rows++;
                List<Viewable> members = ReferenceField.values(row, memberPath);
                List<Viewable> shared = ReferenceField.values(row, sharedPath);
                for (Viewable member : members) {
                    Integer family = familyByMember.get(NodeKey.of(member));
                    if (family == null) { unmatched++; continue; }
                    NodeKey memberKey = NodeKey.of(member);
                    Map<NodeKey, Viewable> forMember = memberShared.computeIfAbsent(
                            memberKey, ignored -> new LinkedHashMap<>());
                    for (Viewable value : shared) {
                        NodeKey sharedKey = NodeKey.of(value);
                        forMember.putIfAbsent(sharedKey, value);
                        sharedByFamily.get(family).putIfAbsent(sharedKey, value);
                        sharedValues.putIfAbsent(sharedKey, value);
                        familiesByShared.computeIfAbsent(sharedKey,
                                ignored -> new LinkedHashSet<>()).add(family);
                    }
                }
            }
        }

        List<Family> families = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            RelationProfile.Component component = components.get(i);
            Viewable representative = component.members().stream()
                    .min(Comparator
                            .<Viewable>comparingInt(member -> -memberShared
                                    .getOrDefault(NodeKey.of(member), Map.of()).size())
                            .thenComparing(StructureDiscovery::display,
                                    String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(StructureDiscovery::stableOrder))
                    .orElseThrow();
            families.add(new Family(i, representative, component.members(),
                    List.copyOf(sharedByFamily.get(i).values()),
                    memberShared.getOrDefault(NodeKey.of(representative), Map.of()).size()));
        }

        Map<Long, Map<NodeKey, Viewable>> sharedByPair = new LinkedHashMap<>();
        for (Map.Entry<NodeKey, Set<Integer>> entry : familiesByShared.entrySet()) {
            List<Integer> indexes = entry.getValue().stream().sorted().toList();
            for (int i = 0; i < indexes.size(); i++) {
                for (int j = i + 1; j < indexes.size(); j++) {
                    int left = indexes.get(i), right = indexes.get(j);
                    long key = ((long) left << 32) | (right & 0xffffffffL);
                    sharedByPair.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                            .putIfAbsent(entry.getKey(), sharedValues.get(entry.getKey()));
                }
            }
        }
        List<SharedLink> links = new ArrayList<>();
        for (Map.Entry<Long, Map<NodeKey, Viewable>> entry : sharedByPair.entrySet()) {
            int left = (int) (entry.getKey() >> 32);
            int right = (int) (long) entry.getKey();
            links.add(new SharedLink(families.get(left), families.get(right),
                    List.copyOf(entry.getValue().values())));
        }
        links.sort(Comparator.<SharedLink>comparingInt(link -> -link.sharedEntities().size())
                .thenComparing(link -> display(link.left().representative()))
                .thenComparing(link -> display(link.right().representative())));
        return new Result(families, links, rows, unmatched);
    }

    private static List<Map<NodeKey, Viewable>> maps(int size) {
        List<Map<NodeKey, Viewable>> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(new LinkedHashMap<>());
        return result;
    }

    /** Last resort so equal names order the same way twice; never an identity test. */
    private static String stableOrder(Viewable value) {
        String id = value == null ? null : value.getIdentifier();
        return id == null ? "" : id.trim();
    }

    private static String display(Viewable value) {
        return value == null || value.getDisplayName() == null ? "" : value.getDisplayName();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
