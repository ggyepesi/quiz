package datasource.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** One registry through which applications discover datasource capabilities. */
public final class DatasourceRegistry {
    private final Map<String, DatasourceProvider> providers = new LinkedHashMap<>();

    public DatasourceRegistry(Collection<? extends DatasourceProvider> providers) {
        if (providers != null) {
            for (DatasourceProvider provider : providers) {
                if (provider == null) continue;
                DatasourceProvider previous = this.providers.put(provider.id(), provider);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Duplicate datasource provider: " + provider.id());
                }
            }
        }
    }

    public Collection<DatasourceProvider> providers() {
        return java.util.List.copyOf(providers.values());
    }

    public Optional<DatasourceProvider> provider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public <T extends DatasourceOperation> Optional<T> operation(
            String providerId, String operationId, Class<T> type) {
        if (type == null) return Optional.empty();
        return provider(providerId).flatMap(provider -> provider.operation(operationId))
                .filter(type::isInstance).map(type::cast);
    }

    /**
     * The operation, or a failure naming what was asked for.
     *
     * <p>An absent operation is a composition error, not a runtime condition: the
     * registry that a caller was handed either declares it or the application was
     * assembled wrong. Returning empty and letting the caller quietly do nothing is the
     * shape of failure this codebase keeps paying for — a rule that reported "not run",
     * a remap that reported success. Duplicate ids already fail here; a missing one
     * should too.
     */
    public <T extends DatasourceOperation> T require(
            String providerId, String operationId, Class<T> type) {
        return operation(providerId, operationId, type).orElseThrow(
                () -> new IllegalStateException(
                        "No " + (type == null ? "operation" : type.getSimpleName())
                                + " '" + operationId + "' on datasource provider '"
                                + providerId + "'. Registered: " + providers.keySet()));
    }
}
