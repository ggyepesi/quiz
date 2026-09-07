package datasource.api;

import java.util.List;
import java.util.Optional;

/** Provider plugin boundary shared by ModelBuilder, Transform and generation. */
public interface DatasourceProvider {
    String id();
    String displayName();
    List<? extends DatasourceOperation> operations();

    /** Ordinary fields every instance backed by this provider declares. */
    default List<? extends DatasourceInstanceField> instanceFields() {
        return List.of();
    }

    default Optional<DatasourceOperation> operation(String operationId) {
        return operations().stream()
                .filter(operation -> operation.id().equals(operationId))
                .map(DatasourceOperation.class::cast)
                .findFirst();
    }
}
