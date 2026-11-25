package domain.config;

import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.ModelConfigurationReference;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceInputRequirement;
import domain.datasource.graph.GraphDiscoveryConfiguration;
import domain.datasource.schema.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every durable reference between configuration sites, derived once from the model.
 *
 * <p>The inventory is the common fact used by validation, dependency navigation and
 * rename discovery.  It deliberately contains references, not editor nodes: #557's
 * configuration projection can turn the same facts into {@code Uses} and
 * {@code Used by} nodes without learning how any construct stores them.</p>
 */
public final class ConfigurationReferenceInventory {

    public enum SiteKind { CLASS, FIELD, SELECTION, CONFIGURATION, DATASOURCE_INPUT }

    /** A model address. Field and configuration paths locate a site within its declaration. */
    public record Site(
            SiteKind kind,
            String declarationId,
            String className,
            String fieldPath,
            String name) {
        public Site {
            kind = Objects.requireNonNull(kind, "kind");
            declarationId = clean(declarationId);
            className = clean(className);
            fieldPath = clean(fieldPath);
            name = clean(name);
        }

        public String displayName() {
            if (kind == SiteKind.FIELD) {
                return className + (fieldPath.isBlank() ? "" : "." + fieldPath);
            }
            if (!name.isBlank()) return name;
            return className;
        }
    }

    public enum Relation {
        EXTENDS,
        ENTITY_FIELD_TARGET,
        OWNED_COMPONENT_TARGET,
        STATEMENT_SOURCE,
        AGGREGATE_SOURCE,
        TRANSFORMATION_INPUT,
        ROLE_OWNER,
        ROLE_FIELD,
        POPULATION_CLASS,
        ENTITY_KIND_CLASS,
        REPRESENTATION_ROLE,
        REPRESENTATION_CLASS,
        CLASS_MEMBERSHIP_BOUND,
        SUBCLASS_POPULATION,
        STATEMENT_OBJECT_BOUND,
        STATEMENT_DISCOVERY_BOUND,
        STATEMENT_SUBJECT_BOUND,
        GRAPH_START_CLASS,
        GRAPH_START_POPULATION,
        GRAPH_OUTPUT_CLASS,
        GRAPH_ADMISSION_POPULATION,
        COMPANION_SUBJECT_FIELD,
        COMPANION_VALUE_FIELD,
        COMPANION_ROLE_FIELD,
        INVERSE_FIELD,
        UNION_SOURCE_FIELD,
        OWNED_FIELD_OVERRIDE,
        CHILD_SORT_FIELD,
        TRANSFORMATION_STEP_FIELD,
        AGGREGATE_MEMBERS_FIELD,
        AGGREGATE_TARGET_FIELD,
        AGGREGATE_SOURCE_FIELD,
        GROUP_MEMBERS_FIELD,
        CANONICAL_KEY_FIELD,
        CANONICAL_REDUCTION_FIELD,
        DISPLAY_NAME_FIELD,
        PRIMARY_LIST_FIELD,
        BUILD_TARGET_CLASS,
        BUILD_TARGET_SELECTION,
        SOURCE_BINDING_TARGET,
        SOURCE_BINDING_CONTEXT,
        DATASOURCE_RECIPE_CLASS,
        DATASOURCE_RECIPE_FIELD,
        DATASOURCE_INPUT
    }

    /** One directed reference from the site that stores it to the site it names. */
    public record Reference(
            Site source,
            Site target,
            Relation relation,
            String role,
            boolean required) {
        public Reference {
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
            relation = Objects.requireNonNull(relation, "relation");
            role = clean(role);
        }
    }

    @FunctionalInterface
    private interface Rewriter {
        void rewrite(String declarationId, String name);
    }

    private record Entry(Reference reference, Rewriter rewriter) { }

    /** One stored datasource binding at the exact model site that owns it. */
    public record BindingSite(
            Site storageSite,
            Site bindingSite,
            SourceBinding binding,
            String storageRole,
            int authoredIndex) {
        public BindingSite {
            storageSite = Objects.requireNonNull(storageSite, "storageSite");
            bindingSite = Objects.requireNonNull(bindingSite, "bindingSite");
            binding = Objects.requireNonNull(binding, "binding");
            storageRole = clean(storageRole);
        }
    }

    private final GeneratedProjectModel project;
    private final List<Entry> entries;
    private final List<BindingSite> bindingSites;

    private ConfigurationReferenceInventory(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        this.project = Objects.requireNonNull(project, "project");
        List<Entry> found = new ArrayList<>();
        List<BindingSite> bindings = new ArrayList<>();
        collectClasses(project, registry, found, bindings);
        collectRules(project, found);
        collectSelections(project, found);
        collectBuildOperations(project, found);
        this.entries = List.copyOf(found);
        this.bindingSites = List.copyOf(bindings);
    }

