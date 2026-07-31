package quiz.transform.ui;

import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link DomainModel} that layers PROJECT-derived classes over a base domain.
 * Derived types join {@link #types()} and their fields join the pool, so an
 * operation can consume a class produced by an earlier PROJECT — the composable
 * transform graph. Derived instances are added to {@link #instances()} for the view.
 */
public final class WorkingDomain implements DomainModel, SchemaView,
        quiz.curation.Curatable, quiz.curation.Mergeable {

    private final DomainModel base;
    private final Map<String, DerivedClass> derived = new LinkedHashMap<>();
    // Empty fields DECLARED on a base class (the "New field" op) — a schema act, distinct
    // from PROJECT-derived classes. They join the field pool and the shape sample so they
    // show at 0% coverage, ready to be filled (e.g. via Find Data).
    private final Map<String, List<DomainField>> declaredFields = new LinkedHashMap<>();
    private final Map<String, Viewable> augmentedSample = new HashMap<>();
    private final Map<String, quiz.transform.EditableGroup> groupRoots =
            new LinkedHashMap<>();
    // The sorted member-identifier signature the cached root was last refreshed against,
    // so editableGroupRoot() only re-derives scope + recomputes produced descendants when
    // the live instance set actually changed (identity-resolve, merge, "forget", derive) —
    // leaving a stable tree, and the user's hand-nested groups, untouched otherwise.
    private final Map<String, List<String>> groupRootSignatures = new HashMap<>();

    public WorkingDomain(DomainModel base) {
        this.base = base;
    }

    /** Declare a new empty field on a base class so it appears in the field pool and at
     *  0% coverage — independent of how it is later filled. Supported only for dynamic
     *  (snapshot-backed) samples whose shape can carry it; returns false otherwise. */
    public boolean addField(String type, DomainField field) {
        if (type == null || field == null || derived.containsKey(type)) {
            return false;
        }
        if (!(base.representativeSample(type) instanceof WikidataDynamicObject)) {
            return false;
        }
        declaredFields.computeIfAbsent(type, t -> new ArrayList<>()).add(field);
        augmentedSample.remove(type);
        return true;
    }

    @Override public javax.swing.JComponent schemaView() {
        return base instanceof SchemaView sv ? sv.schemaView() : null;
    }

    @Override public quiz.curation.ManualCuration curation() {
        return base instanceof quiz.curation.Curatable c ? c.curation() : null;
    }

    /** Apply a merge to the REAL base pool — not the throwaway combined copy that
     *  {@link #instances()} returns — so the duplicate's removal takes effect live. */
    @Override public int applyMerge(quiz.curation.Merge merge) {
        return quiz.curation.Merges.apply(base.instances(), List.of(merge));
    }

    @Override public Collection<? extends Viewable> mergeableInstances() {
        return base.instances();
    }

    public void add(DerivedClass d) {
        if (d != null && d.type() != null && !d.type().isBlank()) {
            derived.put(d.type(), d);
        }
    }

    public boolean isDerived(String type) {
        return derived.containsKey(type);
    }

    public quiz.transform.EditableGroup editableGroupRoot(String type) {
        if (type == null) return null;
        quiz.transform.EditableGroup root =
                groupRoots.computeIfAbsent(type, this::createGroupRoot);
        List<? extends Viewable> live = instances().stream()
                .filter(value -> type.equals(value.typeName()))
                .toList();
        List<String> signature = live.stream()
                .map(Viewable::getIdentifier).sorted().toList();
        if (!signature.equals(groupRootSignatures.get(type))) {
            // The scope changed: re-derive the root's members and recompute every
            // rule-produced descendant against the fresh scope, so the workbench never
            // renders/validates/resolves against a stale membership snapshot.
            root.replaceMembers(live);
            root.reproduceDescendants();
            groupRootSignatures.put(type, signature);
        }
        return root;
    }

    private quiz.transform.EditableGroup createGroupRoot(String type) {
        objectview.group.ViewableGroup<?> declared = base.groupRoot(type);
        // Members + produced descendants are (re)derived by editableGroupRoot's refresh;
        // this only reconstructs the declared/empty tree shape.
        return declared == null
                ? new quiz.transform.EditableGroup("All " + type)
                : quiz.transform.EditableGroup.copyOf(declared);
    }

    @Override public List<String> types() {
        List<String> t = new ArrayList<>(base.types());
        t.addAll(derived.keySet());
        return t;
    }

    @Override public List<DomainField> fields(String type) {
        return DomainSchemas.fields(this, type);
    }

    @Override public FieldSchema fieldSchema(String type) {
        DerivedClass d = derived.get(type);
        if (d != null) {
            return d.fieldSchema();
        }
        FieldSchema baseSchema = base.fieldSchema(type);
        List<DomainField> extra = declaredFields.get(type);
        if (extra == null || extra.isEmpty()) {
            return baseSchema;
        }
        Map<String, FieldRef> combined = new LinkedHashMap<>();
        if (baseSchema != null) {
            for (FieldRef field : baseSchema.fields()) {
                combined.put(field.name(), field);
            }
        }
        for (FieldRef field : DomainSchemas.flatSchema(extra).fields()) {
            combined.put(field.name(), field);
        }
        List<FieldRef> immutable = List.copyOf(combined.values());
        return () -> immutable;
    }

    @Override public java.util.Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    @Override public Viewable representativeSample(String type) {
        // Base types get the base's authoritative shape sample; a PROJECT-derived type
        // falls back to the interface default over the combined instances.
        if (derived.containsKey(type)) {
            return DomainModel.super.representativeSample(type);
        }
        List<DomainField> extra = declaredFields.get(type);
        if (extra == null || extra.isEmpty()) {
            return base.representativeSample(type);
        }
        // Add each declared field to the SHAPE sample as an empty placeholder so the
        // sample-driven field/coverage UIs enumerate it. Real instances still lack it, so
        // it reads as 0% coverage (a gap to fill) — the placeholder is shape-only.
        return augmentedSample.computeIfAbsent(type, t -> {
            Viewable sample = base.representativeSample(t);
            if (sample instanceof WikidataDynamicObject wdo) {
                for (DomainField field : extra) {
                    if (wdo.get(field.field()) == null) {
                        wdo.put(field.field(), "");
                    }
                }
            }
            return sample;
        });
    }

    @Override public Collection<? extends Viewable> instances() {
        List<Viewable> all = new ArrayList<>(base.instances());
        for (DerivedClass d : derived.values()) {
            all.addAll(d.instances());
        }
        return all;
    }

    @Override
    public Collection<? extends Viewable> memberRoots() {
        List<Viewable> roots = new ArrayList<>(base.memberRoots());
        for (DerivedClass derivedClass : derived.values()) {
            roots.addAll(derivedClass.instances());
        }
        return roots;
    }

    @Override
    public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        return DomainModel.super.groupRoots();
    }

    @Override
    public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return types().stream()
                .map(type -> new objectview.viewconfig.DomainGroupRoot(
                        type, editableGroupRoot(type)))
                .toList();
    }

    @Override public Class<? extends Viewable> universe() {
        // Broad enough to keep BOTH base instances and PROJECT-derived
        // DynamicViewables (which the base universe, e.g. a snapshot's WDO, excludes).
        return Viewable.class;
    }
}
