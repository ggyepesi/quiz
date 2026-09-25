package quiz.transform;

import objectview.Viewable;
import objectview.field.FieldPath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a relation actually is, measured over the instances that are loaded.
 *
 * <p>Almost no relation is an equivalence relation. Succession is a chain, subclass is a
 * partial order, spouse is symmetric but not transitive — so the useful question is not
 * which textbook name fits but what the data says: whether the two directions agree,
 * whether there are cycles, and how large the connected components are. The component
 * sizes are what decide whether grouping by the relation means anything: over
 * {@code replaces} they are small office families, over {@code subclass} there is one
 * component holding the entire taxonomy.
 *
 * <p>Wikidata states part of this itself — P1696 pairs converse properties, P2302 carries
 * symmetric and inverse constraints. This measures AGREEMENT with what is stated; it never
 * infers a property's character from the shape of the data.
 *
 * <p>A relation is one thing whether it is stored as a converse pair
 * ({@code replaces}/{@code replacedBy}) or as a single property. Both directions normalize
 * into one directed edge set first, and how many edges only one side states is itself a
 * measure — Wikidata routinely records just one.
 *
 * <p>The nodes are the members handed in and nothing else. An edge whose target was not
 * loaded is counted as dangling and marks its component as touching the population
 * boundary: a chain continuing into offices outside the population looks finished
 * otherwise, and a group built from it would quietly be a fragment.
 */
