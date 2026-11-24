package domain.config;

import domain.datasource.api.ConfigurationElement;
import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.BindingScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** The one model-layer configuration tree for a declared field. */
public final class FieldConfigurationProjection {

    public static final String DEFINITION = "field-definition";
    public static final String PRIMARY = "field-primary-source";
    public static final String FALLBACK = "field-fallback-source";
    public static final String CATEGORY = "field-category-source";
    public static final String EVIDENCE = "field-candidate-evidence";
    public static final String REFINE = "field-refine";
    public static final String REFERENCE = "field-reference";

    private FieldConfigurationProjection() { }

    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel owner, GeneratedFieldModel field,
            DatasourceRegistry registry) {
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
                inventory.bindingsStoredAt(fieldSite);
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
        children.add(producedBy(owner, path, "", field, bindings, registry,
                directContext, fit));
        children.add(candidateEvidence(owner, path, "", bindings, registry,
                directContext));
        children.addAll(contextualSources(owner, path, allBindings, registry, fit));
        referenceGroup(owner, path, "needs", "Needs", referencesFrom(inventory, fieldSite,
                allBindings), true).ifPresent(children::add);
        referenceGroup(owner, path, "used-by", "Used by", inventory.to(fieldSite), false)
                .ifPresent(children::add);
        children.add(node(owner, path, "refine", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Refine", REFINE, List.of()));
        return node(owner, path, "field", ConfigurationNode.Kind.STORED_CONFIGURATION,
                owner.className() + "." + path, DEFINITION, children);
    }

    private static List<ConfigurationNode> contextualSources(
            GeneratedClassModel owner, String path,
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            DatasourceRegistry registry, SlotFit fit) {
        Map<String, List<ConfigurationReferenceInventory.BindingSite>> byContext =
                new LinkedHashMap<>();
        for (ConfigurationReferenceInventory.BindingSite site : bindings) {
            var target = site.binding().target();
            if (!target.contextual()) continue;
            String key = target.contextClassDeclarationId() + "|"
                    + target.contextClassName() + "|" + target.contextFieldPath();
            byContext.computeIfAbsent(key, ignored -> new ArrayList<>()).add(site);
        }
        List<ConfigurationNode> result = new ArrayList<>();
        for (List<ConfigurationReferenceInventory.BindingSite> sites : byContext.values()) {
            var target = sites.getFirst().binding().target();
            String contextId = target.contextClassDeclarationId().isBlank()
                    ? target.contextClassName() : target.contextClassDeclarationId();
            String key = "context:" + contextId + ":" + target.contextFieldPath();
            List<ConfigurationNode> children = new ArrayList<>();
            children.add(producedBy(owner, path, key + "/", null, sites, registry,
                    target, fit));
            children.add(candidateEvidence(owner, path, key + "/", sites, registry,
                    target));
            result.add(node(owner, path, key,
                    ConfigurationNode.Kind.STORED_CONFIGURATION,
                    "When produced by " + target.contextClassName() + "."
                            + target.contextFieldPath(), PRIMARY, target, children));
        }
        return List.copyOf(result);
    }

    private static ConfigurationNode producedBy(GeneratedClassModel owner, String path,
            String keyPrefix,
            GeneratedFieldModel field,
            List<ConfigurationReferenceInventory.BindingSite> bindings,
            DatasourceRegistry registry, SourceBindingTarget sourceContext, SlotFit fit) {
        List<ConfigurationNode> children = new ArrayList<>();
        List<ConfigurationReferenceInventory.BindingSite> primary = slot(
                bindings, SourceBindingSlot.PRIMARY_FIELD_VALUE);
        String modelProducer = field == null ? "" : modelProducer(owner, field);
        if (primary.isEmpty()) {
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
            children.add(bindingNode(owner, path, primary.getFirst(), registry, PRIMARY,
                    "Primary: "));
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
        return node(owner, path, keyPrefix + "produced-by",
                ConfigurationNode.Kind.STORED_CONFIGURATION, "Produced by", PRIMARY,
                sourceContext, children);
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
        String key = "binding:" + site.bindingSite().name();
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
                    .stream().map(element -> elementNode(
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

    private static java.util.Optional<ConfigurationNode> referenceGroup(
            GeneratedClassModel owner, String path, String key, String caption,
            List<ConfigurationReferenceInventory.Reference> references, boolean targetCaption) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ConfigurationNode> children = new ArrayList<>();
        for (ConfigurationReferenceInventory.Reference reference : references) {
            if (reference.relation()
                    == ConfigurationReferenceInventory.Relation.SOURCE_BINDING_TARGET) continue;
            ConfigurationReferenceInventory.Site endpoint = targetCaption
                    ? reference.target() : reference.source();
            String unique = reference.relation() + "|" + endpoint + "|" + reference.role();
            if (!seen.add(unique)) continue;
            // A reference is a model fact, not a row position.  Its identity must
            // survive another reference being inserted before it so the shared
            // tree can restore selection and expansion after Apply.
            String childKey = key + ":" + unique;
            children.add(new ConfigurationNode(id(owner, path, childKey),
                    ConfigurationNode.Kind.REFERENCE,
                    reference.role() + ": " + endpoint.displayName(), REFERENCE,
                    endpoint, List.of()));
        }
        return children.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(node(owner, path, key,
                        ConfigurationNode.Kind.REFERENCE,
                        caption, REFERENCE, children));
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
