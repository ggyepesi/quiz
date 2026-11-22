package domain.datasource.configured;

import domain.datasource.api.BindingScope;
import domain.datasource.api.ParameterDescriptor;
import domain.datasource.api.SourceReferenceSchema;
import domain.datasource.api.SourceValueKind;
import domain.datasource.api.SourceValueSchema;
import domain.datasource.api.discovery.DiscoveredRecordField;
import domain.datasource.api.discovery.RelatedRecordDiscoveryOperation;
import domain.datasource.api.discovery.RelatedRecordDiscoveryRequest;
import work.Query;

import java.util.List;

/** One configuration-defined bounded search-to-detail operation. */
final class ConfiguredRestOperation implements RelatedRecordDiscoveryOperation {
    private final String providerId;
    private final String providerName;
    private final ConfiguredDatasourceDefinition.RestOperation definition;
    private final ConfiguredRestClient client;
    private final List<DiscoveredRecordField> fields;

    ConfiguredRestOperation(String providerId, String providerName,
            ConfiguredDatasourceDefinition.RestOperation definition,
            ConfiguredRestClient client) {
        this.providerId = providerId;
        this.providerName = providerName;
        this.definition = java.util.Objects.requireNonNull(definition);
        this.client = java.util.Objects.requireNonNull(client);
        this.fields = definition.record().fields().stream()
                .map(field -> new DiscoveredRecordField(field.name(), field.label(),
                        field.kind(), field.collection())).toList();
    }

    @Override public String id() { return definition.id(); }
    @Override public String displayName() { return definition.displayName(); }
    @Override public BindingScope scope() { return BindingScope.DOCUMENT_EVIDENCE; }
    @Override public List<ParameterDescriptor> parameters() { return List.of(); }
    @Override public SourceValueSchema outputSchema() {
        return new SourceValueSchema(SourceValueKind.DOCUMENT, true, "");
    }
    @Override public List<SourceReferenceSchema> inputReferences() {
        return List.of(new SourceReferenceSchema(definition.inputNamespace(),
                SourceReferenceSchema.Kind.ENTITY, true));
    }
    @Override public String recordTypeName() { return definition.record().typeName(); }
    @Override public List<DiscoveredRecordField> recordFields() { return fields; }
    @Override public int maximumSeeds() { return definition.maximumSeeds(); }
    @Override public int maximumRecordsPerSeed() {
        return definition.maximumRecordsPerSeed();
    }

    @Override public Query<domain.datasource.api.discovery.RelatedRecordDiscoveryResult> discover(
            RelatedRecordDiscoveryRequest request) {
        RelatedRecordDiscoveryRequest safe = request == null
                ? new RelatedRecordDiscoveryRequest(List.of()) : request;
        if (safe.seeds().size() > maximumSeeds()) {
            throw new IllegalArgumentException(displayName() + " accepts at most "
                    + maximumSeeds() + " selected seed(s), not " + safe.seeds().size());
        }
        for (var seed : safe.seeds()) {
            if (!definition.inputNamespace().equals(seed.entity().namespace())) {
                throw new IllegalArgumentException(displayName() + " needs "
                        + definition.inputNamespace() + " seeds, not "
                        + seed.entity().namespace());
            }
        }
        return new ConfiguredRestDiscoveryQuery(
                providerId, providerName, definition, client, safe.seeds());
    }
}
