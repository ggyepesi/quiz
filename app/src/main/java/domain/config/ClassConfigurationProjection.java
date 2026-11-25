package domain.config;

import java.util.ArrayList;
import java.util.List;

import static domain.config.ConfigurationNode.Kind.AVAILABLE_SLOT;
import static domain.config.ConfigurationNode.Kind.INVENTORY_SUMMARY;
import static domain.config.ConfigurationNode.Kind.STORED_CONFIGURATION;

/** The local configuration outline for class editors with a form-based producer. */
public final class ClassConfigurationProjection {
    public static final String DEFINITION = "definition";
    public static final String PRODUCTION = "production";
    public static final String IDENTITY = "identity";
    public static final String DISPLAY_NAME = "display-name";
    public static final String REPRESENTATION = "representation";
    public static final String REFINE = "refine";
    public static final String GRAPH_EXPANSION = "graph-expansion";
    public static final String RUNTIME_RECIPE = "runtime-recipe";
    public static final String SOURCE_LINKS = "source-links";
    public static final String TRANSFORMATION = "transformation";

    private ClassConfigurationProjection() { }

    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel clazz, ClassKind editorKind) {
        return project(project, clazz, editorKind,
                clazz == null ? null : clazz.transformationSource());
    }

    public static ConfigurationNode project(GeneratedProjectModel project,
            GeneratedClassModel clazz, ClassKind editorKind, TransformationClassSource draft) {
        List<ConfigurationNode> children = new ArrayList<>();
        children.add(production(project, clazz, editorKind, draft));
        children.add(section(clazz, IDENTITY, "Identity", STORED_CONFIGURATION));
        children.add(section(clazz, DISPLAY_NAME, "Display name", STORED_CONFIGURATION));
        if (editorKind == ClassKind.SOURCE) {
            boolean represented = representationConfigured(project, clazz);
            boolean linked = clazz != null
                    && ClassSourceBindings.articleCorrespondence(clazz) != null;
            boolean canConfigure = clazz == null || !clazz.isImported();
            if (represented || canConfigure) {
                children.add(section(clazz, REPRESENTATION, represented
                        ? "Subclass and representation" : "+ Add subclass condition or representation",
                        represented ? STORED_CONFIGURATION : AVAILABLE_SLOT));
            }
            if (linked || canConfigure) {
                children.add(section(clazz, SOURCE_LINKS, linked
                        ? "Source links: English Wikipedia article" : "+ Add source link",
                        linked ? STORED_CONFIGURATION : AVAILABLE_SLOT));
            }
        } else if (editorKind == ClassKind.STATEMENT) {
            children.add(section(clazz, GRAPH_EXPANSION, "Graph expansion", STORED_CONFIGURATION));
            children.add(section(clazz, RUNTIME_RECIPE, "Derived runtime recipe", INVENTORY_SUMMARY));
        }
        if (project != null && clazz != null) {
            var inventory = ConfigurationReferenceInventory.of(project);
            var owner = ConfigurationReferenceInventory.classSite(clazz);
            ConfigurationReferenceProjection.uses(owner, inventory.from(owner))
                    .ifPresent(children::add);
            ConfigurationReferenceProjection.usedBy(owner, inventory.referencesToClass(clazz))
                    .ifPresent(children::add);
        }
        String caption = clazz == null ? editorKind.label() : clazz.className();
        return new ConfigurationNode(id(clazz, "class"), STORED_CONFIGURATION,
                caption, DEFINITION, children);
    }

    /** A subclass condition, or a representation rule whose role is this class. */
    private static boolean representationConfigured(
            GeneratedProjectModel project, GeneratedClassModel clazz) {
        if (clazz == null) return false;
        if (clazz.hasSubclassCondition()) return true;
        return project != null && project.entityRepresentationRules().stream()
                .anyMatch(rule -> rule != null && project.resolveClass(
                        rule.roleClassId(), rule.roleClassName()) == clazz);
    }

    private static ConfigurationNode production(GeneratedProjectModel project,
            GeneratedClassModel clazz, ClassKind kind, TransformationClassSource transformation) {
        return switch (kind) {
            case SOURCE -> {
                if (clazz != null && clazz.materializedByTransformation()) {
                    List<ConfigurationNode> steps = new ArrayList<>();
                    if (transformation != null) for (TransformationStep step : transformation.steps()) {
                        int index = steps.size() + 1;
                        String summary = !step.configured() ? "Incomplete filter"
                                : java.util.Arrays.stream(step.fieldPath().split("\\.", -1))
                                        .map(objectview.field.ViewableContractFieldSet::label)
                                        .collect(java.util.stream.Collectors.joining("."))
                                        + " " + step.operator()
                                        + (step.operator().isUnary() ? "" : " " + step.value())
                                        + (step.operator().isBinary() ? " and " + step.value2() : "");
                        steps.add(new ConfigurationNode(id(clazz, TRANSFORMATION + "/" + index),
                                STORED_CONFIGURATION, index + ". " + summary,
                                TRANSFORMATION, List.of()));
                    }
                    yield new ConfigurationNode(id(clazz, TRANSFORMATION), STORED_CONFIGURATION,
                            "Population: transformation", TRANSFORMATION, steps);
                }
                // The membership pattern is the one answer to how a class gets members;
                // a catalogue recipe is only one of them. Subclass conditions, seeds and
                // classes filled by being referenced are configured populations too.
                // A declared non-Wikidata population (a Wikipedia category, a REST search)
                // IS the class's population, as PopulationSourceBindings decides first.
                // MembershipPattern does not classify it yet (#575), so it is named here.
                var declared = clazz == null ? null : clazz.declaredPopulationSource();
                if (declared != null && !domain.datasource.wikidata.WikidataDatasourceProvider.ID
                        .equals(declared.providerId())) {
                    yield section(clazz, PRODUCTION, "Population: " + operationName(declared),
                            STORED_CONFIGURATION);
                }
                if (clazz != null && project != null) {
                    MembershipPattern pattern = MembershipPattern.of(clazz, project);
                    yield pattern == MembershipPattern.UNCONFIGURED
                            ? wikidataPopulation(clazz, "+ Configure population source",
                                    AVAILABLE_SLOT)
                            : wikidataPopulation(clazz, "Population: " + pattern.label(),
                                    STORED_CONFIGURATION);
                }
                boolean configured = clazz != null && clazz.populationSource() != null;
                yield wikidataPopulation(clazz, configured
                        ? "Population source" : "+ Configure population source",
                        configured ? STORED_CONFIGURATION : AVAILABLE_SLOT);
            }
            case STATEMENT -> {
                var source = clazz == null ? null : clazz.statementSource();
                boolean configured = source != null && !source.propertyPid().isBlank();
                yield section(clazz, PRODUCTION, configured
                        ? "Statement triple: " + source.describeTriple() : "+ Configure statement triple",
                        configured ? STORED_CONFIGURATION : AVAILABLE_SLOT);
            }
            case OWNED -> section(clazz, PRODUCTION, "Ownership sites", INVENTORY_SUMMARY);
            case AGGREGATE -> {
                var source = clazz == null ? null : clazz.aggregateSource();
                boolean configured = source != null && !source.sourceClassName().isBlank();
                yield section(clazz, PRODUCTION, configured
                        ? "Aggregate from " + source.sourceClassName() : "+ Configure aggregation",
                        configured ? STORED_CONFIGURATION : AVAILABLE_SLOT);
            }
            case GRAPH -> throw new IllegalArgumentException("Use the graph configuration projection");
        };
    }

    private static ConfigurationNode wikidataPopulation(GeneratedClassModel clazz, String caption,
            ConfigurationNode.Kind kind) {
        return new ConfigurationNode(id(clazz, PRODUCTION), kind, caption, PRODUCTION,
                List.of(section(clazz, REFINE, "Population limits and filters", STORED_CONFIGURATION)));
    }

    /** A step's identity carries its order. The caption is presentation only. */
    public static int transformationStepIndex(ConfigurationNode node) {
        String prefix = TRANSFORMATION + "/";
        return node != null && node.id().key().startsWith(prefix)
                ? Integer.parseInt(node.id().key().substring(prefix.length())) - 1 : -1;
    }

    private static String operationName(domain.datasource.api.SourceRecipe recipe) {
        try {
            var registry = domain.datasource.Datasources.standard();
            var provider = registry.provider(recipe.providerId()).orElseThrow();
            var operation = provider.operations().stream()
                    .filter(value -> value.id().equals(recipe.operationId()))
                    .findFirst().orElseThrow();
            return provider.displayName() + " — " + operation.displayName();
        } catch (RuntimeException unavailable) {
            return recipe.providerId() + " › " + recipe.operationId();
        }
    }

    private static ConfigurationNode section(GeneratedClassModel clazz, String key,
            String caption, ConfigurationNode.Kind kind) {
        return new ConfigurationNode(id(clazz, key), kind, caption, key, List.of());
    }

    private static ConfigurationNode.Id id(GeneratedClassModel clazz, String key) {
        return new ConfigurationNode.Id(clazz == null ? "" : clazz.declarationId(), "", key);
    }
}
