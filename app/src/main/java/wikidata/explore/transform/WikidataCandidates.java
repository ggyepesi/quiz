package wikidata.explore.transform;

import canonical.Candidate;
import canonical.KeyComponent;
import canonical.StableForm;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.StableIdentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wikidata's normalized output, seen as candidates.
 *
 * <p>A narrow adapter and nothing more: acquisition, parsing and normalization are
 * untouched, and this reads what they already produced. It exists so the reduce phase can
 * be provider-neutral without the provider learning what identity means — the map phase
 * emits candidates, and what becomes of them is the model's business.
 *
 * <p>It is also where the boundary is checked in practice. If something here needed the
 * model's key to decide what to emit, the boundary would be in the wrong place.
 */
public final class WikidataCandidates {

    /** The namespace that qualifies a Wikidata identifier, so two datasources cannot
     *  collide by accident — and so a provider that has resolved a correspondence can
     *  deliberately emit THIS one. */
    public static final String NAMESPACE = "wikidata";

    private WikidataCandidates() { }

    /** The equality every existing grouping path already uses, bound as a capability. */
    public static StableForm stableForm() {
        return StableIdentity::of;
    }

    public static List<Candidate> of(Collection<WikidataDynamicObject> objects) {
        List<Candidate> candidates = new ArrayList<>();
        for (WikidataDynamicObject object : objects == null ? List.<WikidataDynamicObject>of() : objects) {
            if (object != null) candidates.add(new ObjectCandidate(object));
        }
        return candidates;
    }

    public static Candidate of(WikidataDynamicObject object) {
        return object == null ? null : new ObjectCandidate(object);
    }

    private record ObjectCandidate(WikidataDynamicObject object) implements Candidate {

        @Override public String className() {
            return object.typeKey();
        }

        @Override public Object value(String fieldPath) {
            return fieldPath == null || fieldPath.isBlank() ? null : object.get(fieldPath);
        }

        @Override public String structuralIdentity(KeyComponent.Kind kind) {
            return switch (kind) {
                // The entity's own id, qualified. Bare "Q42" would be a claim that no
                // other datasource could ever mean something else by it.
                case SOURCE_IDENTITY -> object.qid().isBlank()
                        ? "" : NAMESPACE + ":" + object.qid();

                // A part's identity is composed when it is produced — owner plus the
                // site that made it — and stored as the object's identifier. Reading it
                // back is right; recomputing it here would be the second discovery path.
                case OWNER_SITE_IDENTITY -> object.isPart() ? object.getIdentifier() : "";

                // NOT SUPPLIED YET, and deliberately blank rather than approximated.
                // A statement's occurrence identity is its GUID, which acquisition does
                // not store; the triple would be the obvious substitute and is exactly
                // wrong, since the same triple legitimately occurs more than once —
                // that is the 179-holdings-over-173-pairs case. A blank means the
                // missing-key policy reports it, which is what should happen until
                // extraction carries the GUID.
                case SOURCE_OCCURRENCE -> "";

                case FIELD -> "";
            };
        }
    }
}
