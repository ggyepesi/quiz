package domain.datasource.configured;

import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;

import java.util.List;

/** A datasource provider whose bounded JSON REST operations come from configuration. */
public final class ConfiguredRestDatasourceProvider implements DatasourceProvider {
    private final ConfiguredDatasourceDefinition definition;
    private final List<DatasourceOperation> operations;

    public ConfiguredRestDatasourceProvider(ConfiguredDatasourceDefinition definition) {
        this(definition, new ConfiguredRestClient());
    }

    ConfiguredRestDatasourceProvider(
            ConfiguredDatasourceDefinition definition, ConfiguredRestClient client) {
        this.definition = java.util.Objects.requireNonNull(definition);
        this.operations = definition.operations().stream()
                .map(operation -> (DatasourceOperation) new ConfiguredRestOperation(
                        definition.id(), definition.displayName(), operation, client))
                .toList();
    }

    @Override public String id() { return definition.id(); }
    @Override public String displayName() { return definition.displayName(); }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }
}
