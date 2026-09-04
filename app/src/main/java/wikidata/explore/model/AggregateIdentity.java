package wikidata.explore.model;

import datasource.EntityRef;
import java.util.ArrayList;
import java.util.List;

/** The one collision-safe identity rule for a configured aggregate key tuple. */
public final class AggregateIdentity {
    private AggregateIdentity() {}

    public static String identifier(String className, List<String> keyValues) {
        List<String> framed = new ArrayList<>();
        framed.add(clean(className));
        if (keyValues != null) framed.addAll(keyValues.stream().map(AggregateIdentity::clean).toList());
        return new EntityRef("domain.aggregate", canonical.StableKey.encode(framed))
                .qualifiedId();
    }

    private static String clean(String value) { return value == null ? "" : value; }
}
