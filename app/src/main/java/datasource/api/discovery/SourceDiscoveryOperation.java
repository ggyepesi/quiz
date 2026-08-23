package datasource.api.discovery;

import datasource.api.DatasourceOperation;
import work.Query;

/** Datasource operation that discovers selectable source structures from a sample. */
public interface SourceDiscoveryOperation extends DatasourceOperation {
    Query<SourceDiscoveryResult> discover(SourceDiscoveryRequest request);
}
