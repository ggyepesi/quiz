package datasource.api.acquisition;

import datasource.api.DatasourceOperation;
import work.Query;

/** An offering that can actually acquire the output its descriptor advertises. */
public interface SourceAcquisitionOperation<R> extends DatasourceOperation {
    Query<R> acquire(SourceAcquisitionRequest request);
}
