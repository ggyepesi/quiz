package wikidata.explore.compiled;

import java.util.*;

/**
 * Immutable, validated runtime view of an editable project model.
 */
public final class CompiledProjectModel {

    private final String name;
    private final int generationDepth;
    private final String rootClassName;
    private final List<CompiledClass> classes;
    private final Map<String, CompiledClass> classesByLowerName;

    public CompiledProjectModel(
            String name,
            int generationDepth,
            String rootClassName,
            List<CompiledClass> classes) {

        this.name = clean(name);
        this.generationDepth = Math.max(0, generationDepth);
        this.rootClassName = clean(rootClassName);
        this.classes = classes == null ? List.of() : List.copyOf(classes);

        LinkedHashMap<String, CompiledClass> index = new LinkedHashMap<>();
        for (CompiledClass clazz : this.classes) {
            index.putIfAbsent(
                    clazz.className().toLowerCase(Locale.ROOT),
                    clazz);
        }
        classesByLowerName = Collections.unmodifiableMap(index);
    }

    public String name() { return name; }
    public int generationDepth() { return generationDepth; }
    public String rootClassName() { return rootClassName; }
    public List<CompiledClass> classes() { return classes; }

    public CompiledClass rootClass() {
        return findClass(rootClassName)
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

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
