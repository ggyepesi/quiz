package quiz.web.sources;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.facet.FacetGrouper;
import quiz.transform.ui.DomainModel;
import quiz.web.QuizableSource;
import quiz.web.QuizableStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A generic {@link QuizableSource} over a {@link DomainModel} — serves the domain's
 * instances of one type. The unified seam: the SAME abstraction that powers the
 * transform workbench serves the web, so any domain (a Wikidata snapshot, a
 * built-in Quizable domain, an in-memory transform result) is servable without a
 * bespoke source or a generated model. Loads lazily and shares one loaded domain
 * across its per-type sources.
 *
 * <p>Grouping is CONFIGURED, not hardcoded: pass the {@link Facet}s (from a saved
 * view / declared facets) and {@link #rootGroup()} groups the members by them —
 * the path to retiring the domain-specific wired groupings in the bespoke sources.
 */
public final class DomainModelSource implements QuizableSource {

    /** Loads the domain on first access (may be slow / throw). */
    public interface Loader {
        DomainModel get() throws Exception;
    }

    private final String type;
    private final Loader loader;
    private final List<Facet> facets;
    private DomainModel cached;

    public DomainModelSource(String type, Loader loader) {
        this(type, loader, List.of());
    }

    /** @param facets configured grouping for {@link #rootGroup()} (empty = no groups). */
    public DomainModelSource(String type, Loader loader, List<Facet> facets) {
        this.type = type;
        this.loader = loader;
        this.facets = facets == null ? List.of() : facets;
    }

    @Override public String type() { return type; }

    @Override public Collection<? extends Quizable> load() throws Exception {
        List<Quizable> out = new ArrayList<>();
        for (Quizable q : domain().instances()) {
            if (q != null && type.equals(q.typeName())) {
                out.add(q);
            }
        }
        return out;
    }

    @Override public QuizableGroup rootGroup() throws Exception {
        if (facets.isEmpty()) {
            return null;
        }
        return FacetGrouper.groupNested(type, load(), facets);
    }

    private synchronized DomainModel domain() throws Exception {
        if (cached == null) {
            cached = loader.get();
        }
        return cached;
    }

    /** Register one source per type of an ALREADY-LOADED domain (shared instance). */
    public static void register(QuizableStore store, DomainModel domain) {
        for (String t : domain.types()) {
            store.register(new DomainModelSource(t, () -> domain));
        }
    }
}
