package datasource.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Process-bound clients made available to datasource execution.
 *
 * <p>A key includes both provider id and Java service type: two providers may use the
 * same client implementation while pointing at different endpoints. The catalogue is
 * immutable once built, so one run cannot observe another run's clients.
 */
public final class SourceRuntimeServices {
    private final Map<Key, Object> services;

    private SourceRuntimeServices(Map<Key, Object> services) {
        this.services = Map.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }
    public static SourceRuntimeServices empty() { return builder().build(); }

    public <T> Optional<T> find(String providerId, Class<T> type) {
        if (providerId == null || type == null) return Optional.empty();
        return Optional.ofNullable(services.get(new Key(providerId, type))).map(type::cast);
    }

    public <T> T require(String providerId, Class<T> type) {
        return find(providerId, type).orElseThrow(() -> new IllegalStateException(
                "No runtime service " + (type == null ? "<unknown>" : type.getSimpleName())
                        + " for datasource provider '" + providerId + "'"));
    }

    public static final class Builder {
        private final Map<Key, Object> services = new LinkedHashMap<>();

        public <T> Builder put(String providerId, Class<T> type, T service) {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("Runtime service provider id is required");
            }
            if (type == null) throw new IllegalArgumentException("Runtime service type is required");
            if (service == null) return this;
            if (!type.isInstance(service)) {
                throw new IllegalArgumentException(service.getClass().getName()
                        + " is not a " + type.getName());
            }
            Key key = new Key(providerId, type);
            if (services.putIfAbsent(key, service) != null) {
                throw new IllegalArgumentException("Duplicate runtime service "
                        + type.getSimpleName() + " for datasource provider '"
                        + providerId + "'");
            }
            return this;
        }

        public SourceRuntimeServices build() {
            return new SourceRuntimeServices(services);
        }
    }

    private record Key(String providerId, Class<?> type) { }
}