    public static ConfigurationReferenceInventory of(GeneratedProjectModel project) {
        return of(project, domain.datasource.Datasources.standard());
    }

    public static ConfigurationReferenceInventory of(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        return new ConfigurationReferenceInventory(project,
                Objects.requireNonNull(registry, "registry"));
    }

    public List<Reference> references() {
        return entries.stream().map(Entry::reference).toList();
    }

    public List<BindingSite> bindingSites() { return bindingSites; }

    public List<BindingSite> bindingsStoredAt(Site storageSite) {
        if (storageSite == null) return List.of();
        return bindingSites.stream()
                .filter(site -> site.storageSite().equals(storageSite)).toList();
    }

    /** The stable site of one declared field path, shared by projection and reference
     * navigation. */
    public static Site fieldSite(GeneratedClassModel owner, String fieldPath) {
        if (owner == null) throw new IllegalArgumentException("Field owner is required");
        return new Site(SiteKind.FIELD, owner.declarationId(), owner.className(),
                clean(fieldPath), "");
    }

    public List<Reference> from(Site source) {
        return entries.stream().map(Entry::reference)
                .filter(reference -> reference.source().equals(source)).toList();
    }

    public List<Reference> to(Site target) {
        return entries.stream().map(Entry::reference)
                .filter(reference -> reference.target().equals(target)).toList();
    }

    /** Resolves a projected navigation target through the same inventory that named it. */
    public Object resolve(Site target) {
        return resolve(project, target);
    }

    public static Object resolve(GeneratedProjectModel project, Site target) {
        if (target == null) return null;
        return switch (target.kind()) {
            case CLASS -> project.resolveClass(target.declarationId(), target.className());
            case FIELD -> resolveField(project, target.className(), target.fieldPath());
            case SELECTION -> project.resolveSelection(target.declarationId(), target.name());
            case DATASOURCE_INPUT -> project.resolveClass(
                    target.declarationId(), target.className());
            case CONFIGURATION -> resolveGraphNodeOwner(project, target);
        };
    }

    private static GeneratedClassModel resolveGraphNodeOwner(
            GeneratedProjectModel project, Site target) {
        if (target.declarationId().isBlank() || target.fieldPath().isBlank()) return null;
        GeneratedClassModel clazz = project.resolveClass(target.declarationId(), target.className());
        if (clazz == null || clazz.graphSource() == null) return null;
        for (int i = 0; i < clazz.graphSource().nextNodes().size(); i++) {
            if (graphNodeSite(clazz, i).fieldPath().equals(target.fieldPath())) return clazz;
        }
        return null;
    }

    public List<Reference> fromClass(GeneratedClassModel clazz) {
        if (clazz == null) return List.of();
        String id = clazz.declarationId();
        return entries.stream().map(Entry::reference).filter(reference ->
                id.equals(reference.source().declarationId())
                        && reference.source().kind() != SiteKind.SELECTION).toList();
    }

    /** Every site that currently resolves to this class. */
    public List<Reference> referencesToClass(GeneratedClassModel clazz) {
        if (clazz == null) return List.of();
        return entries.stream().map(Entry::reference)
                .filter(reference -> isClassTarget(reference.target(), clazz)).toList();
    }

    /** Central class-reference rewrite used by {@link GeneratedProjectModel#renameClass}. */
    void renameClassReferences(
            GeneratedClassModel clazz, String previousName, String nextName) {
        if (clazz == null) return;
        for (Entry entry : entries) {
            if (entry.rewriter() != null
                    && isClassTarget(entry.reference().target(), clazz, previousName)) {
                entry.rewriter().rewrite(clazz.declarationId(), nextName);
            }
        }
    }

    /** Central selection-reference rewrite used by selection rename and removal checks. */
    void renameSelectionReferences(
            Selection selection, String previousName, String nextName) {
        if (selection == null) return;
        for (Entry entry : entries) {
            Site target = entry.reference().target();
            if (entry.rewriter() != null && target.kind() == SiteKind.SELECTION
                    && sameDeclaration(selection.declarationId(), target.declarationId(),
                            previousName, target.name())) {
                entry.rewriter().rewrite(selection.declarationId(), nextName);
            }
        }
    }

    public boolean referencesSelection(Selection selection) {
        return referencesSelection(selection, reference -> true);
    }

