package wikidata.explore.compiled;

import wikidata.explore.model.Selection;
import wikidata.explore.model.EntityRepresentationRule;

import java.util.*;

/**
 * Immutable, validated runtime view of an editable project model.
 */
public final class CompiledProjectModel {

    private final String name;
    private final int generationDepth;
    private final String rootClassName;
    private final String rootClassId;
    private final List<CompiledClass> classes;
    private final Map<String, CompiledClass> classesByLowerName;
    private final Map<String, CompiledClass> classesById;
    private final Map<String, Set<String>> representationTargetsByRole;

    // Named non-product Selections (vocabularies/populations) a production
    // references — carried as-is (they are plain value objects, nothing to
    // compile). Empty for every classic model.
    private final List<Selection> selections;
    private final Map<String, Selection> selectionsByLowerName;
    private final Map<String, Selection> selectionsById;

    public CompiledProjectModel(
            String name,
            int generationDepth,
            String rootClassId,
            String rootClassName,
            List<CompiledClass> classes) {
        this(name, generationDepth, rootClassId, rootClassName, classes, List.of());
    }

    public CompiledProjectModel(
            String name,
            int generationDepth,
            String rootClassId,
            String rootClassName,
            List<CompiledClass> classes,
            List<Selection> selections) {
        this(name, generationDepth, rootClassId, rootClassName, classes, selections,
                List.of());
    }

    public CompiledProjectModel(
            String name,
            int generationDepth,
            String rootClassId,
            String rootClassName,
            List<CompiledClass> classes,
            List<Selection> selections,
            List<EntityRepresentationRule> representationRules) {

        this.name = clean(name);
        this.generationDepth = Math.max(0, generationDepth);
        this.rootClassId = clean(rootClassId);
        this.rootClassName = clean(rootClassName);
        this.classes = classes == null ? List.of() : List.copyOf(classes);

        LinkedHashMap<String, CompiledClass> index = new LinkedHashMap<>();
        for (CompiledClass clazz : this.classes) {
            index.putIfAbsent(
                    clazz.className().toLowerCase(Locale.ROOT),
                    clazz);
        }
        classesByLowerName = Collections.unmodifiableMap(index);
        LinkedHashMap<String, CompiledClass> idIndex = new LinkedHashMap<>();
        for (CompiledClass clazz : this.classes) {
            if (!clazz.declarationId().isBlank()) {
                idIndex.putIfAbsent(clazz.declarationId(), clazz);
            }
        }
        classesById = Collections.unmodifiableMap(idIndex);

        LinkedHashMap<String, Set<String>> representations = new LinkedHashMap<>();
        if (representationRules != null) {
            for (EntityRepresentationRule rule : representationRules) {
                if (rule == null || !rule.isConfigured()) continue;
                findClassById(rule.roleClassId()).or(() -> findClass(rule.roleClassName()))
                        .ifPresent(role -> findClassById(rule.representationClassId())
                                .or(() -> findClass(rule.representationClassName()))
                                .ifPresent(target -> representations
                                        .computeIfAbsent(nameKey(role.className()), ignored ->
                                                new LinkedHashSet<>())
                                        .add(nameKey(target.className()))));
            }
        }
        LinkedHashMap<String, Set<String>> frozenRepresentations = new LinkedHashMap<>();
        representations.forEach((role, targets) ->
                frozenRepresentations.put(role, Set.copyOf(targets)));
        representationTargetsByRole = Collections.unmodifiableMap(frozenRepresentations);

        this.selections = selections == null ? List.of() : List.copyOf(selections);
        LinkedHashMap<String, Selection> selIndex = new LinkedHashMap<>();
        for (Selection s : this.selections) {
            if (s != null && !s.name().isBlank()) {
                selIndex.putIfAbsent(s.name().toLowerCase(Locale.ROOT), s);
            }
        }
        selectionsByLowerName = Collections.unmodifiableMap(selIndex);
        LinkedHashMap<String, Selection> selectionIdIndex = new LinkedHashMap<>();
        for (Selection selection : this.selections) {
            if (selection != null && !selection.declarationId().isBlank()) {
                selectionIdIndex.putIfAbsent(selection.declarationId(), selection);
            }
        }
        selectionsById = Collections.unmodifiableMap(selectionIdIndex);
    }

    public String name() { return name; }
    public int generationDepth() { return generationDepth; }
    public String rootClassName() { return rootClassName; }
    public String rootClassId() { return rootClassId; }
    public List<CompiledClass> classes() { return classes; }
    public List<Selection> selections() { return selections; }

    public Optional<Selection> findSelection(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                selectionsByLowerName.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<Selection> findSelectionById(String declarationId) {
        return Optional.ofNullable(selectionsById.get(clean(declarationId)));
    }

    public CompiledClass rootClass() {
        return findClassById(rootClassId).or(() -> findClass(rootClassName))
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Compiled root class is missing: "
                                        + rootClassName));
    }

    public Optional<CompiledClass> findClass(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                classesByLowerName.get(
                        name.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<CompiledClass> findClassById(String declarationId) {
        return Optional.ofNullable(classesById.get(clean(declarationId)));
    }

    public boolean mayRepresent(String roleClassName, String className) {
        return representationTargetsByRole
                .getOrDefault(nameKey(roleClassName), Set.of())
                .contains(nameKey(className));
    }

    private static String nameKey(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
