package quiz.transform.ui;

import objectview.Viewable;
import quiz.enrichment.ReferenceResolver;
import quiz.transform.DynamicViewable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a QID against the loaded domain, creating the instance when the pool has none.
 *
 * <p>Linking to what is already pooled is what keeps identity single: a location the
 * domain was generated with must stay ONE object, so a curated reference to it points at
 * that object rather than a second copy carrying the same QID. Creating is for the rest —
 * a narrative location no film in the domain happened to be generated with, which is
 * exactly the case a required-but-missing field runs into.
 *
 * <p>A created instance carries its label from the start. One that joined the pool named
 * by its QID would immediately become the kind of unnamed reference curation exists to
 * repair.
 */
public final class DomainReferenceResolver implements ReferenceResolver {

    private final Map<String, Viewable> byQid = new LinkedHashMap<>();
    // Instances created during this session, so the caller can pool them once the
    // decision is accepted rather than the moment a proposal is drafted.
    private final Map<String, Viewable> created = new LinkedHashMap<>();

    public DomainReferenceResolver(Collection<? extends Viewable> pool) {
        if (pool != null) {
            for (Viewable instance : pool) {
                if (instance == null) continue;
                String id = instance.getIdentifier();
                if (id != null && !id.isBlank()) {
                    byQid.putIfAbsent(id, instance);
                }
            }
        }
    }

    @Override
    public Resolved resolve(String qid, String label, String targetType) {
        if (qid == null || qid.isBlank()) {
            return null;
        }
        Viewable existing = byQid.get(qid);
        if (existing != null) {
            return new Resolved(existing, false);
        }
        DynamicViewable fresh = new DynamicViewable(
                qid, label == null || label.isBlank() ? qid : label);
        if (targetType != null && !targetType.isBlank()) {
            fresh.type(targetType);
        }
        byQid.put(qid, fresh);
        created.put(qid, fresh);
        return new Resolved(fresh, true);
    }

    /** The instances this resolver had to invent, in the order it invented them —
     *  what an accepted decision adds to the domain. */
    public Map<String, Viewable> created() {
        return Map.copyOf(created);
    }
}
