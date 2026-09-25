package wikidata.explore.model;

import objectview.Viewable;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declarations that exist in the working project now.
 *
 * <p>Class, graph, population and vocabulary used to acquire separate lifecycle rules
 * because their data happens to be stored in different physical shapes. They are one
 * thing for load/show/save: a construct with a stable declaration id, a current name,
 * and an optional instance artifact. This inventory is that single answer.
 */
public final class ConstructInventory {
    public enum Kind { CLASS, GRAPH, POPULATION, SELECTION }

    public record Entry(String declarationId, String name, Kind kind) {
        public Entry {
            declarationId = clean(declarationId);
            name = clean(name);
            java.util.Objects.requireNonNull(kind, "A construct needs a kind");
            if (declarationId.isBlank()) {
                throw new IllegalArgumentException("A construct needs a declaration id");
            }
            if (name.isBlank()) throw new IllegalArgumentException("A construct needs a name");
        }
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final Set<String> classNames;
    private final Set<String> graphNames;

    private ConstructInventory(List<Entry> values) {
        LinkedHashMap<String, Entry> indexed = new LinkedHashMap<>();
        for (Entry entry : values) {
            Entry previous = indexed.putIfAbsent(entry.declarationId(), entry);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Two constructs have declaration id " + entry.declarationId());
            }
        }
        byId = Map.copyOf(indexed);
        entries = List.copyOf(indexed.values());
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        LinkedHashSet<String> graphs = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.CLASS || entry.kind() == Kind.GRAPH) {
                classes.add(entry.name());
            }
            if (entry.kind() == Kind.GRAPH) graphs.add(entry.name());
        }
        classNames = Set.copyOf(classes);
        graphNames = Set.copyOf(graphs);
    }

    public static ConstructInventory of(GeneratedProjectModel project) {
        if (project == null) return new ConstructInventory(List.of());
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) continue;
            entries.add(new Entry(clazz.declarationId(), clazz.className(),
                    clazz.classKind() == ClassKind.GRAPH ? Kind.GRAPH : Kind.CLASS));
        }
        for (Selection selection : project.selections()) {
            if (selection == null) continue;
            entries.add(new Entry(selection.declarationId(), selection.name(),
                    selection instanceof PopulationSelection
                            ? Kind.POPULATION : Kind.SELECTION));
        }
        return new ConstructInventory(entries);
    }

    public List<Entry> entries() { return entries; }
    public Set<String> classNames() { return classNames; }
    public Set<String> graphNames() { return graphNames; }
    public boolean containsId(String declarationId) { return byId.containsKey(clean(declarationId)); }

    /**
     * Retracts class claims whose declarations no longer exist, and says so in its name:
     * this EDITS the objects it is given. The objects stay in the shared pool as possible
     * reference targets; they merely stop being members/roots.
     *
     * <p>It was called from a method named for what it returned, so asking an inventory
     * which objects are its member roots quietly rewrote the pool those roots came from.
     * The two callers that want the retraction — loading a project, and saving one — now
     * ask for it.
     */
    public List<WikidataDynamicObject> retractRemovedClaims(
            Collection<WikidataDynamicObject> objects) {
        if (objects == null || objects.isEmpty()) return List.of();
        List<WikidataDynamicObject> projected = objects.stream()
                .filter(java.util.Objects::nonNull).toList();
        for (WikidataDynamicObject object : projected) {
            for (String claimed : List.copyOf(object.directClassNames())) {
                if (!WikidataDynamicObject.isInternalClassName(claimed)
                        && !classNames.contains(claimed)) {
                    object.removeClass(claimed);
                }
            }
        }
        return projected;
    }

    /**
     * The active top-level members saved and shown for the current declarations.
     *
     * <p>A filter: it reads the objects and changes none of them. Retracting the claims
     * of removed declarations is {@link #retractRemovedClaims}, which the save path calls
     * for itself.
     */
    public List<WikidataDynamicObject> memberRoots(
            Collection<WikidataDynamicObject> objects) {
        if (objects == null) return List.of();
        return objects.stream().filter(java.util.Objects::nonNull)
                .filter(value -> !value.isPart())
                .filter(value -> value.directClassNames().stream().anyMatch(classNames::contains))
                .toList();
    }

    /** The materialized counterpart used by Show instances. */
    public List<Viewable> visibleInstances(Collection<? extends Viewable> instances) {
        if (instances == null || instances.isEmpty()) return List.of();
        return instances.stream().filter(java.util.Objects::nonNull)
                .filter(value -> value.directClassNames().stream().anyMatch(classNames::contains)
                        || classNames.contains(value.typeName()))
                .map(Viewable.class::cast).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
