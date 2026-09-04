package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which classes a class's existence depends on — one graph, however many constructs
 * contribute edges to it.
 *
 * <p>Four constructs contribute. An owned class has one part made per owning instance; an
 * aggregate reduces its source class; a subclass extends its base; and any ENTITY field
 * makes its class need the class it targets. Each was walked separately — the validator
 * had a cycle check per kind, {@link MembershipPattern} follows owner edges, {@code
 * ModelAggregates} follows source edges, {@code ClassImportPlan} follows base and field
 * edges — and every one of them stops at the first edge of a kind it does not know. So a
 * chain that ALTERNATES kinds closes a cycle none of them can see: it passes validation
 * and then fails downstream, at whichever walker happens to be running.
 *
 * <p>The kinds must stay distinguishable rather than merge, because they do not agree
 * about cycles. A production or extension cycle is a broken model; a reference cycle is
 * ordinary — {@code Person.spouse -> Person} is a fact about people. That is what
 * {@link Kind#acyclic()} says, and it is why one graph with kinds beats one graph.
 *
 * <p>Edges are derived in exactly one place, {@link #dependenciesOf}, which is also the
 * one place a fifth contributor is added. Everything else — the chain a sample walks, the
 * cycles the validator reports — asks this graph and gains that contributor for free.
 */
public final class ClassDependencies {

    private ClassDependencies() { }

    /** Why one class depends on another. One constant per contributing construct. */
    public enum Kind {
        /** One part per owning instance, carrying the owner's identifier. */
        OWNED("is owned by", true, true),
        /** Groups reduced from the source class by a configured key. */
        AGGREGATED("is grouped from", true, true),
        /** A subclass and its base: a declaration dependency, not a production one. */
        EXTENDS("extends", false, true),
        /**
         * An ENTITY field's target class.
         *
         * <p>The only kind that may legitimately cycle, and the reason kinds exist at
         * all: {@code Person.spouse -> Person} is a fact about people, not a broken
         * model, and a graph that could not tell it from a production cycle would have
         * to refuse one or miss the other.
         */
        REFERENCES("references", false, false);

        private final String phrase;
        private final boolean production;
        private final boolean acyclic;

        Kind(String phrase, boolean production, boolean acyclic) {
            this.phrase = phrase;
            this.production = production;
            this.acyclic = acyclic;
        }

        /** How the edge reads in a sentence about the model. */
        public String phrase() {
            return phrase;
        }

        /** Whether the dependent has no population of its own without the dependency. */
        public boolean production() {
            return production;
        }

        /** Whether a cycle over this kind is a broken model. */
        public boolean acyclic() {
            return acyclic;
        }
    }

    /**
     * {@code dependent} cannot be produced until {@code dependency} has been.
     *
     * @param site what carries the dependency — a field name for OWNED, blank otherwise
     */
    public record Edge(GeneratedClassModel dependent, GeneratedClassModel dependency,
                       Kind kind, String site) {
        public Edge {
            site = site == null ? "" : site.trim();
        }

        @Override public String toString() {
            return dependent.className() + " " + kind.phrase() + " "
                    + dependency.className() + (site.isEmpty() ? "" : " (" + site + ")");
        }
    }

    /**
     * Everything {@code clazz} depends on directly.
     *
     * <p>An owned class produced at several sites has one edge per site, and those may
     * name the same owner twice — two sites on one owner agree about the population, and
     * collapsing them here would lose which fields produce it.
     *
     * <p>THE place a new contributor is added. A construct that gives a class its
     * members from another class belongs in this method and nowhere else.
     */
    public static List<Edge> dependenciesOf(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || project == null) return List.of();
        List<Edge> edges = new ArrayList<>();

        for (MembershipPattern.OwnedBy site : MembershipPattern.ownedBy(clazz, project)) {
            GeneratedClassModel owner = project.findClass(site.ownerClass());
            if (owner != null) {
                edges.add(new Edge(clazz, owner, Kind.OWNED, site.fieldName()));
            }
        }

        AggregateClassSource source = clazz.aggregateSource();
        if (source != null && !clean(source.sourceClassName()).isEmpty()) {
            GeneratedClassModel from = project.findClass(source.sourceClassName());
            if (from != null) edges.add(new Edge(clazz, from, Kind.AGGREGATED, ""));
        }

        GeneratedClassModel base = project.findClass(clazz.baseClassName());
        if (base != null && base != clazz) {
            edges.add(new Edge(clazz, base, Kind.EXTENDS, ""));
        } else if (!clean(clazz.baseClassName()).isEmpty()
                && clean(clazz.baseClassName()).equals(clean(clazz.className()))) {
            // A class naming itself as its base is a one-class cycle, and findClass
            // returning the class itself would otherwise hide it as "no base".
            edges.add(new Edge(clazz, clazz, Kind.EXTENDS, ""));
        }

        referenceEdges(clazz, project, clazz.fields(), edges);

        return List.copyOf(edges);
    }

    /** An ENTITY field's target, at any depth — nested fields target classes too. */
    private static void referenceEdges(GeneratedClassModel clazz,
            GeneratedProjectModel project, List<GeneratedFieldModel> fields,
            List<Edge> edges) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            GeneratedClassModel target = project.findClass(field.entityClassName());
            if (target != null) {
                Edge edge = new Edge(clazz, target, Kind.REFERENCES, field.name());
                if (!edges.contains(edge)) edges.add(edge);
            }
            referenceEdges(clazz, project, field.fields(), edges);
        }
    }

    /** Only the edges whose kind a caller cares about. */
    public static List<Edge> dependenciesOf(GeneratedClassModel clazz,
            GeneratedProjectModel project, java.util.function.Predicate<Kind> kinds) {
        return dependenciesOf(clazz, project).stream()
                .filter(edge -> kinds.test(edge.kind())).toList();
    }

    /**
     * Every dependency cycle in the project, each as the class names it runs through.
     *
     * <p>Reported once per cycle rather than once per class on it: a two-class cycle
     * found from both ends is one fact about the model, and saying it twice reads as two
     * problems to fix.
     */
    public static List<Cycle> cycles(GeneratedProjectModel project) {
        if (project == null) return List.of();
        List<Cycle> found = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        Set<String> settled = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) continue;
            walk(clazz, project, new LinkedHashMap<>(), settled, reported, found);
        }
        return List.copyOf(found);
    }

    /**
     * A cycle over the kinds that must not have one.
     *
     * @param classes the class names it runs through, closed (first name repeated last)
     * @param kinds   the edge kinds it uses — a modeller reading "extends" looks
     *                somewhere quite different than one reading "is grouped from"
     */
    public record Cycle(List<String> classes, Set<Kind> kinds) {
        public Cycle {
            classes = List.copyOf(classes);
            kinds = Set.copyOf(kinds);
        }

        /** {@code Prize → Award → Prize (is grouped from, is owned by)} */
        @Override public String toString() {
            return String.join(" → ", classes) + " ("
                    + kinds.stream().map(Kind::phrase).sorted()
                            .collect(java.util.stream.Collectors.joining(", ")) + ")";
        }
    }

    private static void walk(GeneratedClassModel clazz, GeneratedProjectModel project,
            LinkedHashMap<String, Edge> path, Set<String> settled,
            Set<String> reported, List<Cycle> found) {
        String name = clean(clazz.className());
        if (settled.contains(name)) return;
        if (path.containsKey(name)) {
            List<String> names = new ArrayList<>(path.keySet());
            List<Edge> edges = new ArrayList<>(path.values());
            int from = names.indexOf(name);
            List<String> cycle = new ArrayList<>(names.subList(from, names.size()));
            Set<Kind> kinds = new LinkedHashSet<>();
            for (Edge edge : edges.subList(from, edges.size())) {
                if (edge != null) kinds.add(edge.kind());
            }
            // Named by its members, not by where the walk entered it, so the same cycle
            // reached from two starting classes is recognised as the same cycle.
            if (reported.add(String.join(",", new java.util.TreeSet<>(cycle)))) {
                cycle.add(name);
                found.add(new Cycle(cycle, kinds));
            }
            return;
        }
        path.put(name, null);
        for (Edge edge : dependenciesOf(clazz, project)) {
            if (!edge.kind().acyclic()) continue;
            path.put(name, edge);
            walk(edge.dependency(), project, path, settled, reported, found);
        }
        path.remove(name);
        settled.add(name);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
