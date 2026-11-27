package domain.curation;

import domain.runtime.WikidataDynamicObject;
import objectview.field.FieldSchema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Materializes accepted source-derived instances into the ordinary dynamic object pool. */
public final class CuratedInstances {
    private CuratedInstances() { }

    /** The pooled curated objects and any references that could not be assigned. */
    public record Result(List<WikidataDynamicObject> instances, List<Unresolved> unresolved) {
        public Result {
            instances = List.copyOf(instances);
            unresolved = List.copyOf(unresolved);
        }
    }

    /**
     * A reference of an accepted instance that was not applied, as data: which instance,
     * which field (by its label), the identities it named, and whether the field took one
     * value while several were named. Presenting it — names, links — is the UI's job.
     */
    public record Unresolved(CuratedInstance instance, String fieldLabel,
                             List<Target> targets, boolean ambiguous) {
        public Unresolved {
            targets = List.copyOf(targets);
        }
    }

    /** One named identity and the loaded instance it resolved to, or {@code null}. */
    public record Target(CuratedInstance.Reference reference, WikidataDynamicObject loaded) { }

    /** Adds or refreshes the supplied curated instances, resolving references by exact class. */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances) {
        return apply(pool, instances, type -> null);
    }

    /**
     * Adds or refreshes the supplied curated instances. A reference to a class resolves to
     * a loaded instance of that class or of one of its subclasses under the domain's
     * {@code baseType} hierarchy: a story's NorseCharacter reference names the loaded
     * NorseDeity, not "nothing loaded".
     */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances,
            Function<String, String> baseType) {
        return apply(pool, instances, baseType, type -> null);
    }

    /**
     * A curated sidecar stores references as identities, not a runtime value shape.
     * Replay uses the current class declaration: one object for a single field and a
     * list for a collection, even when that collection holds only one object.
     */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances,
            Function<String, String> baseType,
            Function<String, FieldSchema> schemas) {
        if (pool == null || instances == null || instances.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        TypedInstanceIndex<WikidataDynamicObject> instancesByType =
                TypedInstanceIndex.of(pool);

        List<CuratedInstance> declarations = instances.stream()
                .filter(java.util.Objects::nonNull).toList();
        List<WikidataDynamicObject> materialized = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        for (CuratedInstance declaration : declarations) {
            WikidataDynamicObject value =
                    instancesByType.get(declaration.type(), declaration.id());
            if (value == null) {
                value = new WikidataDynamicObject(
                        declaration.id(), declaration.displayName());
                value.type(declaration.type());
                pool.add(value);
                instancesByType.add(value);
            } else if (!declaration.displayName().isBlank()) {
                value.name(declaration.displayName());
            }
            ValueSource source = declaration.source();
            value.acceptedSource(source == null ? null : source.recordLink());
            materialized.add(value);
        }

        for (int index = 0; index < materialized.size(); index++) {
            CuratedInstance declaration = declarations.get(index);
            WikidataDynamicObject target = materialized.get(index);
            FieldSchema schema = schemas.apply(declaration.type());
            for (CuratedInstance.FieldValue field : declaration.fields()) {
                var declared = schema == null ? null : schema.field(field.field());
                boolean single = declared != null && !declared.collection();
                Object value = field.references().isEmpty()
                        ? field.value()
                        : references(declaration, field, instancesByType, baseType,
                                declared == null ? field.field() : declared.label(), single, unresolved);
                if (value != null) target.put(field.field(), value);
            }
        }
        return new Result(materialized, unresolved);
    }

    /**
     * Gives each curated instance its class's schema where it has none. A snapshot
     * instance receives its schema when the snapshot loads; one created here does not,
     * and ObjectView lists fields from the schema, never from the values present — so a
     * curated NorseStory showed only provenance fields while holding its title and text.
     */
    public static void attachSchemas(Collection<WikidataDynamicObject> instances,
            java.util.function.Function<String, objectview.field.FieldSchema> schemaOf) {
        if (instances == null || schemaOf == null) return;
        for (WikidataDynamicObject instance : instances) {
            if (instance == null || instance.dynamicFieldSchema() != null) continue;
            instance.dynamicFieldSchema(schemaOf.apply(instance.typeName()));
        }
    }

    /**
     * The pooled objects a curated field names. A reference that names nothing stays
     * unresolved and is reported: a stand-in carrying only the QID would be stamped as a
     * class member, indistinguishable from an instance whose acquisition failed.
     */
    private static Object references(
            CuratedInstance declaration, CuratedInstance.FieldValue field,
            TypedInstanceIndex<WikidataDynamicObject> index,
            Function<String, String> baseType,
            String fieldLabel,
            boolean single,
            List<Unresolved> unresolved) {
        if (single) {
            var named = field.references().stream().distinct().toList();
            if (named.size() > 1) {
                unresolved.add(new Unresolved(declaration, fieldLabel, named.stream()
                        .map(reference -> new Target(reference, index.getAssignable(
                                reference.type(), reference.id(), baseType)))
                        .toList(), true));
                return null;
            }
        }
        List<WikidataDynamicObject> result = new ArrayList<>();
        for (CuratedInstance.Reference reference : field.references()) {
            WikidataDynamicObject value =
                    index.getAssignable(reference.type(), reference.id(), baseType);
            if (value == null) {
                unresolved.add(new Unresolved(declaration, fieldLabel,
                        List.of(new Target(reference, null)), false));
                continue;
            }
            if (!result.contains(value)) result.add(value);
        }
        if (result.isEmpty()) return null;
        return single ? result.getFirst() : List.copyOf(result);
    }

}
