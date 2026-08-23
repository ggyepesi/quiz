package datasource.wikipedia;

import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;

import java.util.List;

/** Wikipedia capabilities exposed without application-side provider branching. */
public final class WikipediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "wikipedia";

    private final List<DatasourceOperation> operations =
            List.of(new WikipediaCategoryDiscoveryOperation());

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Wikipedia"; }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }
}
