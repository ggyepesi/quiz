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
import domain.DelegatingDomainModel;
import domain.Derived;
import domain.DomainModel;
import domain.DomainSchemas;

/**
 * A {@link DomainModel} that layers PROJECT-derived classes over a base domain.
 * Derived types join {@link #types()} and their fields join the pool, so an
 * operation can consume a class produced by an earlier PROJECT — the composable
 * transform graph. Derived instances are added to {@link #instances()} for the view.
 */
public final class WorkingDomain extends DelegatingDomainModel implements SchemaView,
        quiz.curation.Curatable, quiz.curation.Mergeable,
        quiz.curation.FieldRulePromoter {

    private final Map<String, DerivedClass> derived = new LinkedHashMap<>();
    private final Map<String, String> subclassBases = new LinkedHashMap<>();
    private final java.util.IdentityHashMap<Viewable, java.util.Set<String>>
            assignedClasses = new java.util.IdentityHashMap<>();
    // Empty fields DECLARED on a base or subclass (the "New field" op) — a schema act,
    // distinct from PROJECT-derived classes. They join the field schema so they show at
    // 0% coverage, ready to be filled (e.g. via Find Data). Enumeration is schema-driven,
    // so no synthetic shape sample is needed.
    private final Map<String, List<FieldRef>> declaredFields = new LinkedHashMap<>();
    private final Map<String, quiz.transform.EditableGroup> groupRoots =
            new LinkedHashMap<>();
    // The sorted member-identifier signature the cached root was last refreshed against,
    // so editableGroupRoot() only re-derives scope + recomputes produced descendants when
    // the live instance set actually changed (identity-resolve, merge, "forget", derive) —
    // leaving a stable tree, and the user's hand-nested groups, untouched otherwise.
    private final Map<String, List<String>> groupRootSignatures = new HashMap<>();

    public WorkingDomain(DomainModel base) {
        super(base);
    }

    @Override public List<String> selectionNames() { return base.selectionNames(); }
    @Override public List<Viewable> selectionMembers(String name) {
        return base.selectionMembers(name);
    }
    @Override public boolean exposesEntityUniverse() { return base.exposesEntityUniverse(); }

    /** Declare a new empty field on a base class so it appears in the field pool and at
     *  0% coverage — independent of how it is later filled. Supported only for dynamic
     *  (snapshot-backed) samples whose shape can carry it; returns false otherwise. */
    public boolean addField(String type, FieldRef field) {
        if (type == null || field == null || derived.containsKey(type)) {
            return false;
        }
        Viewable sample = subclassBases.containsKey(type)
                ? instancesOf(type).stream().findFirst().orElse(null)
                : base.representativeSample(type);
        if (!(sample instanceof WikidataDynamicObject)) {
            return false;
        }
        quiz.curation.ManualCuration curation = curation();
        if (curation != null) {
            curation.putFieldDeclaration(type, field);
            try {
                curation.save();
            } catch (java.io.IOException unreadable) {
                curation.removeFieldDeclaration(type, field.name());
                return false;
            }
        }
        declaredFields.computeIfAbsent(type, t -> new ArrayList<>()).add(field);
        return true;
    }

    @Override public javax.swing.JComponent schemaView() {
        return base instanceof SchemaView sv ? sv.schemaView() : null;
    }

    // What the producing model declares travels through the working layer unchanged.
    // Without this the base's answer is replaced by the interface default — the model's
    // own category recipe and kind rules would be invisible the moment a PROJECT-derived
    // class is layered over the domain.
    @Override public wikidata.explore.model.WikipediaCategoryRule wikipediaCategoryRule(
            String type, String field) {
        return base.wikipediaCategoryRule(type, field);
    }

    @Override public wikidata.explore.model.EntityKindRule entityKindRule(String className) {
        return base.entityKindRule(className);
    }

    @Override public quiz.curation.ManualCuration curation() {
        return base instanceof quiz.curation.Curatable c ? c.curation() : null;
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            quiz.curation.Correction correction) {
        return base instanceof quiz.curation.FieldRulePromoter promoter
                ? promoter.previewPromotion(correction)
                : quiz.curation.FieldRulePromoter.PromotionPreview.ineligible(
                        "This dataset has no ModelBuilder model.");
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.Correction correction) throws Exception {
        if (!(base instanceof quiz.curation.FieldRulePromoter promoter)) {
            throw new IllegalStateException("This dataset has no ModelBuilder model.");
        }
        return promoter.promote(correction);
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview previewPromotion(
            quiz.curation.FieldSourceRecipe recipe) {
        return base instanceof quiz.curation.FieldRulePromoter promoter
                ? promoter.previewPromotion(recipe)
                : quiz.curation.FieldRulePromoter.PromotionPreview.ineligible(
                        "This dataset has no ModelBuilder model.");
    }

    @Override public quiz.curation.FieldRulePromoter.PromotionPreview promote(
            quiz.curation.FieldSourceRecipe recipe) throws Exception {
        if (!(base instanceof quiz.curation.FieldRulePromoter promoter)) {
            throw new IllegalStateException("This dataset has no ModelBuilder model.");
        }
        return promoter.promote(recipe);
    }

    /** Derived classes and their instances exist only here, so the sample has to be found
     *  here too: the base has never heard of a PROJECT-derived class and would answer null. */
    @Override public objectview.Viewable representativeSample(String type) {
        for (objectview.Viewable value : instances()) {
            if (value != null && type != null && directClasses(value).contains(type)) {
                return value;
            }
        }
        return null;
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredSource(
            String type, String field) {
        return base instanceof quiz.curation.FieldRulePromoter promoter
                ? promoter.declaredSource(type, field) : null;
    }

    @Override public wikidata.explore.model.FieldSourceMapping declaredFallbackSource(
            String type, String field) {
        return base instanceof quiz.curation.FieldRulePromoter promoter
                ? promoter.declaredFallbackSource(type, field) : null;
    }

    /** Apply a merge to the REAL base pool — not the throwaway combined copy that
     *  {@link #instances()} returns — so the duplicate's removal takes effect live. */
    @Override public int applyMerge(quiz.curation.Merge merge) {
        return quiz.curation.Merges.apply(
                base.instances(), List.of(merge), this::baseType);
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

    /** Defines a semantic subclass and assigns it directly to the supplied instances.
     * The base class is mandatory; group structure is only a source of members. */
    public int defineSubclass(String name, String baseType,
                              Collection<? extends Viewable> members) {
        if (name == null || name.isBlank() || baseType == null || baseType.isBlank()) {
            throw new IllegalArgumentException("Subclass name and base class are required");
        }
        String subtype = name.trim();
        String base = baseType.trim();
        if (!types().contains(base)) {
            throw new IllegalArgumentException("Unknown base class " + base);
        }
        String existingBase = subclassBases.get(subtype);
        if (types().contains(subtype)
                && (existingBase == null || !existingBase.equals(base))) {
            throw new IllegalArgumentException("Class already exists: " + subtype);
        }
        if (subtype.equals(base) || isSubclassOf(base, subtype)) {
            throw new IllegalArgumentException("Cyclic subclass definition "
                    + subtype + " extends " + base);
        }
        // Validate before mutating: pick the members that actually are the base, and reject
        // a base that matches none of them (a silent zero-member subclass otherwise).
        List<Viewable> matched = new ArrayList<>();
        int requested = 0;
        if (members != null) {
            for (Viewable member : members) {
                if (member == null) continue;
                requested++;
                if (isInstanceOf(member, base)) matched.add(member);
            }
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException(requested == 0
                    ? "Select at least one member to define " + subtype + "."
                    : "None of the " + requested
                            + " selected members are instances of " + base + ".");
        }
        subclassBases.put(subtype, base);
        for (Viewable member : matched) {
            assignedClasses.computeIfAbsent(member,
                    ignored -> new java.util.LinkedHashSet<>()).add(subtype);
            if (member instanceof WikidataDynamicObject dynamic) {
                dynamic.assignSubclass(subtype, base);
            }
        }
        groupRoots.remove(subtype);
        groupRootSignatures.remove(subtype);
        return matched.size();
    }
    public quiz.transform.EditableGroup editableGroupRoot(String type) {
        if (type == null) return null;
        quiz.transform.EditableGroup root =
                groupRoots.computeIfAbsent(type, this::createGroupRoot);
        List<? extends Viewable> live = instances().stream()
                .filter(value -> isInstanceOf(value, type))
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
                : quiz.transform.EditableGroup.copyOf(declared, this);
    }

    @Override public List<String> types() {
        List<String> t = new ArrayList<>(base.types());
        t.addAll(derived.keySet());
        for (String subtype : subclassBases.keySet()) {
            if (!t.contains(subtype)) t.add(subtype);
        }
        return t;
    }

    // Serving is decided by the underlying domain; an in-progress derived/subclass type
    // is curatable (above) but not published until it is saved as a member root.
    @Override public List<String> servedTypes() { return base.servedTypes(); }

    @Override public String baseType(String type) {
        if (subclassBases.containsKey(type)) return subclassBases.get(type);
        return derived.containsKey(type) ? null : base.baseType(type);
    }

    @Override public java.util.Set<String> directClasses(Viewable instance) {
        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>(base.directClasses(instance));
        java.util.Set<String> assigned = assignedClasses.get(instance);
        if (assigned != null) {
            for (String subtype : assigned) {
                for (String ancestor = baseType(subtype); ancestor != null;
                     ancestor = baseType(ancestor)) result.remove(ancestor);
                result.add(subtype);
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    @Override public FieldSchema fieldSchema(String type) {
        DerivedClass d = derived.get(type);
        if (d != null) {
            return d.fieldSchema();
        }
        String subtypeBase = subclassBases.get(type);
        if (subtypeBase != null) {
            FieldSchema inherited = fieldSchema(subtypeBase);
            List<FieldRef> own = declaredFields.get(type);
            if (own == null || own.isEmpty()) return inherited;
            Map<String, FieldRef> combined = new LinkedHashMap<>();
            if (inherited != null) {
                for (FieldRef field : inherited.fields()) combined.put(field.name(), field);
            }
            for (FieldRef field : own) {
                combined.put(field.name(), field);
            }
            List<FieldRef> immutable = List.copyOf(combined.values());
            return () -> immutable;
        }
        FieldSchema baseSchema = base.fieldSchema(type);
        List<FieldRef> extra = declaredFields.get(type);
        if (extra == null || extra.isEmpty()) {
            return baseSchema;
        }
        Map<String, FieldRef> combined = new LinkedHashMap<>();
        if (baseSchema != null) {
            for (FieldRef field : baseSchema.fields()) {
                combined.put(field.name(), field);
            }
        }
        for (FieldRef field : extra) {
            combined.put(field.name(), field);
        }
        List<FieldRef> immutable = List.copyOf(combined.values());
        return () -> immutable;
    }

    @Override public java.util.Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    /**
     * Delegated, not inherited. The interface default answers from the runtime FieldRef
     * — {@code reference()} — and a collapsed reference is no longer one, so the default
     * says "not an entity field" for exactly the fields that were. Only the compiled
     * domain still knows what a field was DECLARED as. A derived class has no model
     * behind it, so it keeps the default.
     */
    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        if (!derived.containsKey(type)) {
            return base.entityOrigin(baseTypeOrSelf(type), path);
        }
        // A derived class exists only here, so its fields resolve against THIS domain's
        // schema rather than the base's — the derivation DomainModel would have applied,
        // written out because a wrapper no longer implements the interface directly.
        objectview.field.FieldRef field = DomainSchemas.resolve(this, type, path);
        return field != null && field.reference();
    }

    /** A subclass is served by its base class's model declaration. */
    private String baseTypeOrSelf(String type) {
        String declared = subclassBases.get(type);
        return declared == null ? type : declared;
    }

    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
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
        // Derived from THIS domain's bindings, which include the groups edited here.
        return groupRootBindings().stream()
                .map(objectview.viewconfig.DomainGroupRoot::root)
                .toList();
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
