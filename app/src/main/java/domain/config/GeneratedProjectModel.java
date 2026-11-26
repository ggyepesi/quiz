package domain.config;

import domain.datasource.schema.FieldType;
import domain.datasource.graph.GraphDiscoveryConfiguration;

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
    // Named texts of works (TextSource). Absent from a model that declares none, so
    // adding the construct leaves every existing model file and signature unchanged.
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private final List<TextSource> textSources = new ArrayList<>();
    private final List<EntityKindRule> entityKindRules = new ArrayList<>();
    private final List<EntityRepresentationRule> entityRepresentationRules = new ArrayList<>();
    private final List<ModelImport> imports = new ArrayList<>();
    /** How the project is built, in order. Configuration of the run, never of what is
     *  generated, so it stays out of the model signature. */
    private final List<BuildOperation> buildOperations = new ArrayList<>();

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
        c.membership(EntityBound.relation("P31", java.util.List.of("Q8928"), false));
        c.instanceMapping().sourceLabel("constellation");
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
     * Whether acquisition completeness is required of the saved declaration. A domain
     * must be runnable; a model may remain reusable while incomplete. A model that is
     * actually run is checked separately through validateForAcquisition.
     */
    public boolean acquiresInstances() {
        return projectKind() == ProjectKind.DOMAIN;
    }

    public void projectKind(ProjectKind value) {
        projectKind = value == null ? ProjectKind.DOMAIN : value;
    }

    public boolean isModel() { return projectKind() == ProjectKind.MODEL; }

    /** Reuse and execution are independent: a complete model can generate locally. */
    public boolean supportsExecution() {
        return !isModel()
                || rootClass != null
                && MembershipPattern.of(rootClass, this) != MembershipPattern.UNCONFIGURED
                && GeneratedProjectModelValidator.validateForAcquisition(this).valid();
    }

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

    /**
     * The classes discovered by traversal, in declaration order.
     *
     * <p>There was one graph per project, held here as a singleton field. A graph
     * constraint is a class now, so several cost nothing — and a domain needs more than
     * one discovery rule: one narrowing the offices worth asking about, another reaching
     * their holders. With a singleton the second could only be had by editing the first
     * and destroying the first's result.
     */
    public List<GeneratedClassModel> graphClasses() {
        return classes.stream()
                .filter(clazz -> clazz != null && clazz.classKind() == ClassKind.GRAPH)
                .toList();
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

    /** The texts this project declares or imports; a recipe is offered only these. */
    public List<TextSource> textSources() {
        return Collections.unmodifiableList(textSources);
    }

    public void addTextSource(TextSource source) {
        if (source != null) textSources.add(source);
    }

    public TextSource findTextSource(String name) {
        if (name == null || name.isBlank()) return null;
        return textSources.stream().filter(java.util.Objects::nonNull)
                .filter(source -> source.name().equalsIgnoreCase(name.trim()))
                .findFirst().orElse(null);
    }

    public TextSource findTextSourceById(String declarationId) {
        String id = DeclarationIds.clean(declarationId);
        if (id.isBlank()) return null;
        return textSources.stream().filter(java.util.Objects::nonNull)
                .filter(source -> id.equals(source.declarationId()))
                .findFirst().orElse(null);
    }

    /** Renames a text source this project owns. Recipes name it by id, so nothing else
     *  changes; a blank name or one another text source has is refused. */
    public boolean renameTextSource(String oldName, String newName) {
        TextSource text = findTextSource(oldName);
        String next = newName == null ? "" : newName.trim();
        requireOwnedText(text, "renamed");
        if (text == null || next.isBlank()) return false;
        TextSource conflict = findTextSource(next);
        if (conflict != null && conflict != text) return false;
        text.name(next);
        return true;
    }

    /** Removes a text source no recipe reads; a read one must be dropped from its recipes first. */
    public boolean removeTextSource(String name) {
        TextSource text = findTextSource(name);
        requireOwnedText(text, "removed");
        if (text == null || textSourceReferenced(text)) return false;
        return textSources.remove(text);
    }

    /** Whether a recipe of this project reads the text source. */
    public boolean textSourceReferenced(TextSource text) {
        return text != null && !ConfigurationReferenceInventory.of(this)
                .referencesToTextSource(text).isEmpty();
    }

    private static void requireOwnedText(TextSource text, String verb) {
        if (text == null || !text.isImported()) return;
        throw new IllegalStateException("Text source '" + text.name() + "' is imported from "
                + text.importedFrom() + " and cannot be " + verb + " here; change it in that model.");
    }

    /** Replaces the same-named text source; an import may refresh only a text source
     *  owned by the same model. The replacement keeps its own identity, because recipes
     *  name a text source by declaration id alone. */
    public void replaceTextSource(TextSource replacement) {
        if (replacement == null) return;
        TextSource existing = findTextSource(replacement.name());
        if (existing == null) {
            addTextSource(replacement);
            return;
        }
        if (existing.isImported() && (!replacement.isImported()
                || !existing.importedFrom().equalsIgnoreCase(replacement.importedFrom()))) {
            throw new IllegalStateException("Text source '" + existing.name()
                    + "' is imported from " + existing.importedFrom()
                    + " and cannot be changed here; change it in that model.");
        }
        textSources.set(textSources.indexOf(existing), replacement);
    }

    public List<BuildOperation> buildOperations() {
        return Collections.unmodifiableList(buildOperations);
    }

    /** Replaces the build with {@code operations}, in their order. */
    public void buildOperations(List<BuildOperation> operations) {
        buildOperations.clear();
        if (operations != null) {
            operations.stream().filter(java.util.Objects::nonNull)
                    .map(BuildOperation::copy).forEach(buildOperations::add);
        }
    }

    public List<EntityRepresentationRule> entityRepresentationRules() {
        return Collections.unmodifiableList(entityRepresentationRules);
    }

    public void entityRepresentationRules(List<EntityRepresentationRule> rules) {
        entityRepresentationRules.clear();
        if (rules != null) rules.stream().filter(java.util.Objects::nonNull)
                .map(EntityRepresentationRule::copy).forEach(entityRepresentationRules::add);
    }

    public void replaceEntityRepresentationRule(EntityRepresentationRule replacement) {
        if (replacement == null || !replacement.isConfigured()) return;
        entityRepresentationRules.removeIf(rule -> rule != null
                && rule.roleClassName().equals(replacement.roleClassName())
                && rule.representationClassName().equals(
                        replacement.representationClassName()));
        entityRepresentationRules.add(replacement);
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
        this.textSources.clear();
        this.textSources.addAll(other.textSources);
        this.entityKindRules.clear();
        this.entityKindRules.addAll(other.entityKindRules);
        this.entityRepresentationRules.clear();
        other.entityRepresentationRules.stream().map(EntityRepresentationRule::copy)
                .forEach(this.entityRepresentationRules::add);
        this.imports.clear();
        other.imports.stream().map(ModelImport::copy).forEach(this.imports::add);
        this.buildOperations(other.buildOperations);

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
        ConfigurationReferenceInventory references =
                ConfigurationReferenceInventory.of(this);
        target.className(to);
        references.renameClassReferences(target, from, to);
        return true;
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
            for (OwnedFieldSource override : field.ownedFieldSources()) {
                if (override != null) retargetOwnedBindings(override.sourceBindings(),
                        field.entityClassName(), override.fieldPath(), owner, path);
            }
            reconcileFieldBindingTargets(owner, path, field.fields());
        }
    }

    private static void retargetOwnedBindings(
            List<domain.datasource.api.SourceBinding> bindings,
            String targetClass, String targetPath, String owner, String ownerPath) {
        if (bindings == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            domain.datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            domain.datasource.api.SourceBindingTarget target = binding.target();
            bindings.set(i, new domain.datasource.api.SourceBinding(
                    new domain.datasource.api.SourceBindingTarget(target.scope(), targetClass,
                            targetPath, target.slot(), target.classDeclarationId(), owner,
                            ownerPath, target.contextClassDeclarationId()),
                    binding.recipe()));
        }
        deduplicateBindings(bindings);
    }

    private static void retargetBindings(List<domain.datasource.api.SourceBinding> bindings,
            String owner, String path) {
        if (bindings == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            domain.datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            domain.datasource.api.SourceBindingTarget target = binding.target();
            String expectedPath = target.scope() == domain.datasource.api.BindingScope.FIELD_VALUE
                    ? path : "";
            if (owner.equals(target.className()) && expectedPath.equals(target.fieldPath())) {
                continue;
            }
            bindings.set(i, new domain.datasource.api.SourceBinding(
                    new domain.datasource.api.SourceBindingTarget(target.scope(), owner,
                            expectedPath, target.slot(), target.classDeclarationId()),
                    binding.recipe()));
        }
        deduplicateBindings(bindings);
    }

    private static void deduplicateBindings(
            List<domain.datasource.api.SourceBinding> bindings) {
        // A save after the old rename leak could contain a stale and a synchronized
        // address for one singular slot; keep the later one. Candidate evidence is an
        // ordered additive slot, so distinct recipes sharing its target are children,
        // not duplicates. Only an exact repeated child is removed.
        java.util.Set<domain.datasource.api.SourceBindingTarget> singular =
                new java.util.HashSet<>();
        java.util.Set<domain.datasource.api.SourceBinding> additive =
                new java.util.HashSet<>();
        java.util.ArrayList<domain.datasource.api.SourceBinding> unique =
                new java.util.ArrayList<>();
        for (int index = bindings.size() - 1; index >= 0; index--) {
            domain.datasource.api.SourceBinding binding = bindings.get(index);
            if (binding == null) continue;
            boolean candidate = binding.target().slot().additive();
            if (candidate ? additive.add(binding) : singular.add(binding.target())) {
                unique.add(binding);
            }
        }
        java.util.Collections.reverse(unique);
        bindings.clear();
        bindings.addAll(unique);
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
        if (!replacement.isImported()
                || !existing.importedFrom().equalsIgnoreCase(replacement.importedFrom())) {
            requireOwned(existing, "changed");
        }
        replacement.declarationId(existing.declarationId());
        selections.set(selections.indexOf(existing), replacement);
    }

    /**
     * An imported declaration is owned by the model it came from, and saying so is a
     * different answer from "that name is taken" or "something still points at it". The
     * boolean reports the ordinary refusals; ownership throws, so a caller cannot report
     * one as the other — which is what sent a reader looking for a name clash that was
     * not there.
     */
    private static void requireOwned(Selection selection, String verb) {
        if (selection == null || !selection.isImported()) return;
        throw new IllegalStateException("Selection '" + selection.name()
                + "' is imported from " + selection.importedFrom()
                + " and cannot be " + verb + " here; change it in that model.");
    }

    /** Renames one selection and every model reference to it. */
    public boolean renameSelection(String oldName, String newName) {
        Selection selection = findSelection(oldName);
        String next = newName == null ? "" : newName.trim();
        requireOwned(selection, "renamed");
        if (selection == null || next.isBlank()) return false;
        Selection conflict = findSelection(next);
        if (conflict != null && conflict != selection) return false;
        // A field target names a class or a selection in ONE namespace, and the class
        // wins it. Renaming onto a class name would leave this selection unreachable.
        if (findClass(next) != null) return false;
        String previous = selection.name();
        ConfigurationReferenceInventory references =
                ConfigurationReferenceInventory.of(this);
        selection.name(next);
        references.renameSelectionReferences(selection, previous, next);
        return true;
    }

    /** Removes an unreferenced selection; referenced declarations must be redirected first. */
    public boolean removeSelection(String name) {
        Selection selection = findSelection(name);
        requireOwned(selection, "removed");
        if (selection == null) return false;
        ConfigurationReferenceInventory references =
                ConfigurationReferenceInventory.of(this);
        if (references.blocksSelectionRemoval(selection)) return false;
        if (!selections.remove(selection)) return false;
        buildOperations.removeIf(operation -> operation != null
                && operation.kind().target() == BuildOperation.Kind.Target.SELECTION
                && selection.declarationId().equals(operation.targetDeclarationId()));
        return true;
    }

    public boolean selectionReferenced(String name) {
        Selection selection = findSelection(name);
        return selection != null && ConfigurationReferenceInventory.of(this)
                .referencesSelection(selection);
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

    /** Every configured class carried by one runtime object, in its declared order.
     * The primary type is a fallback, never the sole owner of the object's fields. */
    public List<GeneratedClassModel> classesOf(
            java.util.Collection<String> directClassNames, String primaryType) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (directClassNames != null) names.addAll(directClassNames);
        if (primaryType != null && !primaryType.isBlank()) names.add(primaryType);
        return names.stream().map(this::findClass).filter(java.util.Objects::nonNull)
                .toList();
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
        for (TextSource source : textSources) {
            if (source != null) source.ensureDeclarationId(name());
        }
        for (GeneratedClassModel clazz : classes) {
            if (clazz == null || clazz.transformationSource() == null) continue;
            TransformationClassSource source = clazz.transformationSource();
            GeneratedClassModel input = resolveClass(
                    source.inputClassId(), source.inputClassName());
            if (input != null) source.inputClassReference(
                    input.declarationId(), input.className());
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
                EntityBound discoveryBound = statement.discoveryObjectBound();
                if (discoveryBound.kind() == EntityBound.Kind.VOCABULARY) {
                    Selection discoverySelection = resolveSelection(
                            discoveryBound.selectionId(), discoveryBound.selectionName());
                    if (discoverySelection != null) statement.discoveryObjectSelectionReference(
                            discoverySelection.declarationId(), discoverySelection.name());
                }
            }
            SubclassCondition subclass = clazz.subclassCondition();
            if (subclass.populationBased()) {
                Selection selection = resolveSelection(
                        subclass.selectionId(), subclass.selectionName());
                if (selection != null) clazz.subclassCondition(
                        subclass.rebound(selection.declarationId(), selection.name()));
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
        for (GeneratedClassModel graphClass : graphClasses()) {
            GraphClassSource graph = graphClass.graphSource();
            if (graph == null || graph.startNode() == null) continue;
            GraphDiscoveryConfiguration.StartNode start = graph.startNode();
            if (!start.populationSelection().isBlank()) {
                Selection population = resolveSelection(
                        start.populationSelectionId(), start.populationSelection());
                if (population != null) graph.startNode(start.withPopulation(
                        population.declarationId(), population.name()));
            }
            graph.nextNodes(graph.nextNodes().stream().map(node -> {
                if (node.admissionPopulationSelection().isBlank()) return node;
                Selection population = resolveSelection(node.admissionPopulationSelectionId(),
                        node.admissionPopulationSelection());
                return population == null ? node
                        : node.withAdmission(population.declarationId(), population.name());
            }).toList());
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
            for (OwnedFieldSource override : field.ownedFieldSources()) {
                if (override == null) continue;
                normalizeOwnedBindings(override.sourceBindings(), field.entityClassName(),
                        override.fieldPath(), owner, field.name());
            }
            normalizeFields(field.fields(), owner);
        }
    }

    private static void normalizeBindings(List<domain.datasource.api.SourceBinding> bindings,
            GeneratedClassModel owner) {
        if (bindings == null || owner == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            domain.datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            domain.datasource.api.SourceBindingTarget target = binding.target();
            bindings.set(i, new domain.datasource.api.SourceBinding(
                    new domain.datasource.api.SourceBindingTarget(target.scope(), owner.className(),
                            target.fieldPath(), target.slot(), owner.declarationId(),
                            target.contextClassName(), target.contextFieldPath(),
                            target.contextClassDeclarationId()),
                    binding.recipe()));
        }
    }

    private static void normalizeOwnedBindings(
            List<domain.datasource.api.SourceBinding> bindings, String targetClass,
            String targetPath, GeneratedClassModel owner, String ownerPath) {
        if (bindings == null || owner == null) return;
        for (int i = 0; i < bindings.size(); i++) {
            domain.datasource.api.SourceBinding binding = bindings.get(i);
            if (binding == null) continue;
            domain.datasource.api.SourceBindingTarget address = binding.target();
            bindings.set(i, new domain.datasource.api.SourceBinding(
                    new domain.datasource.api.SourceBindingTarget(address.scope(), targetClass,
                            targetPath, address.slot(), address.classDeclarationId(),
                            owner.className(), ownerPath, owner.declarationId()),
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
        for (TextSource source : textSources) {
            if (source != null) c.textSources.add(source.copy());
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
        c.buildOperations(buildOperations);

        return c;
    }

    /** The authored project written to disk; imported declarations are resolved afresh. */
    public GeneratedProjectModel withoutResolvedImports() {
        GeneratedProjectModel authored = copy();
        authored.classes.removeIf(GeneratedClassModel::isImported);
        authored.selections.removeIf(Selection::isImported);
        authored.textSources.removeIf(TextSource::isImported);
        authored.entityKindRules.removeIf(EntityKindRule::isImported);
        authored.entityRepresentationRules.removeIf(
                EntityRepresentationRule::isImported);
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