    /**
     * Whether a selection has a consumer that must be redirected before removal.
     *
     * <p>A build step which publishes the selection is owned by that selection rather
     * than an independent consumer: removing the selection removes that step with it.
     * It remains in {@link #referencesSelection(Selection)} and in the inventory for
     * navigation and validation.
     */
    boolean blocksSelectionRemoval(Selection selection) {
        return referencesSelection(selection,
                reference -> reference.relation() != Relation.BUILD_TARGET_SELECTION);
    }

    private boolean referencesSelection(Selection selection,
            java.util.function.Predicate<Reference> include) {
        if (selection == null) return false;
        return entries.stream().map(Entry::reference).filter(include)
                .map(Reference::target).anyMatch(target -> target.kind() == SiteKind.SELECTION
                        && sameDeclaration(selection.declarationId(), target.declarationId(),
                                selection.name(), target.name()));
    }

    /** Required targets which do not resolve in the current project. */
    public List<Reference> unresolvedRequiredReferences() {
        return entries.stream().map(Entry::reference)
                .filter(Reference::required)
                .filter(reference -> !resolves(reference.target())).toList();
    }

    private boolean resolves(Site target) {
        return switch (target.kind()) {
            case CLASS -> project.resolveClass(
                    target.declarationId(), target.className()) != null;
            case FIELD -> resolveField(project, target.className(), target.fieldPath()) != null;
            case SELECTION -> project.resolveSelection(
                    target.declarationId(), target.name()) != null;
            case CONFIGURATION, DATASOURCE_INPUT -> true;
        };
    }

    private static void collectClasses(GeneratedProjectModel project,
            DatasourceRegistry registry, List<Entry> entries,
            List<BindingSite> bindingSites) {
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) continue;
            Site owner = classSite(clazz);

