package quiz.transform.app;

import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import objectview.group.MultiRootGroup;
import objectview.group.ViewableGroup;
import objectview.group.ViewableGroupFields;
import objectview.viewconfig.DomainGroupRoot;
import quiz.transform.ConfiguredGroup;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GroupClassSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The one adaptation from a configured group class to ordinary ObjectView groups.
 *
 * <p>The model supplies the semantic fact (which field holds members); the generated
 * or loaded instance supplies the values. ModelBuilder and TransformApp consume these
 * same bindings, so neither infers a group from a class name or field shape.
 */
public final class ConfiguredGroups {

    public record Binding(
            String groupClass,
            String memberClass,
            ViewableGroup<?> root) { }

    private ConfiguredGroups() { }

    public static List<Binding> materialize(
            GeneratedProjectModel model,
            Collection<? extends Viewable> instances) {
        if (model == null || instances == null || instances.isEmpty()) return List.of();
        List<Binding> result = new ArrayList<>();
        for (GeneratedClassModel groupClass : model.classes()) {
            if (groupClass == null || groupClass.groupSpec() == null) continue;
            GroupClassSpec spec = groupClass.groupSpec();
            GeneratedFieldModel memberField = groupClass.effectiveFields(model).stream()
                    .filter(field -> field != null
                            && spec.membersField().equals(field.name()))
                    .findFirst().orElse(null);
            if (memberField == null || memberField.entityClassName().isBlank()) continue;

            List<ViewableGroup<Viewable>> groups = new ArrayList<>();
            List<Viewable> allMembers = new ArrayList<>();
            for (Viewable instance : instances) {
                if (instance == null || !isInstanceOf(model, instance, groupClass.className())) {
                    continue;
                }
                ConfiguredViewableGroup group =
                        new ConfiguredViewableGroup(instance, spec.membersField());
                groups.add(group);
                for (Viewable member : group.getMembers()) {
                    if (!allMembers.contains(member)) allMembers.add(member);
                }
            }
            if (groups.isEmpty()) continue;
            // One node per group class, named after its source category when it has one.
            String identity = groupClass.declarationId().isBlank()
                    ? groupClass.className() : groupClass.declarationId();
            result.add(new Binding(groupClass.className(), memberField.entityClassName(),
                    new ConfiguredClassGroup(
                            identity, rootName(groupClass), groups, allMembers)));
        }
        return List.copyOf(result);
    }

    /**
     * The configured groups as the one root bound to each member type: an "All" root
     * over every instance of that type, holding one child per configured group class.
     * TransformApp starts from the same root name for a type it has no saved tree for.
     */
    public static List<DomainGroupRoot> rootBindings(
            GeneratedProjectModel model,
            Collection<? extends Viewable> instances) {
        Map<String, List<ViewableGroup<?>>> byMember = new java.util.LinkedHashMap<>();
        for (Binding binding : materialize(model, instances)) {
            byMember.computeIfAbsent(binding.memberClass(), ignored -> new ArrayList<>())
                    .add(binding.root());
        }
        List<DomainGroupRoot> result = new ArrayList<>();
        byMember.forEach((memberClass, roots) -> {
            List<Viewable> all = instances.stream()
                    .filter(value -> value != null && isInstanceOf(model, value, memberClass))
                    .map(Viewable.class::cast).toList();
            result.add(new DomainGroupRoot(memberClass,
                    MultiRootGroup.container(roots, "All " + memberClass, all)));
        });
        return List.copyOf(result);
    }

    /**
     * One root per member type, keeping both what was saved and what is configured.
     * The saved TransformApp tree keeps its own children; the configured groups for the
     * same member type are added beside them. They are never part of the saved tree
     * (Save skips {@link ConfiguredGroup}), so nothing here can appear twice, and a
     * regenerated or removed group class shows up on the next load.
     */
    public static List<DomainGroupRoot> combine(
            Collection<DomainGroupRoot> configured,
            Collection<DomainGroupRoot> saved) {
        Map<String, DomainGroupRoot> byMember = new java.util.LinkedHashMap<>();
        if (saved != null) {
            for (DomainGroupRoot binding : saved) {
                if (binding != null) byMember.putIfAbsent(binding.memberType(), binding);
            }
        }
        if (configured != null) {
            for (DomainGroupRoot binding : configured) {
                if (binding == null) continue;
                DomainGroupRoot savedRoot = byMember.get(binding.memberType());
                byMember.put(binding.memberType(), savedRoot == null ? binding
                        : new DomainGroupRoot(binding.memberType(), new WithConfiguredGroups(
                                savedRoot.root(), binding.root())));
            }
        }
        return List.copyOf(byMember.values());
    }

    private static String rootName(GeneratedClassModel groupClass) {
        datasource.api.SourceRecipe recipe =
                wikidata.explore.model.ClassSourceBindings
                        .alternativePopulationSource(groupClass);
        if (recipe != null) try {
            datasource.api.DatasourceOperation operation = recipe.resolve(
                    datasource.Datasources.standard(),
                    datasource.api.BindingScope.CLASS_POPULATION);
            datasource.api.acquisition.PopulationAcquisition acquisition =
                    operation.prepare(new datasource.api.SourceBinding(
                                    datasource.api.SourceBindingTarget.classPopulation(
                                            groupClass.className()), recipe))
                            .configuration(
                                    datasource.api.acquisition.PopulationAcquisition.class);
            if (acquisition != null) return acquisition.displayName();
        } catch (IllegalArgumentException ignored) {
            // Validation reports the malformed recipe. A loaded result must remain
            // inspectable under the ordinary class-group label while it is repaired.
        }
        return groupClass.displayClassName() + " groups";
    }

