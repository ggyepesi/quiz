package wikidata.explore.model;

import datasource.schema.FieldType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeneratedProjectModel {

    public enum ProjectKind {
        DOMAIN("Domain"), MODEL("Model");
        private final String label;
        ProjectKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private String name = "Generated Wikidata Project";
    private ProjectKind projectKind = ProjectKind.DOMAIN;
    private GeneratedClassModel rootClass;

    // How many levels of child-object edges generation should traverse. Stored
    // with the project so a saved model remembers it (depth 0 skips all child
    // edges, e.g. a constellation's stars).
    private int generationDepth = 1;

    private final List<GeneratedClassModel> classes = new ArrayList<>();

    // Named non-product selections (vocabularies, populations) over the entity pool
    // that productions reference but which are never served — see Selection. Empty
    // for every existing model; a domain opts into them as roles are pulled out of
    // "class" overloading.
    private final List<Selection> selections = new ArrayList<>();
    private final List<EntityKindRule> entityKindRules = new ArrayList<>();
    private final List<EntityRepresentationRule> entityRepresentationRules = new ArrayList<>();
    private final List<ModelImport> imports = new ArrayList<>();

    public GeneratedProjectModel() {
        rootClass = new GeneratedClassModel("Constellation");
        classes.add(rootClass);
    }

    /**
     * The Constellation domain config distilled from the hand-built
     * tracer-bullet (see ConstellationSnapshotMain). Membership is "instance of
     * constellation" (P31 = Q8928); the data fields are the properties we found
     * worth keeping, each reached FROM the constellation (ROOT_TO_ITEM):
     *
     *   P1813 abbreviation  - IAU code; doubles as the membership filter that
     *                         drops obsolete/duplicate constellations.
     *   P2046 area          - solid angle in square degrees.
     *   P18   chart         - the Commons sky-chart image (name-blurred for quiz).
     *   P361  hemisphere     - "part of" the northern/southern sky (reference).
     *   P138  namedAfter     - the mythological/figurative source (reference).
     *
     * Neighbours (a self-referential Constellation collection) and per-star
     * brightness (P1215 magnitude, a Star sub-class) are intentionally left for
     * the workbench to add on top of this base.
     */
    public static GeneratedProjectModel constellationDemo() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.name("Constellations");

        GeneratedClassModel c = new GeneratedClassModel("Constellation");
        c.instanceMapping().sourceQid("Q8928");
        c.instanceMapping().sourceLabel("constellation");
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().propertyLabel("instance of");
        c.instanceMapping().limit(200);

        field(c, "abbreviation", FieldType.STRING, FieldCardinality.SINGLE,
                "P1813", "IAU abbreviation", FieldRenderMode.INLINE);
        field(c, "area", FieldType.NUMBER, FieldCardinality.SINGLE,
                "P2046", "area (deg2)", FieldRenderMode.INLINE);
        field(c, "chart", FieldType.IMAGE, FieldCardinality.SINGLE,
                "P18", "image", FieldRenderMode.INLINE);
        field(c, "hemisphere", FieldType.ENTITY, FieldCardinality.SINGLE,
                "P361", "part of", FieldRenderMode.REFERENCE);
        field(c, "namedAfter", FieldType.ENTITY, FieldCardinality.SINGLE,
                "P138", "named after", FieldRenderMode.REFERENCE);

        p.rootClass(c);
        return p;
    }

    /**
     * Adds one data field reached from the root entity via {@code wdt:Pxxx}
     * (ROOT_TO_ITEM), with the property pre-filled so the workbench loads a
     * ready-to-run mapping rather than a blank row.
     */
    private static void field(
            GeneratedClassModel c,
            String name,
            FieldType type,
            FieldCardinality cardinality,
            String propertyPid,
            String propertyLabel,
            FieldRenderMode renderMode) {

        GeneratedFieldModel f = c.addField(name, type, cardinality);
        f.renderMode(renderMode);
        f.mapping().propertyPid(propertyPid);
        f.mapping().propertyLabel(propertyLabel);
        f.mapping().direction(RuleDirection.ROOT_TO_ITEM);
    }

    public String name() {
        return name;
    }

    public ProjectKind projectKind() {
        return projectKind == null ? ProjectKind.DOMAIN : projectKind;
    }

    /**
     * Whether this project acquires instances. A DOMAIN does; a MODEL is configuration
     * and has no generation, so a rule that exists to bound acquisition has nothing to
     * bound here. Asked by name rather than by comparing the enum, so the reason a rule
     * is skipped is stated where it is skipped.
     */
    public boolean acquiresInstances() {
        return projectKind() == ProjectKind.DOMAIN;
    }

    public void projectKind(ProjectKind value) {
        projectKind = value == null ? ProjectKind.DOMAIN : value;
    }

    public boolean isModel() { return projectKind() == ProjectKind.MODEL; }
    public boolean supportsExecution() { return projectKind() == ProjectKind.DOMAIN; }

    public int generationDepth() {
        return generationDepth;
    }

    public void generationDepth(int depth) {
        this.generationDepth = Math.max(0, depth);
    }

    public void name(String name) {
        this.name =
                name == null || name.isBlank()
                        ? "Generated Wikidata Project"
                        : name.trim();
    }

    public GeneratedClassModel rootClass() {
        return rootClass;
    }

    public void rootClass(GeneratedClassModel rootClass) {
        GeneratedClassModel oldRoot = this.rootClass;

        this.rootClass =
                rootClass == null
                        ? new GeneratedClassModel("GeneratedClass")
                        : rootClass;


        if (oldRoot != null && oldRoot != this.rootClass) {
            classes.remove(oldRoot);
        }

        classes.remove(this.rootClass);
        classes.addFirst(this.rootClass);
    }

    public List<GeneratedClassModel> classes() {
        return Collections.unmodifiableList(classes);
    }

    /**
     * Returns the class that directly declares {@code field}, or {@code null} when
     * the field is not part of this model. There is deliberately no root-class
     * fallback: asking a foreign field about the root changes an ownership error
     * into a valid-looking query for the wrong population.
     */
    public GeneratedClassModel declaringClass(GeneratedFieldModel field) {
        if (field == null) return null;
        for (GeneratedClassModel clazz : classes) {
            if (clazz != null && containsField(clazz.fields(), field)) return clazz;
        }
        return null;
    }

    private static boolean containsField(
            List<GeneratedFieldModel> fields, GeneratedFieldModel sought) {
        for (GeneratedFieldModel field : fields) {
            if (field == sought || containsField(field.fields(), sought)) return true;
        }
        return false;
    }

    /** The domain's named non-product Selections (vocabularies/populations). */
    public List<Selection> selections() {
        return Collections.unmodifiableList(selections);
    }

    public void addSelection(Selection selection) {
        if (selection != null) {
            selections.add(selection);
        }
    }

    public Selection findSelection(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Selection s : selections) {
            if (s != null && s.name().equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        return null;
    }

    public Selection findSelectionById(String declarationId) {
        String id = DeclarationIds.clean(declarationId);
        if (id.isBlank()) return null;
        return selections.stream().filter(java.util.Objects::nonNull)
                .filter(selection -> id.equals(selection.declarationId()))
                .findFirst().orElse(null);
    }

    public List<EntityKindRule> entityKindRules() {
        return Collections.unmodifiableList(entityKindRules);
    }

    public List<EntityRepresentationRule> entityRepresentationRules() {
        return Collections.unmodifiableList(entityRepresentationRules);
    }

    public void entityRepresentationRules(List<EntityRepresentationRule> rules) {
        entityRepresentationRules.clear();
        if (rules != null) rules.stream().filter(java.util.Objects::nonNull)
                .map(EntityRepresentationRule::copy).forEach(entityRepresentationRules::add);
    }

    /** Replaces the ordered alternatives owned by one role class. */
    public void representationClasses(GeneratedClassModel role, List<String> classNames) {
        if (role == null) return;
        entityRepresentationRules.removeIf(rule -> references(role.declarationId(),
                rule.roleClassId(), role.className(), rule.roleClassName()));
        if (classNames == null) return;
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>(classNames);
        for (String name : unique) {
            GeneratedClassModel target = findClass(name);
            if (target == null || target == role) continue;
            EntityRepresentationRule rule = new EntityRepresentationRule();
            rule.roleClassReference(role.declarationId(), role.className());
            rule.representationClassReference(target.declarationId(), target.className());
            entityRepresentationRules.add(rule);
        }
    }

    public void addEntityKindRule(EntityKindRule rule) {
        if (rule != null) entityKindRules.add(rule);
    }

    public void entityKindRules(List<EntityKindRule> rules) {
        entityKindRules.clear();
        if (rules != null) rules.stream().filter(java.util.Objects::nonNull)
                .map(EntityKindRule::copy).forEach(entityKindRules::add);
    }

    public List<ModelImport> imports() {
        return Collections.unmodifiableList(imports);
    }

    public void imports(List<ModelImport> values) {
        imports.clear();
        if (values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(ModelImport::copy).forEach(imports::add);
    }

    public void addImport(ModelImport value) {
        if (value == null || !value.complete()) return;
        ModelImport existing = imports.stream()
                .filter(item -> item.modelName().equalsIgnoreCase(value.modelName()))
                .findFirst().orElse(null);
        if (existing == null) {
            imports.add(value.copy());
            return;
        }
        java.util.LinkedHashSet<String> names =
                new java.util.LinkedHashSet<>(existing.classNames());
        names.addAll(value.classNames());
        existing.classNames(new java.util.ArrayList<>(names));
    }

    public void removeImportedClass(String modelName, String className) {
        for (int i = imports.size() - 1; i >= 0; i--) {
            ModelImport item = imports.get(i);
            if (!item.modelName().equalsIgnoreCase(modelName)) continue;
            java.util.ArrayList<String> kept =
                    new java.util.ArrayList<>(item.classNames());
            kept.removeIf(name -> name.equalsIgnoreCase(className));
            if (kept.isEmpty()) imports.remove(i); else item.classNames(kept);
        }
    }

    /** Replaces this model's contents (name + classes) with another's, in
     *  place — so references held to this instance (the workbench panels) keep
     *  pointing at the same object after loading a saved model. */
    public void copyContentsFrom(GeneratedProjectModel other) {
        if (other == null) {
            return;
        }
        this.name = other.name;
        this.projectKind = other.projectKind();
        this.generationDepth = other.generationDepth;
        this.classes.clear();
        this.classes.addAll(other.classes);
        this.selections.clear();
        this.selections.addAll(other.selections);
        this.entityKindRules.clear();
        this.entityKindRules.addAll(other.entityKindRules);
        this.entityRepresentationRules.clear();
        other.entityRepresentationRules.stream().map(EntityRepresentationRule::copy)
                .forEach(this.entityRepresentationRules::add);
        this.imports.clear();
        other.imports.stream().map(ModelImport::copy).forEach(this.imports::add);

        // Serialization has no object identity, so the root is written both as
        // `rootClass` and inside `classes` and deserializes as two separate
        // instances. Reconcile by name to the one already in the list, so the
        // root isn't duplicated (which showed Constellation twice in the tree).
        GeneratedClassModel root = other.rootClass;
        if (root != null) {
            GeneratedClassModel inList = findClassById(root.declarationId());
            if (inList == null) inList = findClass(root.className());
            if (inList != null) {
                root = inList;
            }
        }
        if (root == null) {
            root = classes.isEmpty()
                    ? new GeneratedClassModel("GeneratedClass")
                    : classes.getFirst();
        }
        this.rootClass = root;
        if (!classes.contains(rootClass)) {
            classes.addFirst(rootClass);
        }
        ensureDeclarationIdentities();
    }

    /**
     * Renames a class and every reference to it. A name is used by NAME elsewhere in the
     * model — a field's target class, a base class, a kind rule's subject — so renaming
     * the class alone leaves those pointing at a class that no longer exists: the field
     * keeps a dangling target, and an owned class silently stops being produced because
     * no site names it any more.
     */
    public boolean renameClass(String oldName, String newName) {
        String from = oldName == null ? "" : oldName.trim();
        String to = newName == null ? "" : newName.trim();
        if (from.isEmpty() || to.isEmpty()) return false;
        GeneratedClassModel target = findClass(from);
        if (target == null) return false;
        // A no-op cannot collide with another namespace entry: it changes nothing.
        // Check it before legacy/invalid duplicate-name diagnostics so merely applying
        // an unchanged editor remains safe.
        if (from.equals(to)) return true;
        // Deliberately after the no-op check: the class editors call this on every
        // Apply with an unchanged name, and refusing there would report a rename
        // failure every time an imported class is merely saved.
        if (target.isImported()) return false;
        GeneratedClassModel classConflict = findClass(to);
        if (classConflict != null && classConflict != target) return false;
        if (findSelection(to) != null) return false;
        target.className(to);
        for (GeneratedClassModel clazz : classes) {
            if (clazz == null) continue;
            if (references(target.declarationId(), clazz.baseClassId(),
                    from, clazz.baseClassName())) {
                clazz.baseClassReference(target.declarationId(), to);
            }
            StatementClassSource statement = clazz.statementSource();
            if (statement != null && references(target.declarationId(),
                    statement.sourceClassId(), from, statement.sourceClassName())) {
                statement.sourceClassReference(target.declarationId(), to);
            }
            AggregateClassSource aggregate = clazz.aggregateSource();
            if (aggregate != null && references(target.declarationId(),
                    aggregate.sourceClassId(), from, aggregate.sourceClassName())) {
                aggregate.sourceClassReference(target.declarationId(), to);
            }
            renameBindingTargets(clazz.sourceBindings(), from, to, target.declarationId());
            renameFieldTargets(clazz.fields(), from, to, target.declarationId());
        }
        for (EntityKindRule rule : entityKindRules) {
            if (rule != null && references(target.declarationId(), rule.classId(),
                    from, rule.className())) {
                rule.classReference(target.declarationId(), to);
            }
        }
        for (EntityRepresentationRule rule : entityRepresentationRules) {
            if (rule == null) continue;
            if (references(target.declarationId(), rule.roleClassId(),
                    from, rule.roleClassName())) {
                rule.roleClassReference(target.declarationId(), to);
            }
            if (references(target.declarationId(), rule.representationClassId(),
                    from, rule.representationClassName())) {
                rule.representationClassReference(target.declarationId(), to);
            }
        }
        for (Selection selection : selections) {
            if (selection instanceof RoleSelection role
                    && references(target.declarationId(), role.ownerClassId(),
                            from, role.ownerClassName())) {
                role.ownerReference(target.declarationId(), to);
            }
        }
        return true;
    }

    private static void renameFieldTargets(
            List<GeneratedFieldModel> fields, String from, String to, String targetId) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            if (references(targetId, field.entityDeclarationId(),
                    from, field.entityClassName())) {
                field.entityReference(targetId, to);
            }
            renameBindingTargets(field.sourceBindings(), from, to, targetId);
            renameFieldTargets(field.fields(), from, to, targetId);
        }
    }

    /** Binding targets are durable model addresses, so a class rename must move them
     * with every other name reference. Leaving the old address causes synchronization
     * to add a second binding under the new name while the stale one remains. */
    private static void renameBindingTargets(
            List<datasource.api.SourceBinding> bindings, String from, String to,
            String targetId) {
        if (bindings == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null || !references(targetId,
                    binding.target().classDeclarationId(), from,
                    binding.target().className())) continue;
            datasource.api.SourceBindingTarget target = binding.target();
            bindings.set(i, new datasource.api.SourceBinding(
                    new datasource.api.SourceBindingTarget(target.scope(), to,
                            target.fieldPath(), target.slot(), targetId),
                    binding.recipe()));
        }
    }

    private static boolean references(String declarationId, String referenceId,
            String oldName, String nameHint) {
        String id = DeclarationIds.clean(declarationId);
        String ref = DeclarationIds.clean(referenceId);
        return (!id.isBlank() && id.equals(ref))
                || (ref.isBlank() && oldName.equals(clean(nameHint)));
    }

    /** Repairs models saved by the pre-rename binding leak. A binding is stored on
     * exactly the class/field it addresses, so its durable address can be recovered
     * without guessing from provider-specific recipe contents. */
    void reconcileSourceBindingTargets() {
        for (GeneratedClassModel clazz : classes) {
            if (clazz == null) continue;
            retargetBindings(clazz.sourceBindings(), clazz.className(), "");
            reconcileFieldBindingTargets(clazz.className(), "", clazz.fields());
        }
    }

    private static void reconcileFieldBindingTargets(
            String owner, String prefix, List<GeneratedFieldModel> fields) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            String path = prefix.isBlank() ? field.name() : prefix + "." + field.name();
            retargetBindings(field.sourceBindings(), owner, path);
            reconcileFieldBindingTargets(owner, path, field.fields());
        }
    }

    private static void retargetBindings(List<datasource.api.SourceBinding> bindings,
            String owner, String path) {
        if (bindings == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            datasource.api.SourceBindingTarget target = binding.target();
            String expectedPath = target.scope() == datasource.api.BindingScope.FIELD_VALUE
                    ? path : "";
            if (owner.equals(target.className()) && expectedPath.equals(target.fieldPath())) {
                continue;
            }
            bindings.set(i, new datasource.api.SourceBinding(
                    new datasource.api.SourceBindingTarget(target.scope(), owner,
                            expectedPath, target.slot(), target.classDeclarationId()),
                    binding.recipe()));
        }
        // A save after the old rename leak could already contain both the stale and
        // newly synchronized address. Once retargeted they occupy the same semantic
        // slot; retain the later entry, matching normal replace semantics.
        java.util.LinkedHashMap<datasource.api.SourceBindingTarget,
                datasource.api.SourceBinding> unique = new java.util.LinkedHashMap<>();
        for (datasource.api.SourceBinding binding : bindings) {
            if (binding != null) unique.put(binding.target(), binding);
        }
        bindings.clear();
        bindings.addAll(unique.values());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public void addClass(GeneratedClassModel c) {
        if (c == null) {
            return;
        }


        if (!classes.contains(c)) {
            classes.add(c);
        }
    }

    /** Replace a same-named class in place, or append it when absent. */
    public void replaceClass(GeneratedClassModel replacement) {
        if (replacement == null) return;
        GeneratedClassModel existing = findClass(replacement.className());
        if (existing == null) {
            addClass(replacement);
            return;
        }
        int index = classes.indexOf(existing);
        replacement.declarationId(existing.declarationId());
        classes.set(index, replacement);
        if (rootClass == existing) rootClass = replacement;
    }

    public void replaceSelection(Selection replacement) {
        if (replacement == null) return;
        Selection existing = findSelection(replacement.name());
        if (existing == null) {
            addSelection(replacement);
            return;
        }
        replacement.declarationId(existing.declarationId());
        selections.set(selections.indexOf(existing), replacement);
    }

    /** Renames one selection and every model reference to it. */
    public boolean renameSelection(String oldName, String newName) {
        Selection selection = findSelection(oldName);
        String next = newName == null ? "" : newName.trim();
        if (selection == null || next.isBlank()) return false;
        Selection conflict = findSelection(next);
        if (conflict != null && conflict != selection) return false;
        // A field target names a class or a selection in ONE namespace, and the class
        // wins it. Renaming onto a class name would leave this selection unreachable.
        if (findClass(next) != null) return false;
        String previous = selection.name();
        boolean fieldsPointHere = fieldTargetsResolveToSelection(previous);
        selection.name(next);
        for (GeneratedClassModel clazz : classes) {
            // No early exit here: a class WITHOUT a statement source still has fields
            // that may target this selection, and skipping them left a renamed
            // vocabulary unreferenced from exactly those.
            if (clazz.statementSource() != null) {
                if (references(selection.declarationId(),
                        clazz.statementSource().valueSelectionId(), previous,
                        clazz.statementSource().valueSelectionName())) {
                    clazz.statementSource().valueSelectionReference(
                            selection.declarationId(), next);
                }
                // Both ends. A vocabulary can bound the subject as readily as the
                // object, so a rename reaching only one would leave the other pointing
                // at a name nothing answers to.
                EntityBound subject = clazz.statementSource().subjectBound();
                if (subject.kind() == EntityBound.Kind.VOCABULARY
                        && references(selection.declarationId(), subject.selectionId(),
                                previous, subject.selectionName())) {
                    clazz.statementSource().subjectSelectionReference(
                            selection.declarationId(), next);
                }
            }
            if (fieldsPointHere) renameFieldSelection(
                    clazz.fields(), previous, next, selection.declarationId());
        }
        return true;
    }

    /** Removes an unreferenced selection; referenced declarations must be redirected first. */
    public boolean removeSelection(String name) {
        Selection selection = findSelection(name);
        if (selection == null || selectionReferenced(selection.name())) return false;
        return selections.remove(selection);
    }

    public boolean selectionReferenced(String name) {
        if (name == null || name.isBlank()) return false;
        boolean fieldsPointHere = fieldTargetsResolveToSelection(name);
        for (GeneratedClassModel clazz : classes) {
            // A statement source names a SELECTION explicitly, so it is never ambiguous.
            if (clazz.statementSource() != null
                    && clazz.statementSource().valueSelectionName().equalsIgnoreCase(name)) return true;
            if (fieldsPointHere && fieldReferencesSelection(clazz.fields(), name)) return true;
        }
        return false;
    }

    /**
     * Whether a field whose target reads {@code name} means the SELECTION of that name.
     * Class and selection names share one namespace and a class wins it — ClassImportPlan
     * and the validator both ask findClass first — so while a class of that name exists,
     * no field target refers to the selection, however many spell its name.
     */
    private boolean fieldTargetsResolveToSelection(String name) {
        return findClass(name) == null;
    }

    private static void renameFieldSelection(List<GeneratedFieldModel> fields,
            String previous, String next, String selectionId) {
        for (GeneratedFieldModel field : fields) {
            if (references(selectionId, field.entityDeclarationId(),
                    previous, field.entityClassName())) {
                field.entityReference(selectionId, next);
            }
            renameFieldSelection(field.fields(), previous, next, selectionId);
        }
    }

    private static boolean fieldReferencesSelection(List<GeneratedFieldModel> fields, String name) {
        for (GeneratedFieldModel field : fields) {
            if (field.entityClassName().equalsIgnoreCase(name)
                    || fieldReferencesSelection(field.fields(), name)) return true;
        }
        return false;
    }

    public void replaceEntityKindRule(EntityKindRule replacement) {
        if (replacement == null) return;
        for (int i = 0; i < entityKindRules.size(); i++) {
            EntityKindRule existing = entityKindRules.get(i);
            if (existing.className().equals(replacement.className())
                    && existing.propertyPid().equals(replacement.propertyPid())) {
                entityKindRules.set(i, replacement);
                return;
            }
        }
        entityKindRules.add(replacement);
    }

    public GeneratedClassModel findClass(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (GeneratedClassModel c : classes) {
            if (name.equals(c.className())) {
                return c;
            }
        }
        // Case-insensitive fallback: class-name references (a field's "Of class",
        // a "Reifies statements of") are user labels, and a rename that only
        // changed the case shouldn't silently break them.
        for (GeneratedClassModel c : classes) {
            if (name.equalsIgnoreCase(c.className())) {
                return c;
            }
        }

        return null;
    }

    public GeneratedClassModel findClassById(String declarationId) {
        String id = DeclarationIds.clean(declarationId);
        if (id.isBlank()) return null;
        return classes.stream().filter(java.util.Objects::nonNull)
                .filter(clazz -> id.equals(clazz.declarationId()))
                .findFirst().orElse(null);
    }

    public GeneratedClassModel resolveClass(String declarationId, String nameHint) {
        GeneratedClassModel byId = findClassById(declarationId);
        return byId == null ? findClass(nameHint) : byId;
    }

    public Selection resolveSelection(String declarationId, String nameHint) {
        Selection byId = findSelectionById(declarationId);
        return byId == null ? findSelection(nameHint) : byId;
    }

    /**
     * Migrates legacy name references to stable declaration identities and refreshes
     * their readable name hints. This is deliberately provider-independent and runs on
     * the compiler's snapshot, never halfway through a UI edit.
     */
    public void ensureDeclarationIdentities() {
        for (GeneratedClassModel clazz : classes) {
            if (clazz != null) clazz.ensureDeclarationId(name());
        }
        // An aggregate's identity lives where every other construct's does. What sits
        // under it is a rename table — each of its own fields is grouped from a
        // differently-named field on the source — and applying that rename is
        // construction. So the canonical key is filled from the target halves for a
        // model saved before identity moved: a READ of what the model already said, once,
        // and not a second place to write one.
        for (GeneratedClassModel clazz : classes) {
            if (clazz == null || clazz.aggregateSource() == null) continue;
            if (!clazz.canonical().keyFields().isEmpty()) continue;
            for (AggregateClassSource.Key key : clazz.aggregateSource().keys()) {
                if (key != null && !key.targetField().isBlank()) {
                    clazz.canonical().keyFields().add(key.targetField());
                }
            }
        }
        for (Selection selection : selections) {
            if (selection != null) selection.ensureDeclarationId(name());
        }
        for (GeneratedClassModel clazz : classes) {
            if (clazz == null) continue;
            GeneratedClassModel base = resolveClass(clazz.baseClassId(), clazz.baseClassName());
            if (base != null) clazz.baseClassReference(base.declarationId(), base.className());
            StatementClassSource statement = clazz.statementSource();
            if (statement != null) {
                GeneratedClassModel source = resolveClass(
                        statement.sourceClassId(), statement.sourceClassName());
                if (source != null) statement.sourceClassReference(
                        source.declarationId(), source.className());
                Selection value = resolveSelection(
                        statement.valueSelectionId(), statement.valueSelectionName());
                if (value != null) statement.valueSelectionReference(
                        value.declarationId(), value.name());
                EntityBound subjectBound = statement.subjectBound();
                if (subjectBound.kind() == EntityBound.Kind.VOCABULARY) {
                    Selection subjectSelection = resolveSelection(
                            subjectBound.selectionId(), subjectBound.selectionName());
                    if (subjectSelection != null) statement.subjectSelectionReference(
                            subjectSelection.declarationId(), subjectSelection.name());
                }
            }
            AggregateClassSource aggregate = clazz.aggregateSource();
            if (aggregate != null) {
                GeneratedClassModel source = resolveClass(
                        aggregate.sourceClassId(), aggregate.sourceClassName());
                if (source != null) aggregate.sourceClassReference(
                        source.declarationId(), source.className());
            }
            normalizeFields(clazz.fields(), clazz);
            normalizeBindings(clazz.sourceBindings(), clazz);
        }
        for (EntityKindRule rule : entityKindRules) {
            if (rule == null) continue;
            GeneratedClassModel target = resolveClass(rule.classId(), rule.className());
            if (target != null) rule.classReference(target.declarationId(), target.className());
        }
        for (EntityRepresentationRule rule : entityRepresentationRules) {
            if (rule == null) continue;
            GeneratedClassModel role = resolveClass(
                    rule.roleClassId(), rule.roleClassName());
            if (role != null) rule.roleClassReference(role.declarationId(), role.className());
            GeneratedClassModel target = resolveClass(
                    rule.representationClassId(), rule.representationClassName());
            if (target != null) rule.representationClassReference(
                    target.declarationId(), target.className());
        }
        for (Selection selection : selections) {
            if (!(selection instanceof RoleSelection role)) continue;
            GeneratedClassModel owner = resolveClass(role.ownerClassId(), role.ownerClassName());
            if (owner != null) role.ownerReference(owner.declarationId(), owner.className());
        }
    }

    private void normalizeFields(List<GeneratedFieldModel> fields,
            GeneratedClassModel owner) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            GeneratedClassModel clazz = resolveClass(
                    field.entityDeclarationId(), field.entityClassName());
            if (clazz != null) field.entityReference(clazz.declarationId(), clazz.className());
            else {
                Selection selection = resolveSelection(
                        field.entityDeclarationId(), field.entityClassName());
                if (selection != null) field.entityReference(
                        selection.declarationId(), selection.name());
            }
            normalizeBindings(field.sourceBindings(), owner);
            normalizeFields(field.fields(), owner);
        }
    }

    private static void normalizeBindings(List<datasource.api.SourceBinding> bindings,
            GeneratedClassModel owner) {
        if (bindings == null || owner == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            datasource.api.SourceBindingTarget target = binding.target();
            bindings.set(i, new datasource.api.SourceBinding(
                    new datasource.api.SourceBindingTarget(target.scope(), owner.className(),
                            target.fieldPath(), target.slot(), owner.declarationId()),
                    binding.recipe()));
        }
    }

    /** True when {@code candidate} is {@code expected} or extends it, following the
     * model's single-inheritance chain. Cycles are tolerated here and diagnosed by
     * structural validation; consumers still get a terminating membership answer. */
    public boolean isSameOrSubclass(String candidate, String expected) {
        if (candidate == null || expected == null) return false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String current = candidate; current != null && seen.add(current); ) {
            if (expected.equals(current)) return true;
            GeneratedClassModel model = findClass(current);
            current = model == null || !model.hasBase() ? null : model.baseClassName();
        }
        return false;
    }

    public GeneratedClassModel getOrCreateClass(String name) {
        String clean =
                name == null || name.isBlank()
                        ? "GeneratedClass"
                        : name.trim();

        GeneratedClassModel existing = findClass(clean);

        if (existing != null) {
            return existing;
        }

        GeneratedClassModel created = new GeneratedClassModel(clean);
        addClass(created);
        return created;
    }

    /**
     * Deep snapshot for handing to a background generation run, so EDT
     * edits made while the run is in flight cannot tear it.
     */
    public GeneratedProjectModel copy() {
        GeneratedProjectModel c = new GeneratedProjectModel();
        c.name = name;
        c.projectKind = projectKind();
        c.generationDepth = generationDepth;
        c.classes.clear();
        c.rootClass = null;

        for (GeneratedClassModel cls : classes) {
            GeneratedClassModel copied = cls.copy();
            c.classes.add(copied);

            if (cls == rootClass) {
                c.rootClass = copied;
            }
        }

        if (c.rootClass == null && rootClass != null) {
            // The source can carry its root as a distinct object from its
            // same-named entry in `classes`: a saved model serializes the root
            // both as `rootClass` and inside `classes`, and Jackson deserializes
            // the two separately. Adopt the already-copied same-named class as the
            // root rather than adding a second copy — otherwise the copy ends up
            // with two classes sharing the root's name (a duplicate that fails
            // validation the moment it is compiled).
            for (GeneratedClassModel cls : c.classes) {
                if (cls.className().equalsIgnoreCase(rootClass.className())) {
                    c.rootClass = cls;
                    break;
                }
            }
            if (c.rootClass == null) {
                c.rootClass = rootClass.copy();
                c.classes.addFirst(c.rootClass);
            }
        }

        for (Selection s : selections) {
            if (s != null) {
                c.selections.add(s.copy());
            }
        }
        for (EntityKindRule rule : entityKindRules) {
            if (rule != null) c.entityKindRules.add(rule.copy());
        }
        for (EntityRepresentationRule rule : entityRepresentationRules) {
            if (rule != null) c.entityRepresentationRules.add(rule.copy());
        }
        for (ModelImport dependency : imports) {
            if (dependency != null) c.imports.add(dependency.copy());
        }

        return c;
    }

    /** The authored project written to disk; imported declarations are resolved afresh. */
    public GeneratedProjectModel withoutResolvedImports() {
        GeneratedProjectModel authored = copy();
        authored.classes.removeIf(GeneratedClassModel::isImported);
        authored.selections.removeIf(Selection::isImported);
        authored.entityKindRules.removeIf(EntityKindRule::isImported);
        if (authored.rootClass != null && authored.rootClass.isImported()) {
            throw new IllegalStateException("A project's root class must be authored locally");
        }
        return authored;
    }

    public void removeClass(GeneratedClassModel c) {
        if (c == null || c == rootClass) {
            return;
        }

        classes.remove(c);
    }

    @Override
    public String toString() {
        return name;
    }
}