public record RelationProfile(
        String forwardField,
        String inverseField,
        int members,
        int edges,
        int statedBothWays,
        List<OneSided> statedOneWay,
        int reflexive,
        int mutualPairs,
        int transitivityGaps,
        int maxOutDegree,
        int maxInDegree,
        int danglingEdges,
        List<Viewable> leavingPopulation,
        List<Component> components,
        List<List<Viewable>> mutuallyReachable) {

    /**
     * One weakly connected component: the candidate equivalence class.
     *
     * <p>{@code origins} have nothing pointing at them and {@code terminals} point at
     * nothing, which is what a representative rule like "the last office in the chain"
     * needs. Exactly one of either makes that rule well defined; a component with several
     * does not, and one that {@code touchesBoundary} may have more outside the population.
     */
    public record Component(
            List<Viewable> members,
            List<Viewable> origins,
            List<Viewable> terminals,
            boolean touchesBoundary) {

        public Component {
            members = List.copyOf(members);
            origins = List.copyOf(origins);
            terminals = List.copyOf(terminals);
        }

        public int size() { return members.size(); }
    }

    /**
     * An edge only one of the two fields states.
     *
     * <p>Carried as the entities rather than counted, because this is a worklist: each
     * one is a statement Wikidata has in one direction and not the other, and somebody
     * decides whether to add the other or leave it. A count cannot be acted on.
     */
    public record OneSided(Viewable from, Viewable to, boolean forwardOnly) { }

    private record Edge(int from, int to) { }

    public int largestComponent() {
        return components.stream().mapToInt(Component::size).max().orElse(0);
    }

    /** True when every component is a single member: the relation partitions nothing. */
    public boolean partitionsNothing() {
        return components.stream().allMatch(component -> component.size() == 1);
    }

    public static RelationProfile of(Collection<? extends Viewable> instances,
                                     String forwardField, String inverseField) {
        String forward = clean(forwardField);
        String inverse = clean(inverseField);
        if (forward.isBlank() && inverse.isBlank()) {
            throw new IllegalArgumentException(
                    "A relation needs at least one field to read");
        }

        List<Viewable> nodes = new ArrayList<>();
        Map<NodeKey, Integer> index = new LinkedHashMap<>();
        if (instances != null) {
            for (Viewable value : instances) {
                if (value == null) continue;
                if (index.putIfAbsent(NodeKey.of(value), nodes.size()) == null) {
                    nodes.add(value);
                }
            }
        }

        // [statedForward, statedInverse] per normalized directed edge.
        Map<Edge, boolean[]> stated = new LinkedHashMap<>();
        Set<Integer> boundaryNodes = new LinkedHashSet<>();
        List<Viewable> leaving = new ArrayList<>();
        int dangling = 0;
        FieldPath forwardPath = forward.isBlank() ? null : FieldPath.parse(forward);
        FieldPath inversePath = inverse.isBlank() ? null : FieldPath.parse(inverse);
        for (int from = 0; from < nodes.size(); from++) {
            Viewable node = nodes.get(from);
            for (Viewable target : ReferenceField.values(node, forwardPath)) {
                Integer to = index.get(NodeKey.of(target));
                if (to == null) {
                    dangling++;
                    if (boundaryNodes.add(from)) leaving.add(node);
                    continue;
                }
                stated.computeIfAbsent(new Edge(from, to), ignored -> new boolean[2])[0] = true;
            }
            // The inverse field states the same relation backwards, so it contributes
            // the reversed edge rather than a second relation.
            for (Viewable target : ReferenceField.values(node, inversePath)) {
                Integer to = index.get(NodeKey.of(target));
                if (to == null) {
                    dangling++;
                    if (boundaryNodes.add(from)) leaving.add(node);
                    continue;
                }
                stated.computeIfAbsent(new Edge(to, from), ignored -> new boolean[2])[1] = true;
            }
        }

        int bothWays = 0;
        List<OneSided> oneWay = new ArrayList<>();
        int reflexive = 0;
        int[] outDegree = new int[nodes.size()];
        int[] inDegree = new int[nodes.size()];
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) out.add(new ArrayList<>());
        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (Map.Entry<Edge, boolean[]> entry : stated.entrySet()) {
            Edge edge = entry.getKey();
            boolean[] sides = entry.getValue();
            if (!inverse.isBlank() && !forward.isBlank()) {
                if (sides[0] && sides[1]) {
                    bothWays++;
                } else {
                    oneWay.add(new OneSided(
                            nodes.get(edge.from()), nodes.get(edge.to()), sides[0]));
                }
            }
            if (edge.from() == edge.to()) reflexive++;
            outDegree[edge.from()]++;
            inDegree[edge.to()]++;
            out.get(edge.from()).add(edge.to());
            union(parent, edge.from(), edge.to());
        }

        int mutualPairs = 0;
        int transitivityGaps = 0;
        for (Edge edge : stated.keySet()) {
            if (edge.from() < edge.to() && stated.containsKey(new Edge(edge.to(), edge.from()))) {
                mutualPairs++;
            }
            // a -> b -> c with no a -> c. a == c is excluded: it asks for reflexivity,
            // which is counted on its own and is a convention rather than a finding.
            for (int third : out.get(edge.to())) {
                if (third != edge.from() && !stated.containsKey(new Edge(edge.from(), third))) {
                    transitivityGaps++;
                }
            }
        }

        Map<Integer, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            grouped.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(i);
        }
        List<Component> components = new ArrayList<>();
        for (List<Integer> group : grouped.values()) {
            List<Viewable> members = new ArrayList<>();
            List<Viewable> origins = new ArrayList<>();
            List<Viewable> terminals = new ArrayList<>();
            boolean boundary = false;
            for (int i : group) {
                members.add(nodes.get(i));
                if (inDegree[i] == 0) origins.add(nodes.get(i));
                if (outDegree[i] == 0) terminals.add(nodes.get(i));
                boundary |= boundaryNodes.contains(i);
            }
            components.add(new Component(members, origins, terminals, boundary));
        }
        components.sort((left, right) -> Integer.compare(right.size(), left.size()));

        return new RelationProfile(forward, inverse, nodes.size(), stated.size(),
                bothWays, List.copyOf(oneWay), reflexive, mutualPairs, transitivityGaps,
                Arrays.stream(outDegree).max().orElse(0),
                Arrays.stream(inDegree).max().orElse(0),
                dangling, List.copyOf(leaving), List.copyOf(components),
                stronglyConnected(nodes, out));
    }

    /**
     * The strongly connected components larger than one member.
     *
     * <p>These are the equivalence classes of the directed relation itself, and what they
     * MEAN is the relation's own character, which is why this reports them rather than
     * naming them. For a symmetric relation they are its groups and coincide with the
     * components. For an order-like one — subclass, succession — a single member is the
     * only correct answer and anything larger is a data error.
     *
     * <p>Iterative rather than recursive: the recursion depth is the length of a chain,
     * and a chain is exactly what this is used on.
     */
    private static List<List<Viewable>> stronglyConnected(
            List<Viewable> nodes, List<List<Integer>> out) {
        int size = nodes.size();
        int[] index = new int[size];
        int[] low = new int[size];
        boolean[] onStack = new boolean[size];
        Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        Deque<int[]> frames = new ArrayDeque<>();
        List<List<Viewable>> found = new ArrayList<>();
        int counter = 0;
        for (int root = 0; root < size; root++) {
            if (index[root] != -1) continue;
            index[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;
            frames.push(new int[] {root, 0});
            while (!frames.isEmpty()) {
                int[] frame = frames.peek();
                int node = frame[0];
                List<Integer> next = out.get(node);
                if (frame[1] < next.size()) {
                    int child = next.get(frame[1]++);
                    if (index[child] == -1) {
                        index[child] = low[child] = counter++;
                        stack.push(child);
                        onStack[child] = true;
                        frames.push(new int[] {child, 0});
                    } else if (onStack[child]) {
                        low[node] = Math.min(low[node], index[child]);
                    }
                    continue;
                }
                frames.pop();
                if (!frames.isEmpty()) {
                    int caller = frames.peek()[0];
                    low[caller] = Math.min(low[caller], low[node]);
                }
                if (low[node] == index[node]) {
                    List<Viewable> component = new ArrayList<>();
                    int member;
                    do {
                        member = stack.pop();
                        onStack[member] = false;
                        component.add(nodes.get(member));
                    } while (member != node);
                    if (component.size() > 1) found.add(List.copyOf(component));
                }
            }
        }
        return List.copyOf(found);
    }

    private static int find(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left);
        int b = find(parent, right);
        if (a != b) parent[a] = b;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