    private static boolean isInstanceOf(
            GeneratedProjectModel model, Viewable value, String expected) {
        for (String direct : value.directClassNames()) {
            if (model.isSameOrSubclass(direct, expected)) return true;
        }
        return expected.equals(value.typeName());
    }

    /** A generated entity viewed as one group; its ordinary fields stay its fields. */
    private static final class ConfiguredViewableGroup
            implements ViewableGroup<Viewable>, ConfiguredGroup {
        private final Viewable entity;
        private final String membersField;

        private ConfiguredViewableGroup(Viewable entity, String membersField) {
            this.entity = entity;
            this.membersField = membersField;
        }

        @Override public String getIdentifier() { return entity.getIdentifier(); }
        @Override public String getDisplayName() { return entity.getDisplayName(); }
        @Override public String getReferenceLabel() { return entity.getReferenceLabel(); }
        @Override public String typeName() { return entity.typeName(); }
        @Override public String identityTypeName() { return entity.identityTypeName(); }
        @Override public java.util.Set<String> directClassNames() {
            return entity.directClassNames();
        }
        @Override public boolean isPart() { return entity.isPart(); }
        @Override public Role getRole() { return Role.BUCKET; }
        @Override public Viewable getKeyRef() { return null; }
        @Override public Collection<? extends ViewableGroup<Viewable>> getChildren() {
            return List.of();
        }
        @Override public ViewableGroup<Viewable> getParent() { return null; }
        @Override public String getFullName() { return getDisplayName(); }

        @Override public Collection<Viewable> getMembers() {
            Object value = entity.fields().read(membersField);
            Collection<?> values = value instanceof Map<?, ?> map
                    ? map.values() : value instanceof Collection<?> collection
                    ? collection : value == null ? List.of() : List.of(value);
            return values.stream().filter(Viewable.class::isInstance)
                    .map(Viewable.class::cast).toList();
        }

        @Override public FieldSet fields() {
            FieldSet source = entity.fields();
            FieldSet withoutConfiguredMembers = new FieldSet() {
                @Override public List<FieldRef> fields() {
                    return source.fields().stream()
                            .filter(field -> !membersField.equals(field.name())).toList();
                }
                @Override public Object read(String name) { return source.read(name); }
                @Override public boolean has(String name) {
                    return !membersField.equals(name) && source.has(name);
                }
                @Override public void write(String name, Object value) {
                    source.write(name, value);
                }
            };
            return ViewableGroupFields.of(this, withoutConfiguredMembers);
        }
    }

    /** All instances of one group class, under one node. */
    private static final class ConfiguredClassGroup
            implements ViewableGroup<Viewable>, ConfiguredGroup {
        private final String identity;
        private final String label;
        private final List<ViewableGroup<Viewable>> groups;
        private final List<Viewable> members;

        private ConfiguredClassGroup(
                String identity, String label,
                List<ViewableGroup<Viewable>> groups, List<Viewable> members) {
            this.identity = identity;
            this.label = label;
            this.groups = List.copyOf(groups);
            this.members = List.copyOf(members);
        }

        @Override public String getIdentifier() { return identity; }
        @Override public String getDisplayName() { return label; }
        @Override public String getFullName() { return label; }
        @Override public String groupKey() { return identity; }
        @Override public Role getRole() { return Role.UNIVERSE; }
        @Override public Viewable getKeyRef() { return null; }
        @Override public ViewableGroup<Viewable> getParent() { return null; }
        @Override public Collection<ViewableGroup<Viewable>> getChildren() { return groups; }
        @Override public Collection<Viewable> getMembers() { return members; }
    }

    /** A saved root shown with the configured groups of its member type beside its own
     *  children. Everything else, including its saved producer fields, is the saved root. */
    private static final class WithConfiguredGroups implements ViewableGroup<Viewable> {
        private final ViewableGroup<Viewable> saved;
        private final ViewableGroup<Viewable> configured;
        private final List<ViewableGroup<Viewable>> children;

        @SuppressWarnings("unchecked")
        private WithConfiguredGroups(
                ViewableGroup<?> saved, ViewableGroup<?> configured) {
            this.saved = (ViewableGroup<Viewable>) saved;
            this.configured = (ViewableGroup<Viewable>) configured;
            List<ViewableGroup<Viewable>> all = new ArrayList<>(this.saved.getChildren());
            all.addAll(this.configured.getChildren());
            this.children = List.copyOf(all);
        }

        @Override public String getIdentifier() { return saved.getIdentifier(); }
        @Override public String getDisplayName() { return saved.getDisplayName(); }
        @Override public String getReferenceLabel() { return saved.getReferenceLabel(); }
        @Override public String typeName() { return saved.typeName(); }
        @Override public String identityTypeName() { return saved.identityTypeName(); }
        @Override public String getFullName() { return saved.getFullName(); }
        @Override public String groupKey() { return saved.groupKey(); }
        @Override public Role getRole() { return saved.getRole(); }
        @Override public Viewable getKeyRef() { return saved.getKeyRef(); }
        @Override public ViewableGroup<Viewable> getParent() { return saved.getParent(); }
        @Override public Collection<ViewableGroup<Viewable>> getChildren() { return children; }
        // The saved tree owns authored children, not the current class population.
        // Regeneration may have added or removed members since that tree was saved.
        @Override public Collection<Viewable> getMembers() { return configured.getMembers(); }
        @Override public FieldSet fields() {
            return ViewableGroupFields.of(this, saved.fields());
        }
    }
}
