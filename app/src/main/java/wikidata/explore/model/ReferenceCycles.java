package wikidata.explore.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which ENTITY fields lie on a cycle of class references (#394) — one question asked of
 * {@link ClassDependencies}, like {@link ProductionChain}.
 *
 * <p>A field whose values become members of its target class can grow a population;
 * when that target can reach the field's class again, the growth never stops on its own.
 * {@code Person.spouse -> Person} was the one-class case. Mythology's
 * {@code Character.episodes -> Episode} with {@code Episode.characters -> Character} is the
 * two-class case: every semantic-convergence pass made the characters of new episodes
 * Characters and the episodes of new characters Episodes, with nothing visible bounding it.
 * A field on such a cycle links members another construct supplies; recursive growth
 * belongs to an explicit graph construct with a visible boundary (directive 27).
 *
 * <p>A class hierarchy is one place here: a member of a subclass is a member of its base,
 * and a base member may be classified into a subclass, so {@link ClassDependencies.Kind#EXTENDS}
 * is followed both ways. That makes an inherited copy of {@code Position.superClasses} on
 * {@code PositionWithHolders} exactly as cyclic as the original, and a subclass field typed
 * as its own base ({@code Politician.mentor -> Person}) a cycle too.
 *
 * <p>Only a field that reads its values from the source can grow anything. Derived fields
 * — an inverse, a union, an owner reference, a statement's subject or object, an owned
 * component, a companion match — are produced from members that already exist.
 */
public final class ReferenceCycles {

    private static final Set<FieldProductionKind> DERIVED = EnumSet.of(
            FieldProductionKind.INVERT, FieldProductionKind.UNION,
            FieldProductionKind.OWNER_REFERENCE, FieldProductionKind.COMPANION_MATCH,
            FieldProductionKind.STATEMENT_SUBJECT, FieldProductionKind.STATEMENT_OBJECT,
            FieldProductionKind.STATEMENT_PARTICIPANTS, FieldProductionKind.OWNED_COMPONENT);

    private final GeneratedProjectModel project;
    /** class name -> every class its members can make members of, transitively. */
    private final Map<String, Set<String>> reach;

    private ReferenceCycles(GeneratedProjectModel project, Map<String, Set<String>> reach) {
        this.project = project;
        this.reach = reach;
    }

    public static ReferenceCycles of(GeneratedProjectModel project) {
        Map<String, Set<String>> next = new LinkedHashMap<>();
        if (project != null) {
            for (GeneratedClassModel clazz : project.classes()) {
                if (clazz == null) continue;
                for (ClassDependencies.Edge edge
                        : ClassDependencies.dependenciesOf(clazz, project)) {
                    String from = edge.dependent().className();
                    String to = edge.dependency().className();
                    if (edge.kind() == ClassDependencies.Kind.EXTENDS) {
                        link(next, from, to);
                        link(next, to, from);
                    } else if (edge.kind() == ClassDependencies.Kind.REFERENCES
                            && readsValues(field(clazz, edge.site()))) {
                        link(next, from, to);
                    }
                }
            }
        }
        Map<String, Set<String>> reach = new LinkedHashMap<>();
        for (String start : next.keySet()) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(next.get(start));
            while (!todo.isEmpty()) {
                String c = todo.pop();
                if (seen.add(c)) todo.addAll(next.getOrDefault(c, Set.of()));
            }
            reach.put(start, Set.copyOf(seen));
        }
        return new ReferenceCycles(project, Map.copyOf(reach));
    }

    /**
     * Whether {@code field}, on members of {@code ownerClass} (declared there or
     * inherited), lies on a reference cycle: it reads values into a class from which
     * {@code ownerClass} can be reached again.
     */
    public boolean onCycle(String ownerClass, GeneratedFieldModel field) {
        if (field == null || ownerClass == null || !readsValues(field)) return false;
        GeneratedClassModel target = project == null ? null
                : project.findClass(field.entityClassName());
        if (target == null) return false;
        String to = target.className();
        return to.equals(ownerClass)
                || reach.getOrDefault(to, Set.of()).contains(ownerClass);
    }

    private static boolean readsValues(GeneratedFieldModel field) {
        return field != null && !DERIVED.contains(field.mapping().productionKind());
    }

    /** The exact field a reference edge was derived from. The dependency graph stores
     *  the complete path: two nested fields may legitimately have the same leaf name
     *  but different production kinds. */
    private static GeneratedFieldModel field(GeneratedClassModel clazz, String path) {
        if (clazz == null || path == null || path.isBlank()) return null;
        java.util.List<GeneratedFieldModel> fields = clazz.fields();
        GeneratedFieldModel found = null;
        for (String segment : path.split("\\.")) {
            found = null;
            for (GeneratedFieldModel candidate : fields) {
                if (candidate != null && segment.equals(candidate.name())) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) return null;
            fields = found.fields();
        }
        return found;
    }

    private static void link(Map<String, Set<String>> next, String from, String to) {
        next.computeIfAbsent(from, ignored -> new LinkedHashSet<>()).add(to);
        next.computeIfAbsent(to, ignored -> new LinkedHashSet<>());
    }
}