            if (clazz.hasBase()) {
                add(entries, owner, classTarget(project, clazz.baseClassId(),
                                clazz.baseClassName()), Relation.EXTENDS, "base class", true,
                        clazz::baseClassReference);
            }
            StatementClassSource statement = clazz.statementSource();
            if (statement != null) {
                if (!statement.sourceClassName().isBlank()) {
                    add(entries, owner, classTarget(project, statement.sourceClassId(),
                                    statement.sourceClassName()), Relation.STATEMENT_SOURCE,
                            "statement source class", true, statement::sourceClassReference);
                }
                addSelectionBound(entries, project, owner, statement.objectBound(),
                        Relation.STATEMENT_OBJECT_BOUND,
                        statement::valueSelectionReference);
                addSelectionBound(entries, project, owner, statement.discoveryObjectBound(),
                        Relation.STATEMENT_DISCOVERY_BOUND,
                        statement::discoveryObjectSelectionReference);
                addSelectionBound(entries, project, owner, statement.subjectBound(),
                        Relation.STATEMENT_SUBJECT_BOUND,
                        statement::subjectSelectionReference);
            }
            AggregateClassSource aggregate = clazz.aggregateSource();
            if (aggregate != null) {
                add(entries, owner, classTarget(project, aggregate.sourceClassId(),
                                aggregate.sourceClassName()), Relation.AGGREGATE_SOURCE,
                        "aggregate source class", true, aggregate::sourceClassReference);
                addField(entries, owner, fieldTarget(project, clazz.className(),
                                aggregate.membersField()), Relation.AGGREGATE_MEMBERS_FIELD,
                        "members field", true, null);
                for (AggregateClassSource.Key key : aggregate.keys()) {
                    if (key == null) continue;
                    addField(entries, owner, fieldTarget(project, clazz.className(),
                                    key.targetField()), Relation.AGGREGATE_TARGET_FIELD,
                            "aggregate target field", true, null);
                    addField(entries, owner, fieldTarget(project,
                                    aggregate.sourceClassName(), key.sourceField()),
                            Relation.AGGREGATE_SOURCE_FIELD,
                            "aggregate source field", true, null);
                }
            }
            TransformationClassSource transformation = clazz.transformationSource();
            if (transformation != null && !transformation.inputClassName().isBlank()) {
                add(entries, owner, classTarget(project, transformation.inputClassId(),
                                transformation.inputClassName()),
                        Relation.TRANSFORMATION_INPUT, "transformation input class", true,
                        transformation::inputClassReference);
                for (TransformationStep step : transformation.steps()) {
                    if (step == null || step.fieldPath().isBlank()) continue;
                    addField(entries, owner, fieldPathTarget(project,
                                    project.resolveClass(transformation.inputClassId(),
                                            transformation.inputClassName()),
                                    step.fieldPath()),
                            Relation.TRANSFORMATION_STEP_FIELD,
                            "transformation field", true, null);
                }
            }
            GroupClassSpec group = clazz.groupSpec();
            if (group != null) {
                addField(entries, owner, fieldTarget(project, clazz.className(),
                                group.membersField()), Relation.GROUP_MEMBERS_FIELD,
                        "group members field", true, null);
            }
            SubclassCondition subclass = clazz.subclassCondition();
            EntityBound membership = clazz.membership();
            if (membership.kind() == EntityBound.Kind.VOCABULARY) {
                addSelectionBound(entries, project, owner, membership,
                        Relation.CLASS_MEMBERSHIP_BOUND,
                        (id, name) -> clazz.membership(membership.rebound(id, name)));
            }
            if (subclass.populationBased()) {
                add(entries, owner, selectionTarget(project, subclass.selectionId(),
                                subclass.selectionName()), Relation.SUBCLASS_POPULATION,
                        "subclass population", true,
                        (id, name) -> clazz.subclassCondition(subclass.rebound(id, name)));
            }
            collectCanonical(project, clazz, owner, entries);
            collectGraph(project, clazz, entries);
            collectBindings(project, registry, owner, clazz.sourceBindings(),
                    "class", entries, bindingSites);
            for (GeneratedFieldModel field : clazz.fields()) {
                collectField(project, registry, clazz, "", field, entries, bindingSites);
            }
        }
    }

    private static void collectField(GeneratedProjectModel project,
            DatasourceRegistry registry, GeneratedClassModel owner, String prefix,
            GeneratedFieldModel field, List<Entry> entries,
            List<BindingSite> bindingSites) {
        if (field == null) return;
        String path = prefix.isBlank() ? field.name() : prefix + "." + field.name();
        Site source = fieldSite(owner, path);
        if (!field.entityClassName().isBlank()) {
            Site target = declarationTarget(project, field.entityDeclarationId(),
                    field.entityClassName());
            Relation relation = OwnedClassSemantics.isOwnerQidField(field, project)
                    ? Relation.OWNED_COMPONENT_TARGET : Relation.ENTITY_FIELD_TARGET;
            add(entries, source, target, relation, "entity field target", false,
                    field::entityReference);
        }

        FieldSourceMapping mapping = field.mapping();
        addLocalField(entries, project, owner, source, mapping.subjectField(),
                Relation.COMPANION_SUBJECT_FIELD, "companion subject field");
        addLocalField(entries, project, owner, source, mapping.matchValueField(),
                Relation.COMPANION_VALUE_FIELD, "companion value field");
        addLocalField(entries, project, owner, source, mapping.matchRoleField(),
                Relation.COMPANION_ROLE_FIELD, "companion role field");
        if (!mapping.inverseField().isBlank() && !field.entityClassName().isBlank()) {
            addField(entries, source, fieldTarget(project, field.entityClassName(),
                            mapping.inverseField()), Relation.INVERSE_FIELD,
                    "forward field", true, null);
        }
        if (field.hasSort() && !field.entityClassName().isBlank()) {
            addField(entries, source, fieldTarget(project, field.entityClassName(),
                            field.sortFieldName()), Relation.CHILD_SORT_FIELD,
                    "child sort field", true, null);
        }
        for (String unionPath : mapping.unionSourcePaths()) {
            if (unionPath == null || unionPath.isBlank()) continue;
            addField(entries, source, fieldPathTarget(project, owner, unionPath),
                    Relation.UNION_SOURCE_FIELD, "union source field", true, null);
        }
        for (OwnedFieldSource override : field.ownedFieldSources()) {
            if (override == null || override.fieldPath().isBlank()) continue;
            addField(entries, source, fieldTarget(project, field.entityClassName(),
                            override.fieldPath()), Relation.OWNED_FIELD_OVERRIDE,
                    "owned-field source", true, null);
            collectBindings(project, registry, source, override.sourceBindings(),
                    "owned-field source", entries, bindingSites);
        }
        collectBindings(project, registry, source, field.sourceBindings(),
                "field", entries, bindingSites);
        for (GeneratedFieldModel child : field.fields()) {
            collectField(project, registry, owner, path, child, entries, bindingSites);
        }
    }

    private static void collectRules(
            GeneratedProjectModel project, List<Entry> entries) {
        int index = 0;
        for (EntityKindRule rule : project.entityKindRules()) {
            if (rule == null || rule.className().isBlank()) continue;
            Site source = configurationSite("entity-kind:" + index++,
                    rule.className() + " entity-kind rule");
            add(entries, source, classTarget(project, rule.classId(), rule.className()),
                    Relation.ENTITY_KIND_CLASS, "classified class", true,
                    rule::classReference);
        }
        index = 0;
        for (EntityRepresentationRule rule : project.entityRepresentationRules()) {
            if (rule == null) continue;
            int position = index++;
            Site source = configurationSite("entity-representation:" + position,
                    "Representation rule " + (position + 1));
            if (!rule.roleClassName().isBlank()) {
                add(entries, source, classTarget(project, rule.roleClassId(),
                                rule.roleClassName()), Relation.REPRESENTATION_ROLE,
                        "role class", true, rule::roleClassReference);
            }
            if (!rule.representationClassName().isBlank()) {
                add(entries, source, classTarget(project, rule.representationClassId(),
                                rule.representationClassName()),
                        Relation.REPRESENTATION_CLASS, "representation class", true,
                        rule::representationClassReference);
            }
        }
    }

    private static void collectSelections(
            GeneratedProjectModel project, List<Entry> entries) {
        for (Selection selection : project.selections()) {
            if (selection instanceof RoleSelection role) {
                Site source = selectionSite(selection);
                add(entries, source, classTarget(project, role.ownerClassId(),
                                role.ownerClassName()), Relation.ROLE_OWNER,
                        "role owner class", true, role::ownerReference);
                addField(entries, source, fieldTarget(project, role.ownerClassName(),
                                role.fieldName()), Relation.ROLE_FIELD,
                        "role field", true, null);
            } else if (selection instanceof PopulationSelection population
                    && !population.className().isBlank()) {
                Site source = selectionSite(selection);
                add(entries, source, classTarget(project, "", population.className()),
                        Relation.POPULATION_CLASS, "population class", true,
                        (id, name) -> population.className(name));
            }
        }
    }

    private static void collectCanonical(GeneratedProjectModel project,
            GeneratedClassModel clazz, Site owner, List<Entry> entries) {
        CanonicalSpec canonical = clazz.canonical();
        if (canonical == null) return;
        for (String field : canonical.keyFields()) {
            if (field == null || field.isBlank()) continue;
            addField(entries, owner, fieldTarget(project, clazz.className(), field),
                    Relation.CANONICAL_KEY_FIELD, "canonical key field", true, null);
        }
        for (String field : canonical.reductions().keySet()) {
            if (field == null || field.isBlank()) continue;
            addField(entries, owner, fieldTarget(project, clazz.className(), field),
                    Relation.CANONICAL_REDUCTION_FIELD,
                    "canonical reduction field", true, null);
        }
        if (canonical.displayNameMode() == CanonicalSpec.DisplayNameMode.FIELD
                && !canonical.displayNameField().isBlank()) {
            addField(entries, owner, fieldTarget(project, clazz.className(),
                            canonical.displayNameField()), Relation.DISPLAY_NAME_FIELD,
                    "display-name field", true, null);
        }
        if (!canonical.primaryListField().isBlank()) {
            addField(entries, owner, fieldTarget(project, clazz.className(),
                            canonical.primaryListField()), Relation.PRIMARY_LIST_FIELD,
                    "canonical list field", true, null);
        }
    }

    private static void collectBuildOperations(
            GeneratedProjectModel project, List<Entry> entries) {
        int index = 0;
        for (BuildOperation operation : project.buildOperations()) {
            if (operation == null) continue;
            int position = index++;
            Site source = configurationSite("build:" + position + ":"
                    + operation.declarationId(), "Build step " + (position + 1) + " — "
                            + operation.kind().label());
            if (operation.kind().target() == BuildOperation.Kind.Target.CLASS
                    || operation.kind().target() == BuildOperation.Kind.Target.GRAPH) {
                GeneratedClassModel target = operation.target(project);
                Site targetSite = target == null
                        ? new Site(SiteKind.CLASS, operation.targetDeclarationId(),
                                "", "", "") : classSite(target);
                add(entries, source, targetSite, Relation.BUILD_TARGET_CLASS,
                        "build target", true, null);
            } else if (operation.kind().target()
                    == BuildOperation.Kind.Target.SELECTION) {
                Selection target = operation.selectionTarget(project);
                Site targetSite = target == null
                        ? new Site(SiteKind.SELECTION, operation.targetDeclarationId(),
                                "", "", "") : selectionSite(target);
                add(entries, source, targetSite, Relation.BUILD_TARGET_SELECTION,
                        "build target", true, null);
            }
        }
    }

    private static void collectGraph(GeneratedProjectModel project,
            GeneratedClassModel clazz, List<Entry> entries) {
        GraphClassSource graph = clazz.graphSource();
        if (graph == null || graph.startNode() == null) return;
        Site source = classSite(clazz);
        GraphDiscoveryConfiguration.StartNode start = graph.startNode();
        if (!start.qidSourceClass().isBlank()) {
            add(entries, source, classTarget(project, "", start.qidSourceClass()),
                    Relation.GRAPH_START_CLASS, "graph start class", true,
                    (id, name) -> graph.startNode(new GraphDiscoveryConfiguration.StartNode(
                            name, start.populationSelection(), start.use(),
                            start.populationSelectionId())));
        } else if (!start.populationSelection().isBlank()) {
            add(entries, source, selectionTarget(project, start.populationSelectionId(),
                            start.populationSelection()), Relation.GRAPH_START_POPULATION,
                    "graph start population", true,
                    (id, name) -> graph.startNode(start.withPopulation(id, name)));
        }
        List<GraphDiscoveryConfiguration.NextNode> nodes = graph.nextNodes();
        for (int index = 0; index < nodes.size(); index++) {
            GraphDiscoveryConfiguration.NextNode node = nodes.get(index);
            Site nodeSite = graphNodeSite(clazz, index);
            if (node.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION) {
                int at = index;
                add(entries, nodeSite, classTarget(project, "", node.populationClass()),
                        Relation.GRAPH_OUTPUT_CLASS, "output class", true,
                        (id, name) -> replaceGraphNode(graph, at,
                                graph.nextNodes().get(at).withPopulationClass(name)));
            }
            if (!node.admissionPopulationSelection().isBlank()) {
                int at = index;
                add(entries, nodeSite, selectionTarget(project,
                                node.admissionPopulationSelectionId(),
                                node.admissionPopulationSelection()),
                        Relation.GRAPH_ADMISSION_POPULATION,
                        "admission population", true,
                        (id, name) -> replaceGraphNode(graph, at,
                                graph.nextNodes().get(at).withAdmission(id, name)));
            }
        }
    }

    /** The configuration site of a next node at its ordered position in the graph. */
    static Site graphNodeSite(GeneratedClassModel clazz, int index) {
        return new Site(SiteKind.CONFIGURATION, clazz.declarationId(), clazz.className(),
                "path/node:" + index, clazz.className() + " node " + (index + 1));
    }

    private static void replaceGraphNode(GraphClassSource graph, int index,
            GraphDiscoveryConfiguration.NextNode replacement) {
        List<GraphDiscoveryConfiguration.NextNode> nodes = new ArrayList<>(graph.nextNodes());
        nodes.set(index, replacement);
        graph.nextNodes(nodes);
    }

    private static void collectBindings(GeneratedProjectModel project,
            DatasourceRegistry registry, Site storageSite,
            List<SourceBinding> bindings, String storageRole, List<Entry> entries,
            List<BindingSite> bindingSites) {
        if (bindings == null) return;
        java.util.Map<String, Integer> slotPositions = new java.util.LinkedHashMap<>();
        for (int index = 0; index < bindings.size(); index++) {
            SourceBinding binding = bindings.get(index);
            if (binding == null) continue;
            int at = index;
            SourceBindingTarget target = binding.target();
            String slotSite = target.slot().name() + "|"
                    + target.contextClassDeclarationId() + "|" + target.contextClassName()
                    + "|" + target.contextFieldPath();
            int authoredPosition = slotPositions.merge(slotSite, 1, Integer::sum) - 1;
            Site bindingSite = bindingSite(storageSite, target, authoredPosition);
            bindingSites.add(new BindingSite(storageSite, bindingSite, binding,
                    storageRole, authoredPosition));
            add(entries, bindingSite,
                    classTarget(project, target.classDeclarationId(), target.className()),
                    Relation.SOURCE_BINDING_TARGET, storageRole + " binding target", true,
                    (id, name) -> bindings.set(at, retarget(bindings.get(at),
                            id, name, false)));
            if (target.contextual()) {
                add(entries, bindingSite, classTarget(project,
                                target.contextClassDeclarationId(), target.contextClassName()),
                        Relation.SOURCE_BINDING_CONTEXT, "ownership context", true,
                        (id, name) -> bindings.set(at, retarget(bindings.get(at),
                                id, name, true)));
            }
            DatasourceOperation operation;
            try {
                operation = binding.resolve(registry);
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                continue;
            }
            for (ModelConfigurationReference modelReference
                    : operation.configurationReferences(binding)) {
                String className = modelReference.classSource()
                        == ModelConfigurationReference.ClassSource.BINDING_FIELD_TARGET
                        ? bindingFieldTargetClass(project, binding) : modelReference.className();
                if (className.isBlank()) continue;
                Site referenced = modelReference.kind()
                        == ModelConfigurationReference.Kind.CLASS
                        ? classTarget(project, "", className)
                        : fieldTarget(project, className, modelReference.fieldPath());
                Rewriter recipeRewriter = modelReference.classParameterKey().isBlank()
                        ? null : (id, name) -> bindings.set(at,
                                rewriteRecipeParameter(bindings.get(at),
                                        modelReference.classParameterKey(), name));
                add(entries, bindingSite, referenced,
                        modelReference.kind() == ModelConfigurationReference.Kind.CLASS
                                ? Relation.DATASOURCE_RECIPE_CLASS
                                : Relation.DATASOURCE_RECIPE_FIELD,
                        modelReference.role(), modelReference.required(), recipeRewriter);
            }
            try {
                for (SourceInputRequirement requirement
                        : operation.prepare(binding).inputRequirements()) {
                    if (requirement.kind()
                            != SourceInputRequirement.Kind.ARTICLE_CORRESPONDENCE) continue;
                    for (String carrier : ArticleCorrespondencePlan.articleCarriers(
                            project, target)) {
                        GeneratedClassModel carrierClass = project.findClass(carrier);
                        Site input = new Site(SiteKind.DATASOURCE_INPUT,
                                carrierClass == null ? "" : carrierClass.declarationId(),
                                carrier, "", "article correspondence");
                        add(entries, bindingSite, input, Relation.DATASOURCE_INPUT,
                                requirement.sourceId() + " article correspondence", true, null);
                    }
                }
            } catch (IllegalArgumentException invalidRecipe) {
                // Recipe validation owns this problem.  A malformed recipe must not hide
                // its ordinary model references or make inventory inspection fail.
            }
        }
    }

    private static SourceBinding retarget(SourceBinding binding,
            String id, String name, boolean context) {
        SourceBindingTarget target = binding.target();
        return new SourceBinding(new SourceBindingTarget(target.scope(),
                context ? target.className() : name, target.fieldPath(), target.slot(),
                context ? target.classDeclarationId() : id,
                context ? name : target.contextClassName(), target.contextFieldPath(),
                context ? id : target.contextClassDeclarationId()), binding.recipe());
    }

    private static SourceBinding rewriteRecipeParameter(
            SourceBinding binding, String key, String value) {
        java.util.LinkedHashMap<String, String> parameters =
                new java.util.LinkedHashMap<>(binding.recipe().parameters());
        parameters.put(key, clean(value));
        return new SourceBinding(binding.target(), new domain.datasource.api.SourceRecipe(
                binding.recipe().providerId(), binding.recipe().operationId(), parameters));
    }

    private static String bindingFieldTargetClass(
            GeneratedProjectModel project, SourceBinding binding) {
        SourceBindingTarget target = binding.target();
        GeneratedFieldModel field = resolveField(project, target.className(), target.fieldPath());
        return field == null ? "" : field.entityClassName();
    }

    private static void addSelectionBound(List<Entry> entries,
            GeneratedProjectModel project, Site source, EntityBound bound, Relation relation,
            Rewriter rewriter) {
        if (bound == null || bound.kind() != EntityBound.Kind.VOCABULARY) return;
        add(entries, source, selectionTarget(project, bound.selectionId(),
                        bound.selectionName()), relation, "selection bound", true, rewriter);
    }

    private static void addLocalField(List<Entry> entries,
            GeneratedProjectModel project, GeneratedClassModel owner, Site source,
            String fieldName, Relation relation, String role) {
        if (fieldName == null || fieldName.isBlank()) return;
        addField(entries, source, fieldTarget(project, owner.className(), fieldName),
                relation, role, true, null);
    }

    private static void addField(List<Entry> entries, Site source, Site target,
            Relation relation, String role, boolean required, Rewriter rewriter) {
        add(entries, source, target, relation, role, required, rewriter);
    }

    private static void add(List<Entry> entries, Site source, Site target,
            Relation relation, String role, boolean required, Rewriter rewriter) {
        entries.add(new Entry(new Reference(source, target, relation, role, required), rewriter));
    }

    static Site classSite(GeneratedClassModel clazz) {
        return new Site(SiteKind.CLASS, clazz.declarationId(), clazz.className(), "", "");
    }

    private static Site selectionSite(Selection selection) {
        return new Site(SiteKind.SELECTION, selection.declarationId(), "", "",
                selection.name());
    }

    private static Site configurationSite(String path, String name) {
        return new Site(SiteKind.CONFIGURATION, "", "", path, name);
    }

    private static Site bindingSite(Site storage, SourceBindingTarget target, int index) {
        String context = "";
        if (target.contextual()) {
            String owner = target.contextClassDeclarationId().isBlank()
                    ? target.contextClassName() : target.contextClassDeclarationId();
            context = "context:" + owner + ":" + target.contextFieldPath() + "/";
        }
        String configurationPath = context + target.slot().id()
                + (target.slot().additive() ? ":" + index : "");
        String name = storage.displayName() + " — " + target.slot().id().replace('-', ' ')
                + (target.slot().additive() ? " " + (index + 1) : "");
        return new Site(SiteKind.CONFIGURATION, storage.declarationId(),
                storage.className(), storage.fieldPath() + "/binding/" + configurationPath,
                name + (context.isEmpty() ? "" : " (" + target.contextClassName()
                        + "." + target.contextFieldPath() + ")"));
    }

    private static Site classTarget(
            GeneratedProjectModel project, String id, String name) {
        GeneratedClassModel resolved = project.resolveClass(id, name);
        return resolved == null
                ? new Site(SiteKind.CLASS, id, name, "", "") : classSite(resolved);
    }

    private static Site selectionTarget(
            GeneratedProjectModel project, String id, String name) {
        Selection resolved = project.resolveSelection(id, name);
        return resolved == null
                ? new Site(SiteKind.SELECTION, id, "", "", name)
                : selectionSite(resolved);
    }

    /** A field target may deliberately name a Selection rather than a class. */
    private static Site declarationTarget(
            GeneratedProjectModel project, String id, String name) {
        GeneratedClassModel clazz = project.resolveClass(id, name);
        if (clazz != null) return classSite(clazz);
        Selection selection = project.resolveSelection(id, name);
        if (selection != null) return selectionSite(selection);
        return new Site(SiteKind.CLASS, id, name, "", "");
    }

    private static Site fieldTarget(
            GeneratedProjectModel project, String className, String path) {
        GeneratedClassModel clazz = project.findClass(className);
        return new Site(SiteKind.FIELD, clazz == null ? "" : clazz.declarationId(),
                className, path, "");
    }

    private static Site fieldPathTarget(GeneratedProjectModel project,
            GeneratedClassModel owner, String path) {
        if (owner == null) return fieldTarget(project, "", path);
        String[] segments = clean(path).split("\\.");
        GeneratedClassModel current = owner;
        GeneratedFieldModel field = null;
        StringBuilder resolvedPath = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (current == null || segments[index].isBlank()) break;
            field = findEffectiveField(current, project, segments[index]);
            if (field == null) break;
            if (resolvedPath.length() > 0) resolvedPath.append('.');
            resolvedPath.append(segments[index]);
            if (index < segments.length - 1) {
                current = field.type() == FieldType.ENTITY
                        ? project.findClass(field.entityClassName()) : null;
                resolvedPath.setLength(0);
            }
        }
        return current == null || field == null
                ? fieldTarget(project, owner.className(), path)
                : fieldTarget(project, current.className(), resolvedPath.toString());
    }

    static GeneratedFieldModel resolveField(
            GeneratedProjectModel project, String className, String path) {
        GeneratedClassModel current = project == null ? null : project.findClass(className);
        if (current == null || clean(path).isBlank()) return null;
        String[] segments = clean(path).split("\\.");
        List<GeneratedFieldModel> available = current.effectiveFields(project);
        GeneratedFieldModel field = null;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            field = available.stream().filter(value -> value != null
                            && segment.equals(value.name()))
                    .findFirst().orElse(null);
            if (field == null) return null;
            if (index < segments.length - 1) {
                if (!field.fields().isEmpty()) {
                    available = field.fields();
                } else {
                    if (field.type() != FieldType.ENTITY) return null;
                    current = project.findClass(field.entityClassName());
                    if (current == null) return null;
                    available = current.effectiveFields(project);
                }
            }
        }
        return field;
    }

    private static GeneratedFieldModel findEffectiveField(GeneratedClassModel owner,
            GeneratedProjectModel project, String name) {
        return owner.effectiveFields(project).stream()
                .filter(field -> field != null && name.equals(field.name()))
                .findFirst().orElse(null);
    }

    private static boolean isClassTarget(Site site, GeneratedClassModel clazz) {
        return isClassTarget(site, clazz, clazz.className());
    }

    private static boolean isClassTarget(
            Site site, GeneratedClassModel clazz, String nameHint) {
        return (site.kind() == SiteKind.CLASS || site.kind() == SiteKind.FIELD
                || site.kind() == SiteKind.DATASOURCE_INPUT)
                && sameDeclaration(clazz.declarationId(), site.declarationId(),
                        nameHint, site.className());
    }

    private static boolean sameDeclaration(String declarationId, String referenceId,
            String name, String nameHint) {
        String id = clean(declarationId);
        String ref = clean(referenceId);
        return !id.isBlank() && id.equals(ref)
                || ref.isBlank() && clean(name).equalsIgnoreCase(clean(nameHint));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
