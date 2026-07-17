package wikidata.explore.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * A domain's Transform: an ordered set of constructs producing view classes from
 * the loaded pool. Persisted as {@code <domain>.transform.json}.
 */
public class TransformConfig {

    public List<QualifierLoadConfig> qualifierLoads = new ArrayList<>();
    public List<InvertConstruct> inverts = new ArrayList<>();
    public List<ReifyConstruct> reifies = new ArrayList<>();

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return qualifierLoads.isEmpty() && inverts.isEmpty() && reifies.isEmpty();
    }
}
