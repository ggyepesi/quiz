package datasource.api;

import java.util.List;
import java.util.Optional;

/** Provider plugin boundary shared by ModelBuilder, Transform and generation. */
public interface DatasourceProvider {
    String id();
    String displayName();
    List<? extends DatasourceOperation> operations();

    default Optional<DatasourceOperation> operation(String operationId) {
        return operations().stream()
                .filter(operation -> operation.id().equals(operationId))
                .map(DatasourceOperation.class::cast)
                .findFirst();
    }
}
