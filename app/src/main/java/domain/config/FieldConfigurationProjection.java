package domain.config;

import domain.datasource.api.ConfigurationElement;
import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceRecipe;
import domain.datasource.api.BindingScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one model-layer configuration tree for a declared field. */
public final class FieldConfigurationProjection {

    public static final String DEFINITION = "field-definition";
    public static final String PRIMARY = "field-primary-source";
    public static final String ADVANCED = "field-advanced-source";
    public static final String FALLBACK = "field-fallback-source";
    public static final String CATEGORY = "field-category-source";
    public static final String EVIDENCE = "field-candidate-evidence";
    public static final String REFINE = "field-refine";
    public static final String REFERENCE = ConfigurationReferenceProjection.DETAIL;

    private FieldConfigurationProjection() { }

    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel owner, GeneratedFieldModel field,
            DatasourceRegistry registry) {
        return project(project, owner, field, registry, null);
    }

    /** The current production site and its edited advanced recipe, if the draft has one. */
    public record SourceDraft(OwnedComponentSite site, SourceRecipe recipe) {
        public boolean edits(SourceBindingTarget target) {
            return site == null ? !target.contextual()
                    : site.ownerClass().equals(target.contextClassName())
                            && site.ownerField().equals(target.contextFieldPath());
        }
    }

    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel owner, GeneratedFieldModel field,
            DatasourceRegistry registry, SourceDraft draft) {
        if (project == null || owner == null || field == null || registry == null) {
            throw new IllegalArgumentException("Project, owner, field and registry are required");
        }
        String path = pathOf(owner.fields(), field, "");
        if (path.isBlank()) {
            throw new IllegalArgumentException(
                    "Field " + field.name() + " is not declared by " + owner.className());
        }
        ConfigurationReferenceInventory inventory =
                ConfigurationReferenceInventory.of(project, registry);
        ConfigurationReferenceInventory.Site fieldSite =
                ConfigurationReferenceInventory.fieldSite(owner, path);
        List<ConfigurationReferenceInventory.BindingSite> allBindings =
                inventory.bindingSites().stream()
                        .filter(site -> site.binding().target().scope() == BindingScope.FIELD_VALUE)
                        .filter(site -> site.binding().target().className().equals(owner.className())
                                && site.binding().target().fieldPath().equals(path))
                        .toList();
        List<ConfigurationReferenceInventory.BindingSite> bindings = allBindings.stream()
                .filter(binding -> !binding.binding().target().contextual()).toList();
        SourceBindingTarget directContext = new SourceBindingTarget(
                BindingScope.FIELD_VALUE, owner.className(), path,
                SourceBindingSlot.PRIMARY_FIELD_VALUE, owner.declarationId());

        List<ConfigurationNode> children = new ArrayList<>();
        children.add(node(owner, path, "definition", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Definition", DEFINITION, List.of()));
        SlotFit fit = new SlotFit(fallbackFits(field, registry),
                WikipediaCategoryRule.fillsFieldOfType(field.type()));
        var sites = contextualSources(project, owner, field, path, allBindings, registry, fit, draft);
        children.add(producedBy(owner, path, "", field, bindings, registry,
                directContext, fit, draft, sites.available()));
        children.add(candidateEvidence(owner, path, "", bindings, registry,
                directContext));
        children.addAll(sites.configured());
        ConfigurationReferenceProjection.needs(fieldSite,
                referencesFrom(inventory, fieldSite, allBindings)).ifPresent(children::add);
        ConfigurationReferenceProjection.usedBy(fieldSite, inventory.to(fieldSite))
                .ifPresent(children::add);
        children.add(node(owner, path, "refine", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Refine", REFINE, List.of()));
        return node(owner, path, "field", ConfigurationNode.Kind.STORED_CONFIGURATION,
                owner.className() + "." + path, DEFINITION, children);
    }

    private record ContextualSources(List<ConfigurationNode> configured, List<ConfigurationNode> available) { }

    private static ContextualSources contextualSources(
            GeneratedProjectModel project, GeneratedClassModel owner, GeneratedFieldModel field, String path,
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            DatasourceRegistry registry, SlotFit fit, SourceDraft draft) {
        Map<OwnedComponentSite, List<ConfigurationReferenceInventory.BindingSite>> byContext =
                new LinkedHashMap<>();
        for (ConfigurationReferenceInventory.BindingSite site : bindings) {
            var target = site.binding().target();
            if (!target.contextual()) continue;
            var context = new OwnedComponentSite(owner.className(), target.contextClassName(), target.contextFieldPath());
            byContext.computeIfAbsent(context, ignored -> new ArrayList<>()).add(site);
        }
        List<ConfigurationNode> result = new ArrayList<>();
        for (List<ConfigurationReferenceInventory.BindingSite> sites : byContext.values()) {
            var target = sites.getFirst().binding().target();
            String key = contextKey(project, target);
            List<ConfigurationNode> children = new ArrayList<>();
            children.add(producedBy(owner, path, key + "/", null, sites, registry,
                    target, fit, draft, List.of()));
            children.add(candidateEvidence(owner, path, key + "/", sites, registry,
                    target));
            result.add(node(owner, path, key,
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "When produced by " + contextName(target), PRIMARY, target, children));
        }
        List<ConfigurationNode> available = new ArrayList<>();
        if (draft != null && owner.ownedClass() && !OwnedClassSemantics.isOwnerReferenceField(owner, field)) {
            for (var owned : MembershipPattern.ownedBy(owner, project)) {
                var site = new OwnedComponentSite(owner.className(), owned.ownerClass(), owned.fieldName());
                if (byContext.containsKey(site)) continue;
                var target = new SourceBindingTarget(BindingScope.FIELD_VALUE, owner.className(), path,
                        SourceBindingSlot.PRIMARY_FIELD_VALUE, owner.declarationId(),
                        owned.ownerClass(), owned.fieldName(), project.findClass(owned.ownerClass()).declarationId());
                String siteName = contextName(target);
                var override = OwnedFieldSources.override(OwnedFieldSources.ownershipField(project, site), path);
                String key = contextKey(project, target);
                var advanced = advancedSource(owner, path, key + "/", target, null, registry, draft, List.of());
                List<ConfigurationNode> children = advanced == null ? List.of() : List.of(advanced);
                if (override != null) {
                    result.add(node(owner, path, key, ConfigurationNode.Kind.STORED_CONFIGURATION,
                            "When produced by " + siteName, PRIMARY, target, children));
                } else {
                    available.add(node(owner, path, key, ConfigurationNode.Kind.AVAILABLE_SLOT,
                            "+ Configure override for " + siteName, PRIMARY, target, children));
                }
            }
        }
        return new ContextualSources(result, available);
    }

    private static String contextName(SourceBindingTarget target) {
        return target.contextClassName() + "." + target.contextFieldPath();
    }

    private static String contextKey(GeneratedProjectModel project, SourceBindingTarget target) {
        var context = project.resolveClass(target.contextClassDeclarationId(), target.contextClassName());
        String contextId = context == null ? target.contextClassDeclarationId() : context.declarationId();
        if (contextId.isBlank()) contextId = target.contextClassName();
        return "context:" + contextId + ":" + target.contextFieldPath();
    }

    private static ConfigurationNode producedBy(GeneratedClassModel owner, String path,
            String keyPrefix,
            GeneratedFieldModel field,
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            DatasourceRegistry registry, SourceBindingTarget sourceContext, SlotFit fit,
            SourceDraft draft, List<ConfigurationNode> availableSites) {
        List<ConfigurationNode> children = new ArrayList<>();
        List<ConfigurationReferenceInventory.BindingSite> primary = slot(
                bindings, SourceBindingSlot.PRIMARY_FIELD_VALUE);
        String modelProducer = field == null ? "" : modelProducer(owner, field);
        var primarySite = primary.isEmpty() ? null : primary.getFirst();
        if (primarySite == null) {
            String caption = !modelProducer.isBlank()
                    ? "Primary: " + modelProducer
                    : field != null
                    && field.mapping().sourceType() == FieldSourceType.MANUAL
                    ? "Primary: manual values" : "+ Add primary source";
            ConfigurationNode.Kind kind = !modelProducer.isBlank() || field != null
                    && field.mapping().sourceType() == FieldSourceType.MANUAL
                    ? ConfigurationNode.Kind.STORED_CONFIGURATION
                    : ConfigurationNode.Kind.AVAILABLE_SLOT;
            children.add(node(owner, path, keyPrefix + "primary", kind, caption,
                    PRIMARY, sourceContext, List.of()));
        } else {
            children.add(bindingNode(owner, path, primarySite, registry, PRIMARY, "Primary: ", draft != null));
        }

        if (modelProducer.isBlank()) {
            List<ConfigurationReferenceInventory.BindingSite> fallback = slot(
                    bindings, SourceBindingSlot.FALLBACK_FIELD_VALUE);
            if (!fallback.isEmpty() || fit.fallback()) children.add(fallback.isEmpty()
                    ? node(owner, path, keyPrefix + "fallback",
                            ConfigurationNode.Kind.AVAILABLE_SLOT,
                            "+ Add fallback source", FALLBACK, sourceContext, List.of())
                    : bindingNode(owner, path, fallback.getFirst(), registry, FALLBACK,
                            "Fallback: "));

            List<ConfigurationReferenceInventory.BindingSite> category = slot(
                    bindings, SourceBindingSlot.CATEGORY_EVIDENCE);
            if (!category.isEmpty() || fit.category()) children.add(category.isEmpty()
                    ? node(owner, path, keyPrefix + "category",
                            ConfigurationNode.Kind.AVAILABLE_SLOT,
                            "+ Add category relation", CATEGORY, sourceContext, List.of())
                    : bindingNode(owner, path, category.getFirst(), registry, CATEGORY,
                            "Category relation: "));
        }
        var advanced = advancedSource(owner, path, keyPrefix, sourceContext, primarySite, registry, draft,
                availableSites);
        if (advanced != null) children.add(advanced);
        return node(owner, path, keyPrefix + "produced-by",
                ConfigurationNode.Kind.STORED_CONFIGURATION, "Produced by", PRIMARY,
                sourceContext, children);
    }

    private static ConfigurationNode advancedSource(GeneratedClassModel owner, String path, String keyPrefix,
            SourceBindingTarget context, ConfigurationReferenceInventory.BindingSite primary,
            DatasourceRegistry registry, SourceDraft draft, List<ConfigurationNode> sites) {
        if (draft == null) return null;
        SourceRecipe recipe = draft.edits(context) ? draft.recipe()
                : primary == null ? null : primary.binding().recipe();
        List<ConfigurationElement> settings = List.of();
        if (recipe != null) {
            try {
                var operation = registry.require(recipe.providerId(), recipe.operationId(), DatasourceOperation.class);
                if (operation.hasAdvancedSettings()) {
                    settings = operation.configurationElements(new SourceBinding(context, recipe)).stream()
                            .filter(ConfigurationElement::advanced).toList();
                }
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                // The primary source and validation already report invalid configuration.
            }
        }
        if (settings.isEmpty() && sites.isEmpty()) return null;
        String key = keyPrefix + "advanced-source";
        List<ConfigurationNode> children = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (var setting : settings) {
            summaries.add(setting.advancedSummary());
            children.add(node(owner, path, key + "/" + setting.key(), ConfigurationNode.Kind.STORED_CONFIGURATION,
                    setting.caption(), ADVANCED, context, List.of()));
        }
        if (!sites.isEmpty()) {
            summaries.add(sites.size() == 1 ? "Override available for 1 production site"
                    : "Overrides available for " + sites.size() + " production sites");
        }
        children.addAll(sites);
        return node(owner, path, key, ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Advanced settings — " + String.join(", ", summaries), ADVANCED,
                settings.isEmpty() ? null : context, children);
    }

    /** Which unconfigured slots this field's type can use. A configured binding is
     *  always shown, compatible or not; validation names the mismatch. */
    private record SlotFit(boolean fallback, boolean category) { }

    /** A fallback is one of the non-Wikidata operations a legacy source type names
     *  (the primary already reads Wikidata); it fits when one of them can fill the
     *  field, by the same output-schema check that refuses an incompatible binding. */
    private static boolean fallbackFits(GeneratedFieldModel field, DatasourceRegistry registry) {
        boolean collection = field.cardinality().isCollection();
        return FieldSourceBindings.sourceTypeCoverage().stream()
                .filter(FieldSourceBindings.LegacySourceCoverage::registeredOperation)
                .filter(value -> !domain.datasource.wikidata.WikidataDatasourceProvider.ID
                        .equals(value.providerId()))
                .flatMap(value -> registry.provider(value.providerId()).stream()
                        .flatMap(provider -> provider.operations().stream())
                        .filter(operation -> operation.id().equals(value.operationId())))
                .anyMatch(operation -> operation.outputSchema()
                        .incompatibility(field.type(), collection).isBlank());
    }

    private static String modelProducer(GeneratedClassModel owner, GeneratedFieldModel field) {
        FieldProductionKind kind = field.mapping().productionKind();
        if (kind != null && kind.producedWithinModel()) return kind.toString();
        AggregateClassSource aggregate = owner.aggregateSource();
        if (aggregate == null) return "";
        if (field.name().equals(aggregate.membersField())) return "aggregate members";
        return aggregate.keys().stream().anyMatch(key -> field.name().equals(key.targetField()))
                ? "aggregate key" : "";
    }

    private static ConfigurationNode candidateEvidence(GeneratedClassModel owner, String path,
            String keyPrefix,
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            DatasourceRegistry registry, SourceBindingTarget sourceContext) {
        List<ConfigurationNode> children = new ArrayList<>();
        for (ConfigurationReferenceInventory.BindingSite site
                : slot(bindings, SourceBindingSlot.CANDIDATE_EVIDENCE)) {
            children.add(bindingNode(owner, path, site, registry, EVIDENCE, ""));
        }
        children.add(node(owner, path, keyPrefix + "evidence:add",
                ConfigurationNode.Kind.AVAILABLE_SLOT,
                "+ Add evidence recipe", EVIDENCE, sourceContext, List.of()));
        return node(owner, path, keyPrefix + "evidence",
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Candidate evidence", EVIDENCE, sourceContext, children);
    }

    private static ConfigurationNode bindingNode(GeneratedClassModel owner, String path,
            ConfigurationReferenceInventory.BindingSite site, DatasourceRegistry registry,
            String detail, String prefix) {
        return bindingNode(owner, path, site, registry, detail, prefix, false);
    }

    private static ConfigurationNode bindingNode(GeneratedClassModel owner, String path,
            ConfigurationReferenceInventory.BindingSite site, DatasourceRegistry registry,
            String detail, String prefix, boolean separateAdvanced) {
        String key = "binding:" + site.bindingSite().fieldPath();
        DatasourceOperation operation;
        DatasourceProvider provider;
        try {
            operation = site.binding().resolve(registry);
            provider = registry.provider(site.binding().recipe().providerId()).orElseThrow();
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            String recipe = site.binding().recipe().providerId() + " › "
                    + site.binding().recipe().operationId();
            return new ConfigurationNode(id(owner, path, key),
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    prefix + recipe + " — unavailable", detail, null, site, List.of());
        }
        List<ConfigurationNode> children;
        String summary;
        try {
            children = operation.configurationElements(site.binding())
                    .stream().filter(element -> !separateAdvanced || !element.advanced()).map(element -> elementNode(
                            owner, path, key, detail, site, element)).toList();
            summary = operation.summary(site.binding());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            children = List.of();
            summary = operation.displayName() + " — invalid configuration";
        }
        return new ConfigurationNode(id(owner, path, key),
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                prefix + provider.displayName() + " — " + summary,
                detail, null, site, children);
    }

    private static ConfigurationNode elementNode(GeneratedClassModel owner, String path,
            String parentKey, String detail,
            ConfigurationReferenceInventory.BindingSite site,
            ConfigurationElement element) {
        String key = parentKey + "/" + element.key();
        return new ConfigurationNode(id(owner, path, key),
                ConfigurationNode.Kind.STORED_CONFIGURATION,
                element.caption(), detail, null, site,
                element.children().stream().map(child -> elementNode(
                        owner, path, key, detail, site, child)).toList());
    }

    private static List<ConfigurationReferenceInventory.Reference> referencesFrom(
            ConfigurationReferenceInventory inventory,
            ConfigurationReferenceInventory.Site fieldSite,
            List<ConfigurationReferenceInventory.BindingSite> bindings) {
        List<ConfigurationReferenceInventory.Reference> result =
                new ArrayList<>(inventory.from(fieldSite));
        for (ConfigurationReferenceInventory.BindingSite binding : bindings) {
            result.addAll(inventory.from(binding.bindingSite()));
        }
        return List.copyOf(result);
    }

    private static List<ConfigurationReferenceInventory.BindingSite> slot(
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            SourceBindingSlot slot) {
        return bindings.stream().filter(site -> site.binding().target().slot() == slot).toList();
    }

    private static ConfigurationNode node(GeneratedClassModel owner, String path, String key,
            ConfigurationNode.Kind kind, String caption, String detail,
            List<ConfigurationNode> children) {
        return new ConfigurationNode(id(owner, path, key), kind, caption, detail, children);
    }

    private static ConfigurationNode node(GeneratedClassModel owner, String path, String key,
            ConfigurationNode.Kind kind, String caption, String detail,
            SourceBindingTarget sourceContext, List<ConfigurationNode> children) {
        return new ConfigurationNode(id(owner, path, key), kind, caption, detail,
                null, null, sourceContext, children);
    }

    private static ConfigurationNode.Id id(
            GeneratedClassModel owner, String path, String key) {
        return new ConfigurationNode.Id(owner.declarationId(), path, key);
    }

    private static String pathOf(List<GeneratedFieldModel> fields,
            GeneratedFieldModel target, String prefix) {
        for (GeneratedFieldModel candidate : fields) {
            String path = prefix.isBlank() ? candidate.name() : prefix + "." + candidate.name();
            if (candidate == target) return path;
            String nested = pathOf(candidate.fields(), target, path);
            if (!nested.isBlank()) return nested;
        }
        return "";
    }
}
